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
import javax.annotation.Nullable;

@NotNullByDefault
public class VoiceAttachmentHandler {
	private static final int SAMPLE_RATE = 16000;
	private static final int BIT_RATE = 12000;
	private static final int MAX_DURATION_MS = 300_000;
	private static final int MIN_DURATION_MS = 500;
	private static final int PROGRESS_UPDATE_INTERVAL_MS = 100;
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
				cleanup();
				mainHandler.post(() -> callback.onRecordingError("Failed to start recording: " + e.getMessage()));
			}
		});

		return true;
	}

	@SuppressWarnings("deprecation")
	private void initializeRecording() throws IOException {
		File cacheDir = context.getCacheDir();
		outputFile = File.createTempFile("voice_attachment_", ".m4a", cacheDir);

		mediaRecorder = new MediaRecorder();
		mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
		mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

		mediaRecorder.setAudioSamplingRate(SAMPLE_RATE);
		mediaRecorder.setAudioChannels(1);
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

	public void stopRecording() {
		if (!isRecording.get() || mediaRecorder == null) return;

		isRecording.set(false);

		try {
			int duration = (int) (System.currentTimeMillis() - recordingStartTime);
			mediaRecorder.stop();

			if (duration < MIN_DURATION_MS) {
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
			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onRecordingError("Failed to stop recording");
				}
			});
		} finally {
			releaseRecorder();
		}
	}

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

	@Nullable
	public Uri getRecordedFileUri() {
		if (outputFile != null && outputFile.exists()) {
			return Uri.fromFile(outputFile);
		}
		return null;
	}

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
			}
			mediaRecorder = null;
		}
		currentCallback = null;
	}

	private void deleteOutputFile() {
		if (outputFile != null) {
			if (outputFile.exists()) {
				try {
					long len = outputFile.length();
					if (len > 0 && len < 50L * 1024 * 1024) {
						try (java.io.RandomAccessFile raf =
								new java.io.RandomAccessFile(
										outputFile, "rw")) {
							byte[] zeroes = new byte[8192];
							long written = 0;
							while (written < len) {
								int chunk = (int) Math.min(zeroes.length,
										len - written);
								raf.write(zeroes, 0, chunk);
								written += chunk;
							}
							raf.getFD().sync();
						}
					}
				} catch (java.io.IOException ignored) {
				}
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

	public void release() {
		if (isRecording.get()) {
			cancelRecording();
		}
		cleanup();
	}

	public static int getMaxDurationSeconds() {
		return 20;
	}
}
