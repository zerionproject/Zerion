package com.professor.zerion.android.util;

import android.graphics.Bitmap;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import static org.zerionproject.app.api.attachment.MediaConstants.MAX_ATTACHMENT_SIZE;

@NotNullByDefault
public final class ReceivedImageGate {

	public interface PixelDecoder {

		@Nullable
		Bitmap decode(byte[] checkedImage, int maxSide);

		@Nullable
		default Bitmap decodeAlone(byte[] checkedImage, int maxSide) {
			return decode(checkedImage, maxSide);
		}

		default void release() {
		}
	}

	public static final int MAX_BYTES = MAX_ATTACHMENT_SIZE;

	public static final long MAX_PIXELS = 40_000_000L;

	public static final long MAX_PROGRESSIVE_PIXELS = 8_000_000L;

	public static final int MAX_GIF_FRAMES = 1000;

	public static final int MAX_JPEG_SCANS = 100;

	private static final int SOF_BASELINE = 0xC0;
	private static final int SOF_EXTENDED = 0xC1;
	private static final int SOF_PROGRESSIVE = 0xC2;
	private static final int SEVERAL_SCANS = 0x100;

	private static final int UNUSED_APP_MARKER = 0xEC;
	private static final byte[] XMP = ascii("http://ns.adobe.com/xap/1.0/\0");
	private static final byte[] XMP_EXTENSION =
			ascii("http://ns.adobe.com/xmp/extension/\0");
	private static final byte[] MPF = ascii("MPF\0");
	private static final byte[] ISO_GAIN_MAP =
			ascii("urn:iso:std:iso:ts:21496:-1\0");
	private static final byte[] HDR_GAIN_MAP = ascii("HDRGM\0");
	private static final byte[] HDR_GAIN_MAP_14 = ascii("HDR_GAIN_MAP\0");

	private static volatile PixelDecoder decoder = (image, maxSide) -> null;

	private ReceivedImageGate() {
	}

	public static void useDecoder(PixelDecoder pixelDecoder) {
		decoder = pixelDecoder;
	}

	public static void releaseDecoder() {
		decoder.release();
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static boolean isPng(byte[] d) {
		return d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P'
				&& d[2] == 'N' && d[3] == 'G' && d[4] == 0x0D && d[5] == 0x0A
				&& d[6] == 0x1A && d[7] == 0x0A;
	}

	private static boolean isJpeg(byte[] d) {
		return d.length >= 12 && (d[0] & 0xFF) == 0xFF
				&& (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
	}

	private static boolean isGif(byte[] d) {
		return d.length >= 13 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F'
				&& d[3] == '8' && (d[4] == '7' || d[4] == '9') && d[5] == 'a';
	}

	private static boolean isWebp(byte[] d) {
		return d.length >= 16 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F'
				&& d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B'
				&& d[11] == 'P';
	}

	public static boolean isAcceptedFormat(@Nullable byte[] d) {
		if (d == null || d.length < 12) return false;
		if (isJpeg(d)) {
			int[] found = new int[3];
			int layout = jpegLayout(d, found);
			int frame = layout & 0xFF;
			return layout != -1 && (frame == SOF_BASELINE
					|| frame == SOF_EXTENDED || frame == SOF_PROGRESSIVE)
					&& jpegScans(d, found[2]) <= MAX_JPEG_SCANS;
		}
		if (isPng(d)) {
			return d.length >= 29 && d[12] == 'I' && d[13] == 'H'
					&& d[14] == 'D' && d[15] == 'R' && d[28] == 0;
		}
		if (isGif(d)) {
			int frames = gifFrames(d);
			return frames >= 1 && frames <= MAX_GIF_FRAMES;
		}
		if (isWebp(d)) {
			if (d[12] != 'V' || d[13] != 'P' || d[14] != '8'
					|| (d[15] != ' ' && d[15] != 'L' && d[15] != 'X')) {
				return false;
			}
			if (d[15] == 'X' && (d.length < 21 || (d[20] & 0x02) != 0)) {
				return false;
			}
			return u32le(d, 4) == d.length - 8L;
		}
		return false;
	}

	public static String mimeType(byte[] d) {
		if (isPng(d)) return "image/png";
		if (isGif(d)) return "image/gif";
		if (isWebp(d)) return "image/webp";
		return "image/jpeg";
	}

	public static long dimensions(byte[] d) {
		long width = -1;
		long height = -1;
		if (isJpeg(d)) {
			int[] size = new int[2];
			if (jpegLayout(d, size) == -1) return -1;
			width = size[0];
			height = size[1];
		} else if (isPng(d) && d.length >= 24) {
			width = u32be(d, 16);
			height = u32be(d, 20);
		} else if (isGif(d)) {
			width = u16le(d, 6);
			height = u16le(d, 8);
		} else if (isWebp(d)) {
			if (d[15] == ' ' && d.length >= 30 && (d[23] & 0xFF) == 0x9D
					&& d[24] == 0x01 && d[25] == 0x2A) {
				width = u16le(d, 26) & 0x3FFF;
				height = u16le(d, 28) & 0x3FFF;
			} else if (d[15] == 'L' && d.length >= 25 && d[20] == 0x2F) {
				long bits = u32le(d, 21);
				width = (bits & 0x3FFF) + 1;
				height = ((bits >> 14) & 0x3FFF) + 1;
			} else if (d[15] == 'X' && d.length >= 30) {
				width = (u16le(d, 24) | (d[26] & 0xFFL) << 16) + 1;
				height = (u16le(d, 27) | (d[29] & 0xFFL) << 16) + 1;
			}
		}
		if (width < 1 || height < 1 || width > SafeImageDecoder.MAX_DIMENSION
				|| height > SafeImageDecoder.MAX_DIMENSION) {
			return -1;
		}
		return width << 32 | height;
	}

	private static long u16le(byte[] d, int at) {
		return (d[at] & 0xFFL) | (d[at + 1] & 0xFFL) << 8;
	}

	private static long u32le(byte[] d, int at) {
		return u16le(d, at) | u16le(d, at + 2) << 16;
	}

	private static long u32be(byte[] d, int at) {
		return (d[at] & 0xFFL) << 24 | (d[at + 1] & 0xFFL) << 16
				| (d[at + 2] & 0xFFL) << 8 | (d[at + 3] & 0xFFL);
	}

	public static boolean isSequentialJpeg(byte[] d) {
		if (!isJpeg(d)) return false;
		int layout = jpegLayout(d, null);
		return layout == SOF_BASELINE || layout == SOF_EXTENDED;
	}

	static int jpegLayout(byte[] d, @Nullable int[] size) {
		int i = 2;
		int frame = -1;
		int components = 0;
		while (i + 3 < d.length) {
			if ((d[i] & 0xFF) != 0xFF) return -1;
			int marker = d[i + 1] & 0xFF;
			if (marker == 0xFF) {
				i++;
				continue;
			}
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				i += 2;
				continue;
			}
			int length = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
			if (length < 2 || i + 2 + length > d.length) return -1;
			boolean frameHeader = marker >= 0xC0 && marker <= 0xCF
					&& marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
			boolean skippable = (marker >= 0xE0 && marker <= 0xEF)
					|| marker == 0xFE || marker == 0xDB || marker == 0xC4
					|| marker == 0xCC || marker == 0xDD;
			if (frameHeader) {
				if (frame != -1 || length < 8) return -1;
				frame = marker;
				components = d[i + 9] & 0xFF;
				if (size != null) {
					size[1] = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
					size[0] = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
				}
			} else if (marker == 0xDA) {
				if (frame == -1 || length < 3) return -1;
				if (size != null && size.length > 2) size[2] = i;
				int inScan = d[i + 4] & 0xFF;
				return inScan < components ? frame | SEVERAL_SCANS : frame;
			} else if (!skippable) {
				return -1;
			}
			i += 2 + length;
		}
		return -1;
	}

	static int jpegScans(byte[] d, int firstScan) {
		int scans = 0;
		int i = firstScan;
		while (true) {
			while (i + 1 < d.length && ((d[i] & 0xFF) != 0xFF
					|| d[i + 1] == 0 || (d[i + 1] & 0xFF) == 0xFF)) {
				i++;
			}
			if (i + 1 >= d.length) return scans;
			int marker = d[i + 1] & 0xFF;
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				i += 2;
				continue;
			}
			if (marker == 0xD9) return scans;
			boolean skippable = (marker >= 0xE0 && marker <= 0xEF)
					|| marker == 0xFE || marker == 0xDB || marker == 0xC4
					|| marker == 0xCC || marker == 0xDD || marker == 0xDC;
			if (marker != 0xDA && !skippable) return MAX_JPEG_SCANS + 1;
			if (i + 3 >= d.length) return scans;
			int length = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
			if (length < 2) return MAX_JPEG_SCANS + 1;
			if (marker == 0xDA && ++scans > MAX_JPEG_SCANS) return scans;
			if (i + 2 + length > d.length) return scans;
			i += 2 + length;
		}
	}

	static byte[] withoutGainMap(byte[] d) {
		if (!isJpeg(d)) return d;
		byte[] out = null;
		for (int i = 2; i + 4 < d.length; i++) {
			if ((d[i] & 0xFF) != 0xFF) continue;
			int marker = d[i + 1] & 0xFF;
			boolean points = (marker == 0xE1 && (startsWith(d, i + 4, XMP)
					|| startsWith(d, i + 4, XMP_EXTENSION)))
					|| (marker == 0xE2 && (startsWith(d, i + 4, MPF)
					|| startsWith(d, i + 4, ISO_GAIN_MAP)))
					|| (marker == 0xEF && (startsWith(d, i + 4, HDR_GAIN_MAP)
					|| startsWith(d, i + 4, HDR_GAIN_MAP_14)));
			if (!points) continue;
			if (out == null) out = d.clone();
			out[i + 1] = (byte) UNUSED_APP_MARKER;
		}
		return out == null ? d : out;
	}

	private static boolean startsWith(byte[] d, int at, byte[] prefix) {
		if (at + prefix.length > d.length) return false;
		for (int i = 0; i < prefix.length; i++) {
			if (d[at + i] != prefix[i]) return false;
		}
		return true;
	}

	public static int gifFrames(byte[] d) {
		if (d.length < 13) return -1;
		int screenWidth = (d[6] & 0xFF) | (d[7] & 0xFF) << 8;
		int screenHeight = (d[8] & 0xFF) | (d[9] & 0xFF) << 8;
		int i = 13;
		int screen = d[10] & 0xFF;
		if ((screen & 0x80) != 0) i += 3 * (1 << ((screen & 7) + 1));
		int frames = 0;
		while (i < d.length) {
			int block = d[i++] & 0xFF;
			if (block == 0x3B) return frames;
			if (block == 0x21) {
				if (i + 1 >= d.length) return -1;
				int label = d[i] & 0xFF;
				int first = d[i + 1] & 0xFF;
				if (label == 0xF9) {
					if (first != 4 || i + 6 >= d.length || d[i + 6] != 0) {
						return -1;
					}
					i += 7;
				} else if (label == 0xFF && first != 11) {
					return -1;
				} else {
					i = afterSubBlocks(d, i + 1);
				}
			} else if (block == 0x2C) {
				if (i + 9 > d.length) return -1;
				int left = (d[i] & 0xFF) | (d[i + 1] & 0xFF) << 8;
				int top = (d[i + 2] & 0xFF) | (d[i + 3] & 0xFF) << 8;
				int width = (d[i + 4] & 0xFF) | (d[i + 5] & 0xFF) << 8;
				int height = (d[i + 6] & 0xFF) | (d[i + 7] & 0xFF) << 8;
				if (width < 1 || height < 1 || left + width > screenWidth
						|| top + height > screenHeight) {
					return -1;
				}
				int local = d[i + 8] & 0xFF;
				i += 9;
				if ((local & 0x80) != 0) i += 3 * (1 << ((local & 7) + 1));
				i = afterSubBlocks(d, i + 1);
				if (++frames > MAX_GIF_FRAMES) return frames;
			} else {
				return -1;
			}
			if (i < 0) return -1;
		}
		return -1;
	}

	private static int afterSubBlocks(byte[] d, int i) {
		while (i >= 0 && i < d.length) {
			int size = d[i++] & 0xFF;
			if (size == 0) return i;
			i += size;
		}
		return -1;
	}

	public static byte[] checked(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(
				Math.max(8192, Math.min(in.available(), MAX_BYTES)));
		byte[] buf = new byte[8192];
		int read;
		while ((read = in.read(buf)) != -1) {
			if (out.size() + read > MAX_BYTES) {
				throw new IOException("image too large");
			}
			out.write(buf, 0, read);
		}
		return checked(out.toByteArray());
	}

	public static byte[] checked(@Nullable byte[] data) throws IOException {
		return checked(data, MAX_BYTES);
	}

	public static byte[] checked(@Nullable byte[] data, int maxBytes)
			throws IOException {
		try {
			return checkedWithinMemory(data, maxBytes);
		} catch (OutOfMemoryError e) {
			throw new IOException("image too large");
		}
	}

	private static byte[] checkedWithinMemory(@Nullable byte[] data,
			int maxBytes) throws IOException {
		if (data == null || data.length > maxBytes) {
			throw new IOException("image too large");
		}
		if (!isAcceptedFormat(data)) {
			throw new IOException("image format not accepted");
		}
		if (isPng(data)) {
			try {
				data = com.professor.zerion.android.attachment.ImageScrubber
						.png(data);
			} catch (IOException e) {
				throw new IOException("image format not accepted", e);
			}
		}
		long budget = MAX_PIXELS;
		if (isJpeg(data)) {
			int layout = jpegLayout(data, null);
			if ((layout & 0xFF) == SOF_PROGRESSIVE
					|| (layout & SEVERAL_SCANS) != 0) {
				budget = MAX_PROGRESSIVE_PIXELS;
			}
		}
		long size = dimensions(data);
		if (size == -1 || (size >> 32) * (size & 0xFFFFFFFFL) > budget) {
			throw new IOException("image dimensions not accepted");
		}
		return withoutGainMap(data);
	}

	public static byte[] checkedIfImage(byte[] data) throws IOException {
		if (SafeImageDecoder.hasAllowedMagic(data)
				|| MediaMagic.isStillImage(data)) {
			return checked(data, Integer.MAX_VALUE);
		}
		return data;
	}

	@Nullable
	public static Bitmap decode(@Nullable byte[] data, int maxSide) {
		byte[] image;
		try {
			image = checked(data, SafeImageDecoder.MAX_BYTES);
		} catch (IOException e) {
			return null;
		}
		return decodeChecked(image, maxSide);
	}

	@Nullable
	public static Bitmap decodeAlone(@Nullable byte[] data, int maxSide) {
		byte[] image;
		try {
			image = checked(data, SafeImageDecoder.MAX_BYTES);
		} catch (IOException e) {
			return null;
		}
		try {
			return decoder.decodeAlone(image, maxSide);
		} catch (RuntimeException | OutOfMemoryError e) {
			return null;
		}
	}

	@Nullable
	public static Bitmap decodeChecked(byte[] checkedImage, int maxSide) {
		try {
			return decoder.decode(checkedImage, maxSide);
		} catch (RuntimeException | OutOfMemoryError e) {
			return null;
		}
	}

	@Nullable
	public static byte[] orNull(@Nullable byte[] data) {
		try {
			return checked(data);
		} catch (IOException e) {
			return null;
		}
	}
}
