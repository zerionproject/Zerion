package com.professor.zerion.android.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import javax.annotation.Nullable;

public final class SafeImageDecoder {

	public static final int MAX_DIMENSION = 16384;

	public static final int MAX_BYTES = 16 * 1024 * 1024;

	private SafeImageDecoder() {
	}

	public static boolean hasAllowedMagic(@Nullable byte[] data) {
		if (data == null || data.length < 12) return false;
		if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8
				&& (data[2] & 0xFF) == 0xFF) {
			return true;
		}
		if ((data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N'
				&& data[3] == 'G' && data[4] == 0x0D && data[5] == 0x0A
				&& data[6] == 0x1A && data[7] == 0x0A) {
			return true;
		}
		if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
				&& data[8] == 'W' && data[9] == 'E' && data[10] == 'B'
				&& data[11] == 'P') {
			return true;
		}
		if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
				&& data[3] == '8'
				&& (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
			return true;
		}
		if (data[4] == 'f' && data[5] == 't' && data[6] == 'y'
				&& data[7] == 'p') {
			char b0 = (char) (data[8] & 0xFF);
			char b1 = (char) (data[9] & 0xFF);
			char b2 = (char) (data[10] & 0xFF);
			char b3 = (char) (data[11] & 0xFF);
			String brand = "" + b0 + b1 + b2 + b3;
			if (brand.equals("heic") || brand.equals("heix")
					|| brand.equals("hevc") || brand.equals("hevx")
					|| brand.equals("heim") || brand.equals("heis")
					|| brand.equals("hevm") || brand.equals("hevs")
					|| brand.equals("mif1") || brand.equals("msf1")) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	public static Bitmap decode(@Nullable byte[] data) {
		return decode(data, 0);
	}

	@Nullable
	public static Bitmap decode(@Nullable byte[] data, int targetMaxDim) {
		if (data == null || data.length < 12) return null;
		if (data.length > MAX_BYTES) return null;
		if (!hasAllowedMagic(data)) return null;

		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
		if (bounds.outWidth > MAX_DIMENSION
				|| bounds.outHeight > MAX_DIMENSION) {
			return null;
		}

		BitmapFactory.Options decode = new BitmapFactory.Options();
		decode.inJustDecodeBounds = false;
		if (targetMaxDim > 0) {
			decode.inSampleSize = computeSampleSize(
					bounds.outWidth, bounds.outHeight, targetMaxDim);
		}
		try {
			return BitmapFactory.decodeByteArray(
					data, 0, data.length, decode);
		} catch (OutOfMemoryError e) {
			return null;
		}
	}

	@Nullable
	public static BitmapFactory.Options probeBounds(byte[] data) {
		if (data == null || data.length < 12) return null;
		if (data.length > MAX_BYTES) return null;
		if (!hasAllowedMagic(data)) return null;
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
		if (bounds.outWidth > MAX_DIMENSION
				|| bounds.outHeight > MAX_DIMENSION) {
			return null;
		}
		return bounds;
	}

	public static boolean hasAllowedMagic(InputStream is) {
		if (is == null) return false;
		try {
			byte[] head = new byte[12];
			int read = 0;
			while (read < head.length) {
				int r = is.read(head, read, head.length - read);
				if (r < 0) break;
				read += r;
			}
			if (read < 12) return false;
			return hasAllowedMagic(head);
		} catch (Exception e) {
			return false;
		}
	}

	public static byte[] sniffHead(InputStream is) {
		try {
			byte[] head = new byte[12];
			int read = 0;
			while (read < head.length) {
				int r = is.read(head, read, head.length - read);
				if (r < 0) break;
				read += r;
			}
			if (read < 12) return null;
			return head;
		} catch (Exception e) {
			return null;
		}
	}

	private static int computeSampleSize(int srcW, int srcH, int targetMaxDim) {
		int sample = 1;
		int max = Math.max(srcW, srcH);
		while (max / sample > targetMaxDim) {
			sample *= 2;
		}
		return sample;
	}

	@Nullable
	public static Bitmap decodeFromStream(InputStream is, int targetMaxDim) {
		if (is == null) return null;
		try {
			byte[] head = new byte[12];
			int read = 0;
			while (read < head.length) {
				int r = is.read(head, read, head.length - read);
				if (r < 0) break;
				read += r;
			}
			if (read < 12) return null;
			if (!hasAllowedMagic(head)) return null;
			java.io.ByteArrayOutputStream rest =
					new java.io.ByteArrayOutputStream();
			rest.write(head, 0, read);
			byte[] buf = new byte[8192];
			int n;
			while ((n = is.read(buf)) > 0) {
				rest.write(buf, 0, n);
				if (rest.size() > MAX_BYTES) return null;
			}
			byte[] all = rest.toByteArray();
			return decode(all, targetMaxDim);
		} catch (Exception e) {
			return null;
		}
	}

	public static InputStream prependHead(byte[] head, InputStream rest) {
		return new java.io.SequenceInputStream(
				new ByteArrayInputStream(head), rest);
	}
}
