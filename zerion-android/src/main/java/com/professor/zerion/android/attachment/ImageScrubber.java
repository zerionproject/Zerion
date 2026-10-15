package com.professor.zerion.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;

@NotNullByDefault
public final class ImageScrubber {

	private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G',
			0x0D, 0x0A, 0x1A, 0x0A};

	private static final int VP8X_KEPT_FLAGS = 0x12;

	private ImageScrubber() {
	}

	public static byte[] scrub(byte[] image) throws IOException {
		if (isJpeg(image)) return jpeg(image);
		if (isPng(image)) return png(image);
		if (isWebp(image)) return webp(image);
		throw new IOException("unsupported image format");
	}

	static boolean isJpeg(byte[] d) {
		return d.length >= 3 && (d[0] & 0xFF) == 0xFF
				&& (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF;
	}

	static boolean isPng(byte[] d) {
		if (d.length < PNG_SIGNATURE.length) return false;
		for (int i = 0; i < PNG_SIGNATURE.length; i++) {
			if (d[i] != PNG_SIGNATURE[i]) return false;
		}
		return true;
	}

	static boolean isWebp(byte[] d) {
		return matches(d, 0, "RIFF") && matches(d, 8, "WEBP");
	}

	public static byte[] jpeg(byte[] in) throws IOException {
		if (!isJpeg(in)) throw new IOException("not a jpeg");
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		out.write(0xFF);
		out.write(0xD8);
		boolean frame = false;
		boolean scan = false;
		int p = 2;
		while (true) {
			if (p >= in.length || (in[p] & 0xFF) != 0xFF) {
				throw new IOException("jpeg marker expected");
			}
			while (p < in.length && (in[p] & 0xFF) == 0xFF) p++;
			if (p >= in.length) throw new IOException("truncated jpeg");
			int marker = in[p++] & 0xFF;
			if (marker == 0xD9) {
				if (!scan) throw new IOException("jpeg without image data");
				out.write(0xFF);
				out.write(0xD9);
				return out.toByteArray();
			}
			if (p + 2 > in.length) throw new IOException("truncated jpeg");
			int length = ((in[p] & 0xFF) << 8) | (in[p + 1] & 0xFF);
			if (length < 2 || length > in.length - p) {
				throw new IOException("bad jpeg segment length");
			}
			int end = p + length;
			if (!isJpegMetadata(marker)) {
				if (isJpegFrame(marker)) {
					if (frame) throw new IOException("second jpeg frame");
					frame = true;
				} else if (marker == 0xDA) {
					if (!frame) throw new IOException("jpeg scan before frame");
					scan = true;
				} else if (!isJpegTable(marker)) {
					throw new IOException("unexpected jpeg marker");
				}
				out.write(0xFF);
				out.write(marker);
				out.write(in, p, length);
			}
			p = end;
			if (marker == 0xDA) {
				int dataEnd = entropyEnd(in, p);
				out.write(in, p, dataEnd - p);
				p = dataEnd;
			}
		}
	}

	private static boolean isJpegMetadata(int marker) {
		return (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE;
	}

	private static boolean isJpegFrame(int marker) {
		return marker >= 0xC0 && marker <= 0xCF && marker != 0xC4
				&& marker != 0xC8 && marker != 0xCC;
	}

	private static boolean isJpegTable(int marker) {
		return marker == 0xC4 || marker == 0xCC || marker == 0xDB
				|| marker == 0xDD;
	}

	private static int entropyEnd(byte[] in, int p) throws IOException {
		while (true) {
			if (p >= in.length) throw new IOException("truncated jpeg scan");
			if ((in[p] & 0xFF) != 0xFF) {
				p++;
				continue;
			}
			int q = p + 1;
			while (q < in.length && (in[q] & 0xFF) == 0xFF) q++;
			if (q >= in.length) throw new IOException("truncated jpeg scan");
			int next = in[q] & 0xFF;
			if (next != 0x00 && (next < 0xD0 || next > 0xD7)) return p;
			p = q + 1;
		}
	}

	public static byte[] png(byte[] in) throws IOException {
		if (!isPng(in)) throw new IOException("not a png");
		ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
		out.write(in, 0, PNG_SIGNATURE.length);
		boolean data = false;
		int p = PNG_SIGNATURE.length;
		while (true) {
			if (in.length - p < 12) throw new IOException("truncated png");
			long length = u32be(in, p);
			if (length > Integer.MAX_VALUE || length > in.length - p - 12) {
				throw new IOException("bad png chunk length");
			}
			String type = pngChunkType(in, p + 4);
			int end = p + 12 + (int) length;
			if ((p == PNG_SIGNATURE.length) != type.equals("IHDR")) {
				throw new IOException("png must start with one header");
			}
			if (isKeptPngChunk(type)) {
				CRC32 crc = new CRC32();
				crc.update(in, p + 4, 4 + (int) length);
				if (crc.getValue() != u32be(in, end - 4)) {
					throw new IOException("bad png chunk checksum");
				}
				out.write(in, p, end - p);
				if (type.equals("IDAT")) data = true;
				if (type.equals("IEND")) {
					if (!data) throw new IOException("png without image data");
					return out.toByteArray();
				}
			} else if (Character.isUpperCase(type.charAt(0))) {
				throw new IOException("unknown critical png chunk");
			}
			p = end;
		}
	}

	private static boolean isKeptPngChunk(String type) {
		return type.equals("IHDR") || type.equals("PLTE")
				|| type.equals("tRNS") || type.equals("IDAT")
				|| type.equals("IEND");
	}

	private static String pngChunkType(byte[] in, int off) throws IOException {
		char[] type = new char[4];
		for (int i = 0; i < 4; i++) {
			char c = (char) (in[off + i] & 0xFF);
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
				throw new IOException("bad png chunk type");
			}
			type[i] = c;
		}
		return new String(type);
	}

	public static byte[] webp(byte[] in) throws IOException {
		if (!isWebp(in)) throw new IOException("not a webp");
		long riff = u32le(in, 4);
		if (riff < 4 || riff > in.length - 8) {
			throw new IOException("bad webp size");
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream(in.length);
		if (webpChunks(in, 12, 8 + (int) riff, body, true) == 0) {
			throw new IOException("webp without image data");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(
				body.size() + 12);
		out.write(in, 0, 4);
		writeU32le(out, 4 + body.size());
		out.write(in, 8, 4);
		body.writeTo(out);
		return out.toByteArray();
	}

	private static int webpChunks(byte[] in, int p, int end,
			ByteArrayOutputStream out, boolean top) throws IOException {
		int images = 0;
		int first = p;
		while (p < end) {
			if (end - p < 8) throw new IOException("truncated webp chunk");
			long size = u32le(in, p + 4);
			long padded = size + (size & 1);
			if (padded > end - p - 8) {
				throw new IOException("bad webp chunk size");
			}
			int data = p + 8;
			int next = data + (int) padded;
			if (matches(in, p, "VP8X")) {
				if (!top || p != first || size != 10) {
					throw new IOException("bad webp extended header");
				}
				out.write(in, p, 8);
				out.write(in[data] & VP8X_KEPT_FLAGS);
				out.write(new byte[3], 0, 3);
				out.write(in, data + 4, 6);
			} else if (matches(in, p, "VP8 ")
					|| matches(in, p, "VP8L")) {
				out.write(in, p, next - p);
				images++;
			} else if (matches(in, p, "ALPH")) {
				out.write(in, p, next - p);
			} else if (matches(in, p, "ANIM")) {
				if (!top) throw new IOException("nested webp animation");
				out.write(in, p, next - p);
			} else if (matches(in, p, "ANMF")) {
				if (!top || size < 16) {
					throw new IOException("bad webp animation frame");
				}
				ByteArrayOutputStream frame = new ByteArrayOutputStream();
				if (webpChunks(in, data + 16, data + (int) size, frame,
						false) == 0) {
					throw new IOException("webp frame without image data");
				}
				out.write(in, p, 4);
				writeU32le(out, 16 + frame.size());
				out.write(in, data, 16);
				frame.writeTo(out);
				images++;
			}
			p = next;
		}
		return images;
	}

	private static boolean matches(byte[] d, int off, String s) {
		if (d.length - off < s.length()) return false;
		for (int i = 0; i < s.length(); i++) {
			if (d[off + i] != (byte) s.charAt(i)) return false;
		}
		return true;
	}

	private static long u32be(byte[] d, int off) {
		return ((long) (d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
				| ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
	}

	private static long u32le(byte[] d, int off) {
		return (d[off] & 0xFFL) | ((d[off + 1] & 0xFFL) << 8)
				| ((d[off + 2] & 0xFFL) << 16) | ((d[off + 3] & 0xFFL) << 24);
	}

	private static void writeU32le(ByteArrayOutputStream out, long v) {
		out.write((int) v);
		out.write((int) (v >> 8));
		out.write((int) (v >> 16));
		out.write((int) (v >> 24));
	}
}
