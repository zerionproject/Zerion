package com.professor.zerion.android.attachment;

import com.professor.zerion.android.util.MediaMagic;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@NotNullByDefault
public final class AudioTagStripper {

	private static final int MAX_TRAILERS = 8;

	private static final int LYRICS3_V1_MAX = 5100;

	private AudioTagStripper() {
	}

	public static boolean hasLeadingTag(byte[] d) {
		return matches(d, 0, "ID3") || matches(d, 0, "APETAGEX");
	}

	public static byte[] withoutTags(byte[] d) throws IOException {
		int start = 0;
		int tag;
		while ((tag = leadingTagLength(d, start)) > 0) start += tag;
		if (start == 0 && !MediaMagic.isMpegAudioFrame(d, 0)) return d;
		int end = trailersStart(d, start, d.length);
		if (matches(d, start, "fLaC") || matches(d, start, "OggS")) {
			return Arrays.copyOfRange(d, start, end);
		}
		return withoutInnerTags(d, start, end);
	}

	public static byte[] flacWithoutMetadata(byte[] d) throws IOException {
		if (!matches(d, 0, "fLaC")) throw new IOException("not a flac stream");
		List<int[]> kept = new ArrayList<>();
		int pos = 4;
		boolean last = false;
		while (!last) {
			if (d.length - pos < 4) {
				throw new IOException("truncated flac metadata");
			}
			int header = d[pos] & 0xFF;
			int type = header & 0x7F;
			int length = ((d[pos + 1] & 0xFF) << 16)
					| ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
			last = (header & 0x80) != 0;
			if (type == 127 || length > d.length - pos - 4) {
				throw new IOException("malformed flac metadata block");
			}
			if ((pos == 4) != (type == 0) || (type == 0 && length != 34)) {
				throw new IOException("malformed flac stream information");
			}
			if (type == 0 || type == 3) kept.add(new int[] {pos, length});
			pos += 4 + length;
		}
		int end = trailersStart(d, pos, d.length);
		if (end > pos && (end - pos < 2 || (d[pos] & 0xFF) != 0xFF
				|| (d[pos + 1] & 0xFE) != 0xF8)) {
			throw new IOException("flac audio not found");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(end - pos + 128);
		out.write(d, 0, 4);
		for (int i = 0; i < kept.size(); i++) {
			int[] block = kept.get(i);
			int header = d[block[0]] & 0x7F;
			if (i == kept.size() - 1) header |= 0x80;
			out.write(header);
			out.write(d, block[0] + 1, 3 + block[1]);
		}
		out.write(d, pos, end - pos);
		return out.toByteArray();
	}

	public static byte[] waveWithoutMetadata(byte[] d) throws IOException {
		if (!MediaMagic.isRiff(d, "WAVE")) {
			throw new IOException("not a wave file");
		}
		long declared = u32le(d, 4);
		int end = (int) Math.min(d.length, 8L + declared);
		ByteArrayOutputStream out = new ByteArrayOutputStream(end);
		out.write(d, 0, 12);
		boolean format = false;
		boolean samples = false;
		int pos = 12;
		while (end - pos >= 8) {
			long size = u32le(d, pos + 4);
			boolean data = chunkIs(d, pos, "data");
			long bodyEnd = pos + 8L + size;
			if (bodyEnd > end) {
				if (!data) throw new IOException("truncated wave chunk");
				size = end - pos - 8L;
				bodyEnd = end;
			}
			boolean keep = data || chunkIs(d, pos, "fmt ")
					|| chunkIs(d, pos, "fact");
			if (keep) {
				out.write(d, pos, 4);
				writeU32le(out, size);
				out.write(d, pos + 8, (int) size);
				if ((size & 1) != 0) out.write(0);
				if (data) samples = true;
				else if (chunkIs(d, pos, "fmt ")) format = true;
			}
			pos = (int) Math.min(end, bodyEnd + (size & 1));
		}
		if (!format || !samples) {
			throw new IOException("incomplete wave file");
		}
		byte[] clean = out.toByteArray();
		long riff = clean.length - 8L;
		clean[4] = (byte) riff;
		clean[5] = (byte) (riff >> 8);
		clean[6] = (byte) (riff >> 16);
		clean[7] = (byte) (riff >> 24);
		return clean;
	}

	static int trailersStart(byte[] d, int start, int end) throws IOException {
		for (int i = 0; i < MAX_TRAILERS; i++) {
			int t = trailerLength(d, start, end);
			if (t == 0) return end;
			end -= t;
		}
		if (trailerLength(d, start, end) != 0) {
			throw new IOException("too many tags");
		}
		return end;
	}

	private static int leadingTagLength(byte[] d, int off) throws IOException {
		if (matches(d, off, "ID3")) return id3v2Length(d, off, d.length);
		if (!matches(d, off, "APETAGEX")) return 0;
		if (d.length - off < 32) throw new IOException("truncated tag");
		long size = u32le(d, off + 12);
		long flags = u32le(d, off + 20);
		boolean footer = (flags & 0x40000000L) == 0;
		if ((flags & 0x20000000L) == 0 || (footer && size < 32)) {
			throw new IOException("bad APE tag");
		}
		if (32 + size > d.length - off) throw new IOException("truncated tag");
		return (int) (32 + size);
	}

	private static int id3v2Length(byte[] d, int off, int end)
			throws IOException {
		if (end - off < 10) throw new IOException("truncated tag");
		int major = d[off + 3] & 0xFF;
		int revision = d[off + 4] & 0xFF;
		if (major < 2 || major > 4 || revision == 0xFF) {
			throw new IOException("unsupported tag");
		}
		boolean footer = major == 4 && (d[off + 5] & 0x10) != 0;
		long total = 10L + syncSafe(d, off + 6) + (footer ? 10 : 0);
		if (total > end - off) throw new IOException("truncated tag");
		return (int) total;
	}

	private static byte[] withoutInnerTags(byte[] d, int start, int end)
			throws IOException {
		ByteArrayOutputStream out = null;
		int copied = start;
		int i = start;
		while (end - i >= 10) {
			if (d[i] == 'I' && isInnerId3(d, i, end)) {
				int length = id3v2Length(d, i, end);
				if (out == null) out = new ByteArrayOutputStream(end - start);
				out.write(d, copied, i - copied);
				i += length;
				copied = i;
			} else {
				i++;
			}
		}
		if (out == null) {
			if (start == 0 && end == d.length) return d;
			return Arrays.copyOfRange(d, start, end);
		}
		out.write(d, copied, end - copied);
		return out.toByteArray();
	}

	private static boolean isInnerId3(byte[] d, int i, int end) {
		if (!matches(d, i, "ID3")) return false;
		int major = d[i + 3] & 0xFF;
		int flags = d[i + 5] & 0xFF;
		if (major < 2 || major > 4 || (d[i + 4] & 0xFF) == 0xFF) return false;
		int defined = major == 2 ? 0xC0 : major == 3 ? 0xE0 : 0xF0;
		if ((flags & ~defined) != 0) return false;
		for (int k = 6; k < 10; k++) {
			if ((d[i + k] & 0x80) != 0) return false;
		}
		if (end - i == 10) return true;
		int next = d[i + 10] & 0xFF;
		return next == 0 || (next >= 'A' && next <= 'Z')
				|| (next >= '0' && next <= '9');
	}

	private static int trailerLength(byte[] d, int start, int end)
			throws IOException {
		int n = end - start;
		if (n >= 128 && matches(d, end - 128, "TAG")) {
			if (n >= 128 + 227 && matches(d, end - 128 - 227, "TAG+")) {
				return 128 + 227;
			}
			return 128;
		}
		if (n >= 32 && matches(d, end - 32, "APETAGEX")) {
			long size = u32le(d, end - 32 + 12);
			long flags = u32le(d, end - 32 + 20);
			long total = size + ((flags & 0x80000000L) != 0 ? 32 : 0);
			if (size < 32 || total > n) throw new IOException("bad APE tag");
			return (int) total;
		}
		if (n >= 10 && matches(d, end - 10, "3DI")) {
			long total = 20L + syncSafe(d, end - 10 + 6);
			if (total > n) throw new IOException("truncated tag");
			return (int) total;
		}
		if (n >= 15 && matches(d, end - 9, "LYRICS200")) {
			long total = 15 + decimal(d, end - 15, 6);
			if (total > n || !matches(d, (int) (end - total), "LYRICSBEGIN")) {
				throw new IOException("bad lyrics tag");
			}
			return (int) total;
		}
		if (n >= 20 && matches(d, end - 9, "LYRICSEND")) {
			int from = Math.max(start, end - 20 - LYRICS3_V1_MAX);
			for (int i = end - 20; i >= from; i--) {
				if (matches(d, i, "LYRICSBEGIN")) return end - i;
			}
			throw new IOException("bad lyrics tag");
		}
		return 0;
	}

	private static long decimal(byte[] d, int off, int digits)
			throws IOException {
		long v = 0;
		for (int i = 0; i < digits; i++) {
			int c = d[off + i];
			if (c < '0' || c > '9') throw new IOException("malformed tag size");
			v = v * 10 + (c - '0');
		}
		return v;
	}

	private static int syncSafe(byte[] d, int off) throws IOException {
		int v = 0;
		for (int i = 0; i < 4; i++) {
			int b = d[off + i] & 0xFF;
			if (b >= 0x80) throw new IOException("malformed tag size");
			v = (v << 7) | b;
		}
		return v;
	}

	private static boolean chunkIs(byte[] d, int off, String id) {
		return matches(d, off, id);
	}

	static boolean matches(byte[] d, int off, String s) {
		if (off < 0 || d.length - off < s.length()) return false;
		for (int i = 0; i < s.length(); i++) {
			if (d[off + i] != (byte) s.charAt(i)) return false;
		}
		return true;
	}

	static long u32le(byte[] d, int off) {
		return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8)
				| ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
	}

	private static void writeU32le(ByteArrayOutputStream out, long v) {
		out.write((int) (v & 0xFF));
		out.write((int) ((v >> 8) & 0xFF));
		out.write((int) ((v >> 16) & 0xFF));
		out.write((int) ((v >> 24) & 0xFF));
	}
}
