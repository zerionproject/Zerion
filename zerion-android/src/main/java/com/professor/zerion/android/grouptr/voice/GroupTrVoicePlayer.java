package com.professor.zerion.android.grouptr.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class GroupTrVoicePlayer {

	public interface Listener {
		void onStarted();

		void onCompleted();

		void onError();
	}

	private final Context context;
	@Nullable
	private MediaPlayer player;
	@Nullable
	private File tempFile;

	public GroupTrVoicePlayer(Context context) {
		this.context = context.getApplicationContext();
	}

	public void play(byte[] oggOpus, Listener listener) {
		stop();
		try {
			tempFile = File.createTempFile("grouptr_voice_play_", ".ogg",
					context.getCacheDir());
			try (FileOutputStream out = new FileOutputStream(tempFile)) {
				out.write(oggOpus);
			}
			player = new MediaPlayer();
			player.setAudioAttributes(new AudioAttributes.Builder()
					.setUsage(AudioAttributes.USAGE_MEDIA)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build());
			player.setDataSource(tempFile.getAbsolutePath());
			player.setOnPreparedListener(mp -> {
				mp.start();
				listener.onStarted();
			});
			player.setOnCompletionListener(mp -> {
				releaseInternal();
				listener.onCompleted();
			});
			player.setOnErrorListener((mp, what, extra) -> {
				releaseInternal();
				listener.onError();
				return true;
			});
			player.prepareAsync();
		} catch (IOException | RuntimeException ex) {
			releaseInternal();
			listener.onError();
		}
	}

	public boolean isPlaying() {
		return player != null && player.isPlaying();
	}

	public void stop() {
		if (player != null) {
			try {
				if (player.isPlaying()) player.stop();
			} catch (IllegalStateException ignored) {
			}
		}
		releaseInternal();
	}

	private void releaseInternal() {
		if (player != null) {
			try {
				player.release();
			} catch (RuntimeException ignored) {
			}
			player = null;
		}
		if (tempFile != null) {
			secureWipe(tempFile);
			if (!tempFile.delete()) tempFile.deleteOnExit();
			tempFile = null;
		}
	}

	private static void secureWipe(java.io.File f) {
		try {
			if (!f.exists()) return;
			long len = f.length();
			if (len <= 0) return;
			try (java.io.RandomAccessFile raf =
					new java.io.RandomAccessFile(f, "rws")) {
				byte[] zeros = new byte[(int) Math.min(len, 8192)];
				long written = 0;
				while (written < len) {
					int chunk = (int) Math.min(zeros.length, len - written);
					raf.write(zeros, 0, chunk);
					written += chunk;
				}
				raf.getFD().sync();
			}
		} catch (java.io.IOException ignored) {
		}
	}
}
