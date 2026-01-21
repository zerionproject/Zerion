package com.professor.zerion.android.conversation.voice;

import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * Handles voice message recording as attachments for longer recordings.
 * Uses MediaRecorder with AAC/Opus encoding for efficient compression.
 *
 * Security notes:
 * - Temp files are deleted after attachment is stored
 * - Uses same attachment infrastructure as images (battle-tested)
 * - Size limit enforced by MessagingManager (32KB per attachment)
 */
@NotNullByDefault
public class VoiceAttachmentHandler {

	private static final Logger LOG = Logger.getLogger(VoiceAttachmentHandler.class.getName());

	// Recording parameters for efficient compression
	private static final int SAMPLE_RATE = 16000; // 16kHz for voice quality
	private static final int BIT_RATE = 12000; // 12kbps for good quality/size balance
	private static final int MAX_DURATION_MS = 300_000; // 5 minutes
	private static final int MIN_DURATION_MS = 500;
	private static final int PROGRESS_UPDATE_INTERVAL_MS = 100;

	// Briar attachment limit: 32KB
	// At 12kbps (1.5KB/sec), max ~21 seconds per attachment
	// For longer recordings, would need multiple attachments (future enhancement)
	private static final int MAX_FILE_SIZE_BYTES = 32_000;

	public interface AttachmentRecordingCallback {
		void onRecordingStarted();
		void onRecordingProgress(int durationMs, int amplitudeDb);
		void onRecordingCompleted(File audioFile, int durationMs, String mimeType);
		void onRecordingError(String error);
		void onRecordingCancelled();
	}

	private final Context context;
	private final Executor ioExecutor;
	private final Handler mainHandler;

	@Nullable
	private MediaRecorder mediaRecorder;
	@Nullable
	private File outputFile;
	@Nullable
	private AttachmentRecordingCallback currentCallback;

	private final AtomicBoolean isRecording = new AtomicBoolean(false);
	private long recordingStartTime;

	public VoiceAttachmentHandler(Context context, Executor ioExecutor) {
		this.context = context;
		this.ioExecutor = ioExecutor;
		this.mainHandler = new Handler(Looper.getMainLooper());
	}

	/**
	 * Start recording audio for attachment.
	 * Uses AAC encoding for broad compatibility.
	 */
	public boolean startRecording(AttachmentRecordingCallback callback) {
		if (isRecording.get()) {
			callback.onRecordingError("Recording already in progress");
			return false;
		}

		this.currentCallback = callback;

		ioExecutor.execute(() -> {
			try {
				initializeRecording();
				mainHandler.post(callback::onRecordingStarted);
				startProgressMonitoring();
			} catch (IOException e) {
				LOG.warning("Failed to start attachment recording: " + e.getMessage());
				cleanup();
				mainHandler.post(() -> callback.onRecordingError("Failed to start recording: " + e.getMessage()));
			}
		});

		return true;
	}

	@SuppressWarnings("deprecation")
	private void initializeRecording() throws IOException {
		// Create temp file in cache directory (auto-cleaned by system)
		File cacheDir = context.getCacheDir();
		outputFile = File.createTempFile("voice_attachment_", ".m4a", cacheDir);

		mediaRecorder = new MediaRecorder();
		mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

		// Use MPEG-4 container with AAC encoding for broad compatibility
		mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

		mediaRecorder.setAudioSamplingRate(SAMPLE_RATE);
		mediaRecorder.setAudioChannels(1); // Mono
		mediaRecorder.setAudioEncodingBitRate(BIT_RATE);
		mediaRecorder.setMaxDuration(MAX_DURATION_MS);
		mediaRecorder.setMaxFileSize(MAX_FILE_SIZE_BYTES);

		mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

		mediaRecorder.setOnInfoListener((mr, what, extra) -> {
			if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED ||
					what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
				stopRecording();
			}
		});

		mediaRecorder.setOnErrorListener((mr, what, extra) -> {
			LOG.severe("MediaRecorder error: " + what);
			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onRecordingError("Recording error occurred");
				}
			});
			cleanup();
		});

		mediaRecorder.prepare();
		mediaRecorder.start();
		isRecording.set(true);
		recordingStartTime = System.currentTimeMillis();
	}

	private void startProgressMonitoring() {
		mainHandler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (mediaRecorder != null && isRecording.get()) {
					try {
						int duration = (int) (System.currentTimeMillis() - recordingStartTime);
						int amplitude = mediaRecorder.getMaxAmplitude();

						// Convert to dB-like scale (0-100)
						int amplitudeDb = amplitude > 0
								? Math.min(100, (int) (20 * Math.log10(amplitude / 32767.0) + 100))
								: 0;

						if (currentCallback != null) {
							currentCallback.onRecordingProgress(duration, amplitudeDb);
						}

						mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
					} catch (IllegalStateException e) {
						// MediaRecorder was released, stop monitoring
					}
				}
			}
		}, PROGRESS_UPDATE_INTERVAL_MS);
	}

	/**
	 * Stop recording and finalize the audio file.
	 */
	public void stopRecording() {
		if (!isRecording.get() || mediaRecorder == null) return;

		isRecording.set(false);

		try {
			int duration = (int) (System.currentTimeMillis() - recordingStartTime);
			mediaRecorder.stop();

			if (duration < MIN_DURATION_MS) {
				LOG.warning("Attachment recording too short: " + duration + "ms");
				deleteOutputFile();
				mainHandler.post(() -> {
					if (currentCallback != null) {
						currentCallback.onRecordingError("Recording too short (minimum 0.5 seconds)");
					}
				});
			} else if (outputFile != null && outputFile.exists()) {
				long fileSize = outputFile.length();

				if (fileSize > MAX_FILE_SIZE_BYTES) {
					deleteOutputFile();
					mainHandler.post(() -> {
						if (currentCallback != null) {
							currentCallback.onRecordingError("Recording too long. Maximum ~30 seconds per voice attachment.");
						}
					});
				} else {
					File finalFile = outputFile;
					mainHandler.post(() -> {
						if (currentCallback != null) {
							currentCallback.onRecordingCompleted(finalFile, duration, "audio/mp4");
						}
					});
				}
			}
		} catch (IllegalStateException e) {
			LOG.warning("Error stopping MediaRecorder: " + e.getMessage());
			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onRecordingError("Failed to stop recording");
				}
			});
		} finally {
			releaseRecorder();
		}
	}

	/**
	 * Cancel recording and delete any temp files.
	 */
	public void cancelRecording() {
		if (!isRecording.get()) return;

		isRecording.set(false);
		releaseRecorder();
		deleteOutputFile();

		mainHandler.post(() -> {
			if (currentCallback != null) {
				currentCallback.onRecordingCancelled();
			}
		});
	}

	/**
	 * Get URI for the recorded file (to pass to attachment system).
	 */
	@Nullable
	public Uri getRecordedFileUri() {
		if (outputFile != null && outputFile.exists()) {
			return Uri.fromFile(outputFile);
		}
		return null;
	}

	/**
	 * Delete the temp file after attachment is stored.
	 * Should be called after the attachment is successfully created.
	 */
	public void cleanupTempFile() {
		deleteOutputFile();
	}

	public boolean isRecording() {
		return isRecording.get();
	}

	public int getCurrentDuration() {
		if (recordingStartTime > 0 && isRecording.get()) {
			return (int) (System.currentTimeMillis() - recordingStartTime);
		}
		return 0;
	}

	private void releaseRecorder() {
		if (mediaRecorder != null) {
			try {
				mediaRecorder.release();
			} catch (IllegalStateException e) {
				LOG.warning("Error releasing MediaRecorder: " + e.getMessage());
			}
			mediaRecorder = null;
		}
		currentCallback = null;
	}

	private void deleteOutputFile() {
		if (outputFile != null) {
			if (outputFile.exists()) {
				outputFile.delete();
			}
			outputFile = null;
		}
	}

	private void cleanup() {
		isRecording.set(false);
		releaseRecorder();
		deleteOutputFile();
	}

	/**
	 * Release all resources. Call when done with this handler.
	 */
	public void release() {
		if (isRecording.get()) {
			cancelRecording();
		}
		cleanup();
	}

	/**
	 * Get the maximum recording duration in seconds.
	 */
	public static int getMaxDurationSeconds() {
		// At 12kbps (1.5KB/sec), 32KB = ~21 seconds
		// Round down for safety margin
		return 20;
	}
}
