package com.professor.zerion.android.grouptr.voice;

import com.professor.zerion.android.vault.utils.SecureMemory;

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
		SecureMemory.secureDeleteFile(f, 0L, true);
	}
}
