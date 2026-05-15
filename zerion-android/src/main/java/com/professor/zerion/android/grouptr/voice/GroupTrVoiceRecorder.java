package com.professor.zerion.android.grouptr.voice;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@RequiresApi(Build.VERSION_CODES.Q)
public class GroupTrVoiceRecorder {

	public interface Listener {
		void onRecordingFinished(byte[] oggOpus, long durationMs);

		void onRecordingFailed(String reason);
	}

	private static final int SAMPLE_RATE = 16_000;
	private static final int BITRATE = 24_000;
	private static final long MAX_DURATION_MS = 5L * 60L * 1000L;

	private final Context context;
	private MediaRecorder recorder;
	private File outputFile;
	private long startedAt;
	private boolean recording;

	public GroupTrVoiceRecorder(Context context) {
		this.context = context.getApplicationContext();
	}

	public boolean start() {
		if (recording) return false;
		try {
			outputFile = File.createTempFile("grouptr_voice_", ".ogg",
					context.getCacheDir());
			recorder = new MediaRecorder(context);
			recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
			recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
			recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
			recorder.setAudioSamplingRate(SAMPLE_RATE);
			recorder.setAudioEncodingBitRate(BITRATE);
			recorder.setAudioChannels(1);
			recorder.setMaxDuration((int) MAX_DURATION_MS);
			recorder.setOutputFile(outputFile.getAbsolutePath());
			recorder.prepare();
			recorder.start();
			startedAt = SystemClock.elapsedRealtime();
			recording = true;
			return true;
		} catch (IOException | RuntimeException ex) {
			releaseInternal();
			deleteOutputFile();
			return false;
		}
	}

	public void stop(Listener listener) {
		if (!recording) {
			listener.onRecordingFailed("not recording");
			return;
		}
		long duration = SystemClock.elapsedRealtime() - startedAt;
		recording = false;
		try {
			recorder.stop();
		} catch (RuntimeException ex) {
			releaseInternal();
			deleteOutputFile();
			listener.onRecordingFailed("recording too short");
			return;
		}
		releaseInternal();
		byte[] data = readOutputFile();
		deleteOutputFile();
		if (data == null || data.length == 0) {
			listener.onRecordingFailed("empty recording");
			return;
		}
		listener.onRecordingFinished(data, duration);
	}

	public void cancel() {
		if (!recording) return;
		recording = false;
		try {
			recorder.stop();
		} catch (RuntimeException ignored) {
		}
		releaseInternal();
		deleteOutputFile();
	}

	public boolean isRecording() {
		return recording;
	}

	public long elapsedMs() {
		if (!recording) return 0L;
		return SystemClock.elapsedRealtime() - startedAt;
	}

	private void releaseInternal() {
		if (recorder != null) {
			try {
				recorder.release();
			} catch (RuntimeException ignored) {
			}
			recorder = null;
		}
	}

	private byte[] readOutputFile() {
		if (outputFile == null || !outputFile.exists()) return null;
		try (FileInputStream in = new FileInputStream(outputFile)) {
			long len = outputFile.length();
			if (len <= 0 || len > 10L * 1024L * 1024L) return null;
			byte[] out = new byte[(int) len];
			int off = 0;
			while (off < out.length) {
				int n = in.read(out, off, out.length - off);
				if (n < 0) break;
				off += n;
			}
			return out;
		} catch (IOException ex) {
			return null;
		}
	}

	private void deleteOutputFile() {
		if (outputFile != null && outputFile.exists()) {
			if (!outputFile.delete()) outputFile.deleteOnExit();
		}
		outputFile = null;
	}
}
