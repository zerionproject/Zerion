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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.core.app.ActivityCompat;

@NotNullByDefault
public class VoiceMessageRecorder {

	private static final Logger LOG = Logger.getLogger(VoiceMessageRecorder.class.getName());

	private static final int SAMPLE_RATE = 8000;
	private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
	private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	private static final int CHUNK_SIZE = 4096;

	private static final int MAX_DURATION_MS = 18000;
	private static final int MIN_DURATION_MS = 300;

	private static final int PROGRESS_UPDATE_INTERVAL_MS = 100;

	private final Context context;
	private final Executor ioExecutor;
	private final Handler mainHandler;

	private AudioRecord audioRecord;
	private StreamingAudioEncryptor encryptor;
	private Thread recordingThread;
	private final AtomicBoolean isRecording = new AtomicBoolean(false);
	private final AtomicBoolean isCancelled = new AtomicBoolean(false);

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
				LOG.warning("Failed to start streaming recording: " + e.getMessage());
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

		encryptor = new StreamingAudioEncryptor();

		// SECURITY: Set AAD context with GroupId for replay protection
		// MessageId will be zero bytes (placeholder) since message doesn't exist yet
		// The actual MessageId binding happens via the message storage in Bramble
		byte[] formatVersion = new byte[]{1};
		byte[] messageIdPlaceholder = new byte[0];  // Empty - message not created yet
		encryptor.setAADContext(formatVersion, groupId, messageIdPlaceholder);

		isRecording.set(true);
		isCancelled.set(false);
		recordingStartTime = System.currentTimeMillis();

		audioRecord.startRecording();

		mainHandler.post(callback::onRecordingStarted);

		byte[] iv = encryptor.getIV();

		// Wrap the session key using AES-GCM with a key derived from groupId
		// This produces a 48-byte wrapped key (32 bytes ciphertext + 16 bytes GCM tag)
		byte[] wrappedKey;
		try {
			javax.crypto.SecretKey wrapKey = deriveWrapKey(groupId);
			wrappedKey = encryptor.getEncryptedKey(wrapKey);
			// Zeroize wrap key
			byte[] wrapKeyBytes = wrapKey.getEncoded();
			java.util.Arrays.fill(wrapKeyBytes, (byte) 0);

			// Debug log to verify wrapped key length
			LOG.info("✓ Wrapped key length = " + wrappedKey.length + " bytes (expected 48)");
		} catch (Exception e) {
			throw new RuntimeException("Failed to wrap session key", e);
		}

		mainHandler.post(() -> callback.onEncryptionInit(iv, wrappedKey));

		recordingThread = new Thread(() -> streamingRecordingLoop(callback));
		recordingThread.start();
	}

	@android.annotation.SuppressLint("MissingPermission")
	private void streamingRecordingLoop(EncryptedChunkCallback callback) {
		byte[] buffer = new byte[CHUNK_SIZE];
		long lastProgressUpdate = System.currentTimeMillis();
		int totalChunks = 0;

		try {
			while (isRecording.get() && !isCancelled.get()) {
				long currentDuration = System.currentTimeMillis() - recordingStartTime;
				if (currentDuration >= MAX_DURATION_MS) {
					LOG.info("Max recording duration reached");
					break;
				}

				int bytesRead = audioRecord.read(buffer, 0, CHUNK_SIZE);

				if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION) {
					throw new IllegalStateException("AudioRecord in invalid state");
				} else if (bytesRead == AudioRecord.ERROR_BAD_VALUE) {
					throw new IllegalArgumentException("Invalid AudioRecord read parameters");
				} else if (bytesRead > 0) {
					int amplitude = calculateAmplitude(buffer, bytesRead);

					StreamingAudioEncryptor.EncryptedChunk encryptedChunk =
							encryptor.encryptChunk(buffer, bytesRead);

					Arrays.fill(buffer, 0, bytesRead, (byte) 0);

					mainHandler.post(() -> callback.onEncryptedChunk(
							encryptedChunk.ciphertext,
							encryptedChunk.ciphertext.length,
							encryptedChunk.tag
					));

					totalChunks++;

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
				LOG.info("Recording cancelled by user");
				mainHandler.post(callback::onCancelled);
			} else if (finalDuration < MIN_DURATION_MS) {
				LOG.warning("Recording too short: " + finalDuration + "ms");
				mainHandler.post(() -> callback.onError(
						new IllegalStateException("Recording too short (min " + MIN_DURATION_MS + "ms)")
				));
			} else {
				LOG.info("Recording completed: " + finalDuration + "ms, " + totalChunks + " chunks");

				final int finalChunkCount = totalChunks;
				try {
					byte[] globalMAC = encryptor.computeGlobalMAC(finalChunkCount, finalDuration);
					mainHandler.post(() -> callback.onEncryptionFinal(globalMAC, finalDuration, finalChunkCount));
					Arrays.fill(globalMAC, (byte) 0);
				} catch (Exception e) {
					LOG.severe("Failed to generate global MAC: " + e.getMessage());
					mainHandler.post(() -> callback.onError(e));
				}
			}

		} catch (Exception e) {
			LOG.severe("Recording error: " + e.getMessage());
			mainHandler.post(() -> callback.onError(e));
		} finally {
			Arrays.fill(buffer, (byte) 0);
			cleanup();
		}
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
		if (isRecording.compareAndSet(true, false)) {
			LOG.info("Stopping streaming recording");
		}
	}

	public void cancelStreamingRecording() {
		if (isRecording.get()) {
			LOG.info("Cancelling streaming recording");
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
			} catch (IllegalStateException e) {
				LOG.warning("Error releasing AudioRecord: " + e.getMessage());
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
				LOG.warning("Failed to start legacy recording: " + e.getMessage());
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
			LOG.severe("MediaRecorder error: " + what);
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
					LOG.warning("Legacy recording too short: " + duration + "ms");
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
				LOG.warning("Error stopping MediaRecorder: " + e.getMessage());
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
				LOG.warning("Error releasing MediaRecorder: " + e.getMessage());
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
		// Derive a wrapping key from the groupId using HKDF-like construction
		// This is secure because groupId is unique per conversation and known to both parties
		java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
		sha256.update("VOICE_KEY_WRAP".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		sha256.update(groupId);
		byte[] keyMaterial = sha256.digest();
		return new javax.crypto.spec.SecretKeySpec(keyMaterial, "AES");
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
