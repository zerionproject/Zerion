package com.professor.zerion.android.conversation.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import javax.inject.Inject;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;

/**
 * Secure voice message player with ephemeral playback support.
 */
@NotNullByDefault
public class VoiceMessagePlayer {

	private static final Logger LOG = getLogger(VoiceMessagePlayer.class.getName());

	private final Context context;
	private final Executor ioExecutor;
	private final Handler mainHandler;

	private MediaPlayer mediaPlayer;
	private PlaybackCallback currentCallback;
	private File currentFile;
	private boolean isPlaying = false;
	private Runnable progressRunnable;

	public interface PlaybackCallback {
		void onPlaybackStarted(int durationMs);
		void onPlaybackProgress(int positionMs);
		void onPlaybackCompleted();
		void onPlaybackError(String error);
		void onPlaybackPaused();
	}

	@Inject
	public VoiceMessagePlayer(Context context, Executor ioExecutor) {
		this.context = context;
		this.ioExecutor = ioExecutor;
		this.mainHandler = new Handler(Looper.getMainLooper());
	}

	/**
	 * Plays a voice message from file.
	 * File will be deleted after playback if ephemeral is true.
	 */
	public void playVoiceMessage(File audioFile, boolean ephemeral,
			PlaybackCallback callback) {
		if (isPlaying) {
			stopPlayback();
		}

		this.currentCallback = callback;
		this.currentFile = audioFile;

		ioExecutor.execute(() -> {
			try {
				initializePlayer(audioFile, ephemeral);
			} catch (IOException e) {
				LOG.log(WARNING, "Failed to play audio", e);
				notifyError("Failed to play audio: " + e.getMessage());
			}
		});
	}

	private void initializePlayer(File audioFile, boolean ephemeral)
			throws IOException {
		if (!audioFile.exists()) {
			notifyError("Audio file not found");
			return;
		}

		mediaPlayer = new MediaPlayer();

		// Set audio attributes for secure playback
		AudioAttributes attributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
				.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				.setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
				.build();
		mediaPlayer.setAudioAttributes(attributes);

		// Set data source
		mediaPlayer.setDataSource(audioFile.getAbsolutePath());

		mediaPlayer.setOnPreparedListener(mp -> {
			isPlaying = true;
			int duration = mp.getDuration();

			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onPlaybackStarted(duration);
				}
			});

			mp.start();
			startProgressUpdates();
		});

		mediaPlayer.setOnCompletionListener(mp -> {
			isPlaying = false;
			stopProgressUpdates();

			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onPlaybackCompleted();
				}
			});

			// Delete file if ephemeral
			if (ephemeral && audioFile.exists()) {
				audioFile.delete();
			}

			releasePlayer();
		});

		mediaPlayer.setOnErrorListener((mp, what, extra) -> {
			LOG.log(WARNING, "Playback error: " + what + ", " + extra);
			notifyError("Playback error");
			releasePlayer();
			return true;
		});

		// Prepare asynchronously
		mediaPlayer.prepareAsync();
	}

	public void pausePlayback() {
		if (mediaPlayer != null && isPlaying) {
			mediaPlayer.pause();
			isPlaying = false;
			stopProgressUpdates();

			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onPlaybackPaused();
				}
			});
		}
	}

	public void resumePlayback() {
		if (mediaPlayer != null && !isPlaying) {
			mediaPlayer.start();
			isPlaying = true;
			startProgressUpdates();

			int duration = mediaPlayer.getDuration();
			mainHandler.post(() -> {
				if (currentCallback != null) {
					currentCallback.onPlaybackStarted(duration);
				}
			});
		}
	}

	public void stopPlayback() {
		isPlaying = false;
		stopProgressUpdates();
		releasePlayer();

		mainHandler.post(() -> {
			if (currentCallback != null) {
				currentCallback.onPlaybackCompleted();
			}
		});
	}

	public void seekTo(int positionMs) {
		if (mediaPlayer != null) {
			mediaPlayer.seekTo(positionMs);
		}
	}

	private void startProgressUpdates() {
		progressRunnable = new Runnable() {
			@Override
			public void run() {
				if (mediaPlayer != null && isPlaying) {
					int position = mediaPlayer.getCurrentPosition();
					if (currentCallback != null) {
						currentCallback.onPlaybackProgress(position);
					}
					mainHandler.postDelayed(this, 100); // Update every 100ms
				}
			}
		};
		mainHandler.post(progressRunnable);
	}

	private void stopProgressUpdates() {
		if (progressRunnable != null) {
			mainHandler.removeCallbacks(progressRunnable);
			progressRunnable = null;
		}
	}

	private void releasePlayer() {
		if (mediaPlayer != null) {
			try {
				if (mediaPlayer.isPlaying()) {
					mediaPlayer.stop();
				}
				mediaPlayer.release();
			} catch (IllegalStateException e) {
				// Already released
			}
			mediaPlayer = null;
		}
		currentFile = null;
		isPlaying = false;
	}

	private void notifyError(String message) {
		mainHandler.post(() -> {
			if (currentCallback != null) {
				currentCallback.onPlaybackError(message);
			}
		});
		releasePlayer();
	}

	public boolean isPlaying() {
		return isPlaying;
	}

	public void release() {
		stopPlayback();
		releasePlayer();
	}
}