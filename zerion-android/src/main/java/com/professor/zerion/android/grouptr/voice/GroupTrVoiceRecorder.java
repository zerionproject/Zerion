package com.professor.zerion.android.grouptr.voice;

import com.professor.zerion.android.attachment.OggCommentStripper;
import com.professor.zerion.android.vault.utils.SecureMemory;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Random;

@RequiresApi(Build.VERSION_CODES.Q)
public class GroupTrVoiceRecorder {

	public interface Listener {
		void onRecordingFinished(byte[] oggOpus, long durationMs);

		void onRecordingFailed(String reason);
	}

	private static final int SAMPLE_RATE = 16_000;
	private static final int BITRATE = 24_000;
	public static final long MAX_DURATION_MS = 5L * 60L * 1000L;

	private final Context context;
	private MediaRecorder recorder;
	private File outputFile;
	private long startedAt;
	private boolean recording;
	private volatile boolean maxDurationReached;

	public GroupTrVoiceRecorder(Context context) {
		this.context = context.getApplicationContext();
	}

	public boolean start() {
		if (recording) return false;
		maxDurationReached = false;
		try {
			outputFile = File.createTempFile("grouptr_voice_", ".ogg",
					context.getCacheDir());
			recorder = android.os.Build.VERSION.SDK_INT >= 31
					? new MediaRecorder(context) : new MediaRecorder();
			recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
			recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
			recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
			recorder.setAudioSamplingRate(SAMPLE_RATE);
			recorder.setAudioEncodingBitRate(BITRATE);
			recorder.setAudioChannels(1);
			recorder.setMaxDuration((int) MAX_DURATION_MS);
			recorder.setOnInfoListener((mr, what, extra) -> {
				if (what == MediaRecorder
						.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
					maxDurationReached = true;
				}
			});
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
		long duration = Math.min(MAX_DURATION_MS,
				SystemClock.elapsedRealtime() - startedAt);
		recording = false;
		boolean maxed = maxDurationReached;
		try {
			recorder.stop();
		} catch (RuntimeException ex) {
			if (!maxed) {
				releaseInternal();
				deleteOutputFile();
				listener.onRecordingFailed("recording too short");
				return;
			}
		}
		releaseInternal();
		byte[] data = readOutputFile();
		deleteOutputFile();
		if (data == null || data.length == 0) {
			listener.onRecordingFailed("empty recording");
			return;
		}
		byte[] clean;
		try {
			clean = cleanRecording(data, new SecureRandom());
		} catch (IOException | RuntimeException ex) {
			listener.onRecordingFailed("recording cannot be cleaned");
			return;
		} finally {
			Arrays.fill(data, (byte) 0);
		}
		listener.onRecordingFinished(clean, duration);
	}

	public static byte[] cleanRecording(byte[] recorded, Random serials)
			throws IOException {
		byte[] stripped = OggCommentStripper.withoutComments(recorded);
		try {
			return OggCommentStripper.withFreshSerials(stripped, serials);
		} finally {
			Arrays.fill(stripped, (byte) 0);
		}
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
		return Math.min(MAX_DURATION_MS,
				SystemClock.elapsedRealtime() - startedAt);
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
			secureWipe(outputFile);
			if (!outputFile.delete()) outputFile.deleteOnExit();
		}
		outputFile = null;
	}

	private static void secureWipe(File f) {
		SecureMemory.secureDeleteFile(f, 0L, true);
	}
}
