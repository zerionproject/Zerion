package com.professor.zerion.android.attachment;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class DeviceMediaFiles {

	private DeviceMediaFiles() {
	}

	static final class Track {

		MediaFormat format;
		final List<byte[]> data = new ArrayList<>();
		final List<Long> times = new ArrayList<>();
		final List<Integer> flags = new ArrayList<>();
	}

	static Track video(String mime, int width, int height, int frames)
			throws IOException {
		MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
		fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
				MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
		fmt.setInteger(MediaFormat.KEY_BIT_RATE, 250_000);
		fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
		fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		byte[] frame = new byte[width * height * 3 / 2];
		for (int i = 0; i < frame.length; i++) frame[i] = (byte) (i * 7);
		return encode(fmt, frame, frames, 100_000L);
	}

	static Track audio(String mime, int rate, int bitRate, int units)
			throws IOException {
		MediaFormat fmt = MediaFormat.createAudioFormat(mime, rate, 1);
		fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
		if (MediaFormat.MIMETYPE_AUDIO_AAC.equals(mime)) {
			fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
					MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		}
		byte[] pcm = new byte[1920];
		for (int i = 0; i < pcm.length; i += 2) {
			short s = (short) (Math.sin(i / 20.0) * 8000);
			pcm[i] = (byte) s;
			pcm[i + 1] = (byte) (s >> 8);
		}
		return encode(fmt, pcm, units, pcm.length / 2 * 1_000_000L / rate);
	}

	static Track syntheticVorbis(int packets) {
		Track t = new Track();
		MediaFormat fmt = MediaFormat.createAudioFormat(
				MediaFormat.MIMETYPE_AUDIO_VORBIS, 48000, 1);
		byte[] id = new byte[30];
		byte[] magic = {1, 'v', 'o', 'r', 'b', 'i', 's'};
		System.arraycopy(magic, 0, id, 0, magic.length);
		id[11] = 1;
		id[12] = (byte) 0x80;
		id[13] = (byte) 0xBB;
		id[28] = (byte) 0xB8;
		id[29] = 1;
		byte[] setup = new byte[300];
		byte[] setupMagic = {5, 'v', 'o', 'r', 'b', 'i', 's'};
		System.arraycopy(setupMagic, 0, setup, 0, setupMagic.length);
		for (int i = setupMagic.length; i < setup.length; i++) {
			setup[i] = (byte) (i * 3);
		}
		fmt.setByteBuffer("csd-0", ByteBuffer.wrap(id));
		fmt.setByteBuffer("csd-1", ByteBuffer.wrap(setup));
		t.format = fmt;
		for (int p = 0; p < packets; p++) {
			byte[] b = new byte[60 + (p * 13) % 120];
			for (int i = 0; i < b.length; i++) b[i] = (byte) (i * 5 + p);
			b[0] = (byte) (b[0] & 0xFE);
			t.data.add(b);
			t.times.add(p * 21_333L);
			t.flags.add(MediaCodec.BUFFER_FLAG_KEY_FRAME);
		}
		return t;
	}

	private static Track encode(MediaFormat fmt, byte[] unit, int units,
			long usPerUnit) throws IOException {
		String mime = fmt.getString(MediaFormat.KEY_MIME);
		MediaCodec enc = MediaCodec.createEncoderByType(mime);
		Track t = new Track();
		enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		enc.start();
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		int fed = 0;
		boolean inputDone = false;
		boolean outputDone = false;
		long deadline = System.currentTimeMillis() + 30_000;
		try {
			while (!outputDone && System.currentTimeMillis() < deadline) {
				if (!inputDone) {
					int in = enc.dequeueInputBuffer(10_000);
					if (in >= 0) {
						ByteBuffer b = enc.getInputBuffer(in);
						b.clear();
						long pts = fed * usPerUnit;
						if (fed == units) {
							enc.queueInputBuffer(in, 0, 0, pts,
									MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							inputDone = true;
						} else {
							int n = Math.min(b.remaining(), unit.length);
							b.put(unit, 0, n);
							enc.queueInputBuffer(in, 0, n, pts, 0);
							fed++;
						}
					}
				}
				int out = enc.dequeueOutputBuffer(info, 10_000);
				if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					t.format = enc.getOutputFormat();
				} else if (out >= 0) {
					boolean config = (info.flags
							& MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
					if (!config && info.size > 0) {
						ByteBuffer ob = enc.getOutputBuffer(out);
						byte[] d = new byte[info.size];
						ob.position(info.offset);
						ob.get(d);
						t.data.add(d);
						t.times.add(info.presentationTimeUs);
						t.flags.add(info.flags
								& ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
					}
					enc.releaseOutputBuffer(out, false);
					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
							!= 0) {
						outputDone = true;
					}
				}
			}
		} finally {
			enc.stop();
			enc.release();
		}
		if (t.format == null || t.data.isEmpty()) {
			throw new IOException("encoder produced nothing for " + mime);
		}
		return t;
	}

	static void mux(File out, int container, Track... tracks)
			throws IOException {
		MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), container);
		try {
			int[] index = new int[tracks.length];
			for (int i = 0; i < tracks.length; i++) {
				index[i] = muxer.addTrack(tracks[i].format);
			}
			muxer.start();
			int[] next = new int[tracks.length];
			MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
			while (true) {
				int best = -1;
				for (int i = 0; i < tracks.length; i++) {
					if (next[i] < tracks[i].data.size() && (best < 0
							|| tracks[i].times.get(next[i])
							< tracks[best].times.get(next[best]))) {
						best = i;
					}
				}
				if (best < 0) break;
				byte[] d = tracks[best].data.get(next[best]);
				info.set(0, d.length, tracks[best].times.get(next[best]),
						tracks[best].flags.get(next[best]));
				muxer.writeSampleData(index[best], ByteBuffer.wrap(d), info);
				next[best]++;
			}
			muxer.stop();
		} finally {
			muxer.release();
		}
	}

	static List<String> stats(File f) throws IOException {
		MediaExtractor ex = new MediaExtractor();
		try {
			ex.setDataSource(f.getAbsolutePath());
			int n = ex.getTrackCount();
			String[] types = new String[n];
			long[] counts = new long[n];
			long[] bytes = new long[n];
			for (int i = 0; i < n; i++) {
				types[i] = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
				ex.selectTrack(i);
			}
			ByteBuffer buf = ByteBuffer.allocate(4 * 1024 * 1024);
			while (true) {
				int t = ex.getSampleTrackIndex();
				if (t < 0) break;
				int size = ex.readSampleData(buf, 0);
				if (size < 0) break;
				counts[t]++;
				bytes[t] += size;
				ex.advance();
			}
			List<String> out = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				out.add(types[i] + " " + counts[i] + " " + bytes[i]);
			}
			Collections.sort(out);
			return out;
		} finally {
			ex.release();
		}
	}

	static boolean contains(byte[] hay, byte[] needle) {
		if (needle.length == 0) return false;
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	static String describe(List<String> stats) {
		return Arrays.toString(stats.toArray());
	}
}
