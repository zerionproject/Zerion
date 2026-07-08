package com.professor.zerion.android.conversation.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.nullsafety.NullSafety;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;

import androidx.core.app.ActivityCompat;

@NotNullByDefault
public class VoiceMessageRecorder {
	private static final int SAMPLE_RATE = 8000;
	private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
	private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	private static final int CHUNK_SIZE = 1024;
	private static final int MAX_DURATION_MS = 300_000;
	private static final int MIN_DURATION_MS = 300;
	private static final int MAX_RAW_AUDIO_SIZE = 4_800_000;
	private static final int MAX_ENCRYPTED_PAYLOAD_SIZE = 250_000;

	private static final int PROGRESS_UPDATE_INTERVAL_MS = 100;

	private final Context context;
	private final Executor ioExecutor;
	private final Handler mainHandler;

	private AudioRecord audioRecord;
	private StreamingAudioEncryptor encryptor;
	private Thread recordingThread;
	private final AtomicBoolean isRecording = new AtomicBoolean(false);
	private final AtomicBoolean isCancelled = new AtomicBoolean(false);
	private ByteArrayOutputStream accumulatedPcm;
	private byte[] currentGroupId;
	private EncryptedChunkCallback currentEncryptedCallback;

	private MediaRecorder mediaRecorder;
	private File outputFile;

	private long recordingStartTime;
	private RecordingCallback currentCallback;

	public interface RecordingCallback {
		void onRecordingStarted();
		void onRecordingProgress(int durationMs, int amplitudeDb);
		void onRecordingCompleted(File audioFile, int durationMs);
		void onRecordingError(String error);
		void onRecordingCancelled();
	}

	@Inject
	public VoiceMessageRecorder(Context context, Executor ioExecutor) {
		this.context = context;
		this.ioExecutor = ioExecutor;
		this.mainHandler = new Handler(Looper.getMainLooper());
	}

	public boolean startStreamingRecording(byte[] groupId, EncryptedChunkCallback callback) {
		if (!checkAudioPermission()) {
			callback.onError(new SecurityException("Audio recording permission not granted"));
			return false;
		}

		if (isRecording.get()) {
			callback.onError(new IllegalStateException("Recording already in progress"));
			return false;
		}

		ioExecutor.execute(() -> {
			try {
				initializeStreamingRecording(groupId, callback);
			} catch (Exception e) {
				cleanup();
				callback.onError(e);
			}
		});

		return true;
	}

	@android.annotation.SuppressLint("MissingPermission")
	private void initializeStreamingRecording(byte[] groupId, EncryptedChunkCallback callback) throws Exception {
		int minBufferSize = AudioRecord.getMinBufferSize(
				SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

		if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
			throw new IllegalStateException("Invalid AudioRecord buffer size");
		}

		int bufferSize = Math.max(minBufferSize * 2, CHUNK_SIZE * 4);

		audioRecord = new AudioRecord(
				MediaRecorder.AudioSource.MIC,
				SAMPLE_RATE,
				CHANNEL_CONFIG,
				AUDIO_FORMAT,
				bufferSize
		);

		if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
			throw new IllegalStateException("AudioRecord initialization failed");
		}
		currentGroupId = Arrays.copyOf(groupId, groupId.length);
		currentEncryptedCallback = callback;
		accumulatedPcm = new ByteArrayOutputStream();

		isRecording.set(true);
		isCancelled.set(false);
		recordingStartTime = System.currentTimeMillis();

		audioRecord.startRecording();

		mainHandler.post(callback::onRecordingStarted);
		recordingThread = new Thread(() -> accumulatingRecordingLoop(callback));
		recordingThread.start();
	}

	@android.annotation.SuppressLint("MissingPermission")
	private void accumulatingRecordingLoop(EncryptedChunkCallback callback) {
		byte[] buffer = new byte[CHUNK_SIZE];
		long lastProgressUpdate = System.currentTimeMillis();

		try {
			while (isRecording.get() && !isCancelled.get()) {
				long currentDuration = System.currentTimeMillis() - recordingStartTime;
				if (currentDuration >= MAX_DURATION_MS) {
					break;
				}
				if (accumulatedPcm.size() >= MAX_RAW_AUDIO_SIZE) {
					break;
				}

				int bytesRead = audioRecord.read(buffer, 0, CHUNK_SIZE);

				if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
					throw new IllegalStateException("AudioRecord in invalid state");
				} else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
					throw new IllegalArgumentException("Invalid AudioRecord read parameters");
				} else if (bytesRead > 0) {
					int amplitude = calculateAmplitude(buffer, bytesRead);
					accumulatedPcm.write(buffer, 0, bytesRead);

					long now = System.currentTimeMillis();
					if (now - lastProgressUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
						int durationMs = (int) (now - recordingStartTime);
						mainHandler.post(() -> callback.onRecordingProgress(durationMs, amplitude));
						lastProgressUpdate = now;
					}
				}
			}

			int finalDuration = (int) (System.currentTimeMillis() - recordingStartTime);

			if (isCancelled.get()) {
				mainHandler.post(callback::onCancelled);
			} else if (finalDuration < MIN_DURATION_MS) {
				mainHandler.post(() -> callback.onError(
						new IllegalStateException("Recording too short (min " + MIN_DURATION_MS + "ms)")
				));
			} else {
				compressAndEncrypt(callback, finalDuration);
			}

		} catch (Exception e) {
			mainHandler.post(() -> callback.onError(e));
		} finally {
			Arrays.fill(buffer, (byte) 0);
			cleanup();
		}
	}

	private void compressAndEncrypt(EncryptedChunkCallback callback, int durationMs) {
		try {
			byte[] pcmData = accumulatedPcm.toByteArray();
			byte[] muLawData = pcmToMuLaw(pcmData);
			Arrays.fill(pcmData, (byte) 0);
			int maxPayloadData = MAX_ENCRYPTED_PAYLOAD_SIZE - 100;
			if (muLawData.length > maxPayloadData) {
				mainHandler.post(() -> callback.onError(
						new IllegalStateException("Voice message too long. Please keep it under 30 seconds.")));
				Arrays.fill(muLawData, (byte) 0);
				return;
			}
			encryptor = new StreamingAudioEncryptor();
			byte[] formatVersion = new byte[]{1};
			byte[] messageIdPlaceholder = new byte[0];
			encryptor.setAADContext(formatVersion, currentGroupId, messageIdPlaceholder);

			byte[] iv = encryptor.getIV();
			javax.crypto.SecretKey wrapKey = deriveWrapKey(currentGroupId);
			byte[] encryptedSessionKey = encryptor.getEncryptedKey(wrapKey);
			byte[] wrapKeyBytes = wrapKey.getEncoded();
			byte[] wrappedKey = new byte[32 + encryptedSessionKey.length];
			System.arraycopy(wrapKeyBytes, 0, wrappedKey, 0, 32);
			System.arraycopy(encryptedSessionKey, 0, wrappedKey, 32, encryptedSessionKey.length);
			java.util.Arrays.fill(wrapKeyBytes, (byte) 0);
			java.util.Arrays.fill(encryptedSessionKey, (byte) 0);
			mainHandler.post(() -> callback.onEncryptionInit(iv, wrappedKey));
			int chunkSize = 4096;
			int totalChunks = 0;
			int offset = 0;

			while (offset < muLawData.length) {
				int len = Math.min(chunkSize, muLawData.length - offset);
				byte[] chunkData = new byte[len];
				System.arraycopy(muLawData, offset, chunkData, 0, len);

				StreamingAudioEncryptor.EncryptedChunk encryptedChunk =
						encryptor.encryptChunk(chunkData, len);

				Arrays.fill(chunkData, (byte) 0);

				final byte[] ciphertext = encryptedChunk.ciphertext;
				final byte[] tag = encryptedChunk.tag;
				mainHandler.post(() -> callback.onEncryptedChunk(ciphertext, ciphertext.length, tag));

				totalChunks++;
				offset += len;
			}
			Arrays.fill(muLawData, (byte) 0);
			byte[] globalMAC = encryptor.computeGlobalMAC(totalChunks, durationMs);
			final byte[] globalMACCopy = Arrays.copyOf(globalMAC, globalMAC.length);
			Arrays.fill(globalMAC, (byte) 0);
			final int finalChunkCount = totalChunks;
			mainHandler.post(() -> {
				callback.onEncryptionFinal(globalMACCopy, durationMs, finalChunkCount);
				Arrays.fill(globalMACCopy, (byte) 0);
			});

		} catch (Exception e) {
			mainHandler.post(() -> callback.onError(e));
		}
	}

	private byte[] pcmToMuLaw(byte[] pcmData) {
		int numSamples = pcmData.length / 2;
		byte[] muLawData = new byte[numSamples];

		for (int i = 0; i < numSamples; i++) {
			int sample = (pcmData[i * 2] & 0xFF) | (pcmData[i * 2 + 1] << 8);
			muLawData[i] = linearToMuLaw((short) sample);
		}

		return muLawData;
	}

	private byte linearToMuLaw(short sample) {
		final int MULAW_MAX = 0x1FFF;
		final int MULAW_BIAS = 33;

		int sign = (sample >> 8) & 0x80;
		if (sign != 0) sample = (short) -sample;
		if (sample > MULAW_MAX) sample = MULAW_MAX;

		sample = (short) (sample + MULAW_BIAS);
		int exponent = 7;
		for (int expMask = 0x4000; (sample & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {}

		int mantissa = (sample >> (exponent + 3)) & 0x0F;
		byte muLawByte = (byte) ~(sign | (exponent << 4) | mantissa);

		return muLawByte;
	}

	private int calculateAmplitude(byte[] buffer, int length) {
		long sum = 0;
		int samplesCount = length / 2;

		for (int i = 0; i < length - 1; i += 2) {
			short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
			sum += Math.min(Math.abs(sample), 32767);
		}

		if (samplesCount == 0) return 0;

		int average = (int) (sum / samplesCount);

		return Math.min(100, (average * 100) / 32767);
	}

	public void stopStreamingRecording() {
		isRecording.compareAndSet(true, false);
	}

	public void cancelStreamingRecording() {
		if (isRecording.get()) {
			isCancelled.set(true);
			isRecording.set(false);
		}
	}

	private void cleanup() {
		if (audioRecord != null) {
			try {
				if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
					audioRecord.stop();
				}
				audioRecord.release();
			} catch (IllegalStateException ignored) {
			}
			audioRecord = null;
		}

		if (encryptor != null) {
			encryptor.zeroizeKeys();
			encryptor = null;
		}

		if (recordingThread != null && recordingThread.isAlive()) {
			try {
				recordingThread.interrupt();
				recordingThread.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			recordingThread = null;
		}
		if (accumulatedPcm != null) {
			try {
				accumulatedPcm.reset();
			} catch (Exception ignored) {}
			accumulatedPcm = null;
		}
		if (currentGroupId != null) {
			Arrays.fill(currentGroupId, (byte) 0);
			currentGroupId = null;
		}
		currentEncryptedCallback = null;

		isRecording.set(false);
		isCancelled.set(false);
	}

	public boolean startRecording(File outputFile, RecordingCallback callback) {
		if (!checkAudioPermission()) {
			callback.onRecordingError("Audio recording permission not granted");
			return false;
		}

		if (mediaRecorder != null) {
			callback.onRecordingError("Recording already in progress");
			return false;
		}

		this.outputFile = outputFile;
		this.currentCallback = callback;

		ioExecutor.execute(() -> {
			try {
				initializeLegacyRecording();
				mainHandler.post(() -> {
					if (currentCallback != null) {
						currentCallback.onRecordingStarted();
					}
				});
				startProgressMonitoring();
			} catch (IOException e) {
				releaseLegacyRecorder();
				mainHandler.post(() -> {
					if (currentCallback != null) {
						currentCallback.onRecordingError("Failed to start recording: " + e.getMessage());
					}
				});
			}
		});

		return true;
	}

	private void initializeLegacyRecording() throws IOException {
		mediaRecorder = new MediaRecorder();
		mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		mediaRecorder.setAudioSamplingRate(SAMPLE_RATE);
		mediaRecorder.setAudioChannels(1);
		mediaRecorder.setAudioEncodingBitRate(32000);
		mediaRecorder.setMaxDuration(MAX_DURATION_MS);

		File outputFile = NullSafety.requireNonNull(this.outputFile);
		mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

		mediaRecorder.setOnInfoListener((mr, what, extra) -> {
			if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
				stopLegacyRecording();
			}
		});

		mediaRecorder.setOnErrorListener((mr, what, extra) -> {
			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onRecordingError("Recording error occurred");
				}
			});
			releaseLegacyRecorder();
		});

		mediaRecorder.prepare();
		mediaRecorder.start();
		recordingStartTime = System.currentTimeMillis();
	}

	private void startProgressMonitoring() {
		mainHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (mediaRecorder != null) {
					try {
						int duration = (int) (System.currentTimeMillis() - recordingStartTime);
						int amplitude = mediaRecorder.getMaxAmplitude();

						int amplitudeDb = amplitude > 0
								? Math.min(100, (int) (20 * Math.log10(amplitude / 32767.0) + 100))
								: 0;

						if (currentCallback != null) {
							currentCallback.onRecordingProgress(duration, amplitudeDb);
						}

						mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
					} catch (IllegalStateException e) {
					}
				}
			}
		}, PROGRESS_UPDATE_INTERVAL_MS);
	}

	public void stopLegacyRecording() {
		if (mediaRecorder != null) {
			try {
				int duration = (int) (System.currentTimeMillis() - recordingStartTime);

				mediaRecorder.stop();

				if (duration < MIN_DURATION_MS) {
					if (outputFile != null && outputFile.exists()) {
						outputFile.delete();
					}
					mainHandler.post(() -> {
						if (currentCallback != null) {
							currentCallback.onRecordingError("Recording too short (min " + MIN_DURATION_MS + "ms)");
						}
					});
				} else {
					File finalFile = NullSafety.requireNonNull(outputFile);
					mainHandler.post(() -> {
						if (currentCallback != null) {
							currentCallback.onRecordingCompleted(finalFile, duration);
						}
					});
				}
			} catch (IllegalStateException e) {
				mainHandler.post(() -> {
					if (currentCallback != null) {
						currentCallback.onRecordingError("Failed to stop recording");
					}
				});
			} finally {
				releaseLegacyRecorder();
			}
		}
	}

	public void cancelLegacyRecording() {
		if (mediaRecorder != null) {
			releaseLegacyRecorder();

			if (outputFile != null && outputFile.exists()) {
				outputFile.delete();
			}

			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onRecordingCancelled();
				}
			});
		}
	}

	private void releaseLegacyRecorder() {
		if (mediaRecorder != null) {
			try {
				mediaRecorder.release();
			} catch (IllegalStateException e) {
			}
			mediaRecorder = null;
		}
		currentCallback = null;
		outputFile = null;
	}

	private boolean checkAudioPermission() {
		return ActivityCompat.checkSelfPermission(context,
				Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
	}

	private javax.crypto.SecretKey deriveWrapKey(byte[] groupId) throws Exception {
		byte[] keyMaterial = new byte[32];
		java.security.SecureRandom.getInstanceStrong().nextBytes(keyMaterial);
		javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(keyMaterial, "AES");
		java.util.Arrays.fill(keyMaterial, (byte) 0);
		return key;
	}

	public boolean isRecording() {
		return isRecording.get() || mediaRecorder != null;
	}

	public int getCurrentDuration() {
		if (recordingStartTime > 0) {
			return (int) (System.currentTimeMillis() - recordingStartTime);
		}
		return 0;
	}

	public void release() {
		if (isRecording.get()) {
			cancelStreamingRecording();
		}
		if (mediaRecorder != null) {
			cancelLegacyRecording();
		}
		cleanup();
	}
}
