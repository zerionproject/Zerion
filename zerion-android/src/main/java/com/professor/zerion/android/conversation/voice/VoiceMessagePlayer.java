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

import javax.inject.Inject;

@NotNullByDefault
public class VoiceMessagePlayer {

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

		AudioAttributes attributes = new AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
				.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				.setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
				.build();
		mediaPlayer.setAudioAttributes(attributes);

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

			if (ephemeral && audioFile.exists()) {
				audioFile.delete();
			}

			releasePlayer();
		});

		mediaPlayer.setOnErrorListener((mp, what, extra) -> {
			notifyError("Playback error");
			releasePlayer();
			return true;
		});

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
					mainHandler.postDelayed(this, 100);
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