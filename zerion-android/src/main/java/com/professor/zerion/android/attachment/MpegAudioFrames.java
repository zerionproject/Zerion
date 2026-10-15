package com.professor.zerion.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@NotNullByDefault
final class MpegAudioFrames {

	private static final int[][] KBPS = {
			{0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384,
					416, 448},
			{0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320,
					384},
			{0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256,
					320},
			{0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224,
					256},
			{0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}};

	private static final int[] MPEG1_RATES = {44100, 48000, 32000};

	private static final int ADTS_MAX_RATE_INDEX = 12;

	private static final String[] ENCODER_MARKERS = {"Xing", "Info", "VBRI",
			"LAME"};

	private MpegAudioFrames() {
	}

	static byte[] rebuilt(byte[] d) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
		int first = -1;
		boolean synced = false;
		long audio = 0;
		long junk = 0;
		int pos = 0;
		while (pos < d.length) {
			int length = frameAt(d, pos, first, synced);
			if (length < 0) {
				synced = false;
				junk++;
				pos++;
				continue;
			}
			synced = true;
			if (first < 0) {
				first = pos;
				if (hasEncoderMarker(d, pos, length)) {
					pos += length;
					continue;
				}
			}
			out.write(d, pos, length);
			audio += length;
			pos += length;
		}
		if (audio == 0 || junk > audio) {
			throw new IOException("too little audio between the frames");
		}
		return out.toByteArray();
	}

	static int frameLength(byte[] d, int off) {
		if (off < 0 || d.length - off < 4) return -1;
		int b1 = d[off + 1] & 0xFF;
		if ((d[off] & 0xFF) != 0xFF || (b1 & 0xE0) != 0xE0) return -1;
		return ((b1 >> 1) & 3) == 0 ? adtsLength(d, off) : mpegLength(d, off);
	}

	private static int frameAt(byte[] d, int pos, int first, boolean synced) {
		int length = frameLength(d, pos);
		if (length < 0 || length > d.length - pos) return -1;
		if (first >= 0 && !sameStream(d, first, pos)) return -1;
		if (synced) return length;
		int next = pos + length;
		if (next == d.length) return length;
		return frameLength(d, next) > 0 && sameStream(d, pos, next)
				? length : -1;
	}

	private static int mpegLength(byte[] d, int off) {
		int b1 = d[off + 1] & 0xFF;
		int b2 = d[off + 2] & 0xFF;
		int version = (b1 >> 3) & 3;
		int layer = (b1 >> 1) & 3;
		int bitrate = b2 >> 4;
		int rate = (b2 >> 2) & 3;
		if (version == 1 || bitrate == 0 || bitrate == 15 || rate == 3) {
			return -1;
		}
		boolean mpeg1 = version == 3;
		int row = mpeg1 ? 3 - layer : layer == 3 ? 3 : 4;
		int bps = KBPS[row][bitrate] * 1000;
		int hz = MPEG1_RATES[rate] >> (mpeg1 ? 0 : version == 2 ? 1 : 2);
		int padding = (b2 >> 1) & 1;
		if (layer == 3) return (12 * bps / hz + padding) * 4;
		int slots = layer == 1 && !mpeg1 ? 72 : 144;
		return slots * bps / hz + padding;
	}

	private static int adtsLength(byte[] d, int off) {
		if (d.length - off < 7) return -1;
		int b1 = d[off + 1] & 0xFF;
		if ((b1 & 0xF6) != 0xF0
				|| ((d[off + 2] >> 2) & 0xF) > ADTS_MAX_RATE_INDEX) {
			return -1;
		}
		int length = ((d[off + 3] & 3) << 11) | ((d[off + 4] & 0xFF) << 3)
				| ((d[off + 5] & 0xFF) >> 5);
		int header = (b1 & 1) != 0 ? 7 : 9 + 2 * (d[off + 6] & 3);
		return length > header ? length : -1;
	}

	private static boolean sameStream(byte[] d, int a, int b) {
		if (((d[a + 1] ^ d[b + 1]) & 0xFE) != 0) return false;
		int fields = (d[a + 1] & 0x06) == 0 ? 0xFC : 0x0C;
		return ((d[a + 2] ^ d[b + 2]) & fields) == 0;
	}

	private static boolean hasEncoderMarker(byte[] d, int off, int length) {
		int end = off + length;
		for (String marker : ENCODER_MARKERS) {
			for (int i = off + 4; i + marker.length() <= end; i++) {
				if (AudioTagStripper.matches(d, i, marker)) return true;
			}
		}
		return false;
	}
}
