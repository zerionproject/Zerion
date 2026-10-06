package com.professor.zerion.android.vault.utils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

@NotNullByDefault
public final class Mp4MetadataScrubber {

	private static final int MAX_BOXES = 4096;

	private static final int CHUNK = 64 * 1024;

	private static final byte[] ANDROID_KEY =
			"com.android.".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] FREE =
			"free".getBytes(StandardCharsets.US_ASCII);

	private Mp4MetadataScrubber() {
	}

	public static void scrub(File file) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(file, "rw")) {
			long end = f.length();
			long pos = 0;
			boolean movie = false;
			int count = 0;
			while (pos < end) {
				if (++count > MAX_BOXES) throw new IOException("too many boxes");
				Box b = box(f, pos, end);
				if (b.is("moov")) {
					movie = true;
					scrubChildren(f, b, true);
				} else if (b.is("meta") || b.is("udta")) {
					neutralise(f, b);
				}
				pos = b.end;
			}
			if (!movie) throw new IOException("no movie box");
			pos = 0;
			while (pos < end) {
				Box b = box(f, pos, end);
				if (!b.is("mdat") && contains(f, b.start, b.end, ANDROID_KEY)) {
					throw new IOException("device details left in the file");
				}
				pos = b.end;
			}
		}
	}

	private static void scrubChildren(RandomAccessFile f, Box parent,
			boolean movie) throws IOException {
		long pos = parent.start + parent.header;
		int count = 0;
		while (pos < parent.end) {
			if (++count > MAX_BOXES) throw new IOException("too many boxes");
			if (parent.end - pos < 8) {
				throw new IOException("box ends inside a header");
			}
			Box b = box(f, pos, parent.end);
			if (b.is("meta") || b.is("udta")) {
				neutralise(f, b);
			} else if (movie && b.is("trak")) {
				scrubChildren(f, b, false);
			}
			pos = b.end;
		}
	}

	private static void neutralise(RandomAccessFile f, Box b)
			throws IOException {
		f.seek(b.start + 4);
		f.write(FREE);
		byte[] zeros = new byte[(int) Math.min(CHUNK, b.end - b.start)];
		long pos = b.start + b.header;
		f.seek(pos);
		while (pos < b.end) {
			int n = (int) Math.min(zeros.length, b.end - pos);
			f.write(zeros, 0, n);
			pos += n;
		}
	}

	private static Box box(RandomAccessFile f, long pos, long limit)
			throws IOException {
		if (limit - pos < 8) throw new IOException("truncated box header");
		byte[] h = new byte[16];
		f.seek(pos);
		f.readFully(h, 0, 8);
		long size = u32(h, 0);
		int header = 8;
		if (size == 1) {
			if (limit - pos < 16) throw new IOException("truncated box header");
			f.readFully(h, 8, 8);
			size = (u32(h, 8) << 32) | u32(h, 12);
			header = 16;
			if (size < 0) throw new IOException("box too large");
		} else if (size == 0) {
			size = limit - pos;
		}
		if (size < header || size > limit - pos) {
			throw new IOException("malformed box size");
		}
		String type = new String(h, 4, 4, StandardCharsets.ISO_8859_1);
		return new Box(type, pos, pos + size, header);
	}

	private static boolean contains(RandomAccessFile f, long start, long end,
			byte[] needle) throws IOException {
		byte[] buf = new byte[CHUNK + needle.length];
		long pos = start;
		int carry = 0;
		while (pos < end) {
			int n = (int) Math.min(CHUNK, end - pos);
			f.seek(pos);
			f.readFully(buf, carry, n);
			int len = carry + n;
			if (indexOf(buf, len, needle) >= 0) return true;
			carry = Math.min(needle.length - 1, len);
			System.arraycopy(buf, len - carry, buf, 0, carry);
			pos += n;
		}
		return false;
	}

	private static int indexOf(byte[] hay, int len, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= len; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	private static long u32(byte[] d, int off) {
		return ((d[off] & 0xFFL) << 24) | ((d[off + 1] & 0xFFL) << 16)
				| ((d[off + 2] & 0xFFL) << 8) | (d[off + 3] & 0xFFL);
	}

	private static final class Box {

		private final String type;
		private final long start;
		private final long end;
		private final int header;

		private Box(String type, long start, long end, int header) {
			this.type = type;
			this.start = start;
			this.end = end;
			this.header = header;
		}

		private boolean is(String t) {
			return type.equals(t);
		}
	}
}
