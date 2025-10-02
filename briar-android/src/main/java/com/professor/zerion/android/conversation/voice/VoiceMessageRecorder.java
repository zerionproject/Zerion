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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;

/**
 * Secure voice message recorder with Opus encoding.
 * Records audio with consistent parameters to prevent fingerprinting.
 */
@NotNullByDefault
public class VoiceMessageRecorder {

	private static final Logger LOG = getLogger(VoiceMessageRecorder.class.getName());

	// Standardized recording parameters to prevent fingerprinting
	private static final int SAMPLE_RATE = 16000; // 16 kHz mono
	private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
	private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	private static final int BITRATE = 24000; // 24 kbps Opus
	private static final int MAX_DURATION_MS = 300000; // 5 minutes max

	private final Context context;
	private final Executor ioExecutor;
	private final OpusEncoder opusEncoder;
	private final Handler mainHandler;

	private AudioRecord audioRecord;
	private boolean isRecording = false;
	private RecordingCallback callback;
	private File outputFile;
	private long recordingStartTime;

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
		this.opusEncoder = new OpusEncoder(SAMPLE_RATE, 1, BITRATE);
		this.mainHandler = new Handler(Looper.getMainLooper());
	}

	public boolean hasRecordingPermission() {
		return ContextCompat.checkSelfPermission(context,
				Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
	}

	@RequiresPermission(Manifest.permission.RECORD_AUDIO)
	public void startRecording(RecordingCallback callback) {
		if (isRecording) {
			callback.onRecordingError("Already recording");
			return;
		}

		this.callback = callback;
		isRecording = true;
		recordingStartTime = System.currentTimeMillis();

		// Create output file with random name (no metadata)
		String randomFileName = UUID.randomUUID().toString() + ".opus";
		outputFile = new File(context.getCacheDir(), randomFileName);

		ioExecutor.execute(this::recordAudio);
		mainHandler.post(() -> callback.onRecordingStarted());
	}

	private void recordAudio() {
		try {
			int bufferSize = AudioRecord.getMinBufferSize(
					SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

			audioRecord = new AudioRecord(
					MediaRecorder.AudioSource.MIC,
					SAMPLE_RATE,
					CHANNEL_CONFIG,
					AUDIO_FORMAT,
					bufferSize
			);

			if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
				notifyError("Failed to initialize audio recorder");
				return;
			}

			audioRecord.startRecording();

			// PCM buffer
			byte[] pcmBuffer = new byte[bufferSize];
			ByteBuffer opusBuffer = ByteBuffer.allocateDirect(bufferSize);
			opusBuffer.order(ByteOrder.LITTLE_ENDIAN);

			try (FileOutputStream fos = new FileOutputStream(outputFile)) {
				// Write minimal Opus header
				opusEncoder.writeHeader(fos);

				while (isRecording) {
					int bytesRead = audioRecord.read(pcmBuffer, 0, bufferSize);

					if (bytesRead > 0) {
						// Check max duration
						long duration = System.currentTimeMillis() - recordingStartTime;
						if (duration > MAX_DURATION_MS) {
							stopRecording();
							break;
						}

						// Calculate amplitude for UI feedback (optional)
						int amplitude = calculateAmplitude(pcmBuffer, bytesRead);
						int durationMs = (int) duration;

						mainHandler.post(() -> {
							if (callback != null) {
								callback.onRecordingProgress(durationMs, amplitude);
							}
						});

						// Encode to Opus and write
						opusBuffer.clear();
						opusBuffer.put(pcmBuffer, 0, bytesRead);
						byte[] opusData = opusEncoder.encode(opusBuffer, bytesRead / 2);
						if (opusData != null) {
							fos.write(opusData);
						}
					}
				}

				// Finalize Opus stream
				opusEncoder.finalizeStream(fos);
			}

			int finalDuration = (int) (System.currentTimeMillis() - recordingStartTime);
			File finalFile = outputFile;
			mainHandler.post(() -> {
				if (callback != null) {
					callback.onRecordingCompleted(finalFile, finalDuration);
				}
			});

		} catch (IOException e) {
			LOG.log(WARNING, "Recording failed", e);
			notifyError("Recording failed: " + e.getMessage());
		} finally {
			releaseRecorder();
		}
	}

	public void stopRecording() {
		isRecording = false;
		releaseRecorder();
	}

	public void cancelRecording() {
		isRecording = false;
		releaseRecorder();

		// Delete the file
		if (outputFile != null && outputFile.exists()) {
			outputFile.delete();
		}

		mainHandler.post(() -> {
			if (callback != null) {
				callback.onRecordingCancelled();
			}
		});
	}

	private void releaseRecorder() {
		if (audioRecord != null) {
			try {
				audioRecord.stop();
			} catch (IllegalStateException e) {
				// Already stopped
			}
			audioRecord.release();
			audioRecord = null;
		}
	}

	private int calculateAmplitude(byte[] buffer, int size) {
		long sum = 0;
		for (int i = 0; i < size; i += 2) {
			// Convert bytes to 16-bit PCM sample
			short sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
			sum += Math.abs(sample);
		}

		double average = sum / (double) (size / 2);
		// Convert to pseudo-dB scale (0-100)
		return (int) (20 * Math.log10(average / 32768.0) + 100);
	}

	private void notifyError(String message) {
		mainHandler.post(() -> {
			if (callback != null) {
				callback.onRecordingError(message);
			}
		});
		isRecording = false;
		releaseRecorder();
	}

	public boolean isRecording() {
		return isRecording;
	}

	public File getRecordingFile() {
		return outputFile;
	}
}