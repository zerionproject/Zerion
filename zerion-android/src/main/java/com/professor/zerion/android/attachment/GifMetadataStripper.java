package com.professor.zerion.android.attachment;

import com.professor.zerion.android.util.SafeImageDecoder;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

@NotNullByDefault
final class GifMetadataStripper {

	private static final int TRAILER = 0x3B;
	private static final int EXTENSION = 0x21;
	private static final int IMAGE = 0x2C;
	private static final int GRAPHIC_CONTROL = 0xF9;
	private static final int PLAIN_TEXT = 0x01;
	private static final int APPLICATION = 0xFF;

	private static final byte[] GIF89A =
			"GIF89a".getBytes(StandardCharsets.US_ASCII);

	private GifMetadataStripper() {
	}

	static boolean isGif(byte[] d) {
		return AudioTagStripper.matches(d, 0, "GIF87a")
				|| AudioTagStripper.matches(d, 0, "GIF89a");
	}

	static byte[] withoutMetadata(byte[] d) throws IOException {
		if (!isGif(d) || d.length < 13) throw new IOException("not a gif");
		int width = u16(d, 6);
		int height = u16(d, 8);
		if (width == 0 || height == 0
				|| width > SafeImageDecoder.MAX_DIMENSION
				|| height > SafeImageDecoder.MAX_DIMENSION) {
			throw new IOException("unsupported gif dimensions");
		}
		int packed = d[10] & 0xFF;
		int pos = 13;
		if ((packed & 0x80) != 0) pos += colourTableLength(packed);
		require(d, pos, 0);
		ByteArrayOutputStream frames = new ByteArrayOutputStream(d.length);
		byte[] control = null;
		Integer loops = null;
		int images = 0;
		while (true) {
			require(d, pos, 1);
			int block = d[pos++] & 0xFF;
			if (block == TRAILER) break;
			if (block == IMAGE) {
				int start = pos - 1;
				require(d, pos, 9);
				int imagePacked = d[pos + 8] & 0xFF;
				pos += 9;
				if ((imagePacked & 0x80) != 0) {
					pos += colourTableLength(imagePacked);
				}
				require(d, pos, 1);
				int codeSize = d[pos++] & 0xFF;
				if (codeSize < 1 || codeSize > 11) {
					throw new IOException("malformed gif image data");
				}
				pos = skipSubBlocks(d, pos);
				if (control != null) frames.write(control, 0, control.length);
				control = null;
				frames.write(d, start, pos - start);
				images++;
			} else if (block == EXTENSION) {
				require(d, pos, 1);
				int label = d[pos++] & 0xFF;
				int start = pos;
				int end = skipSubBlocks(d, pos);
				if (label == GRAPHIC_CONTROL) {
					if (end - start != 6 || (d[start] & 0xFF) != 4) {
						throw new IOException("malformed gif control block");
					}
					control = new byte[] {(byte) EXTENSION,
							(byte) GRAPHIC_CONTROL, 4, d[start + 1],
							d[start + 2], d[start + 3], d[start + 4], 0};
				} else if (label == APPLICATION) {
					Integer l = loopCount(d, start, end);
					if (loops == null) loops = l;
				} else if (label == PLAIN_TEXT) {
					control = null;
				}
				pos = end;
			} else {
				throw new IOException("malformed gif block");
			}
		}
		if (images == 0) throw new IOException("gif without a picture");
		ByteArrayOutputStream out = new ByteArrayOutputStream(d.length);
		out.write(GIF89A, 0, GIF89A.length);
		int screenEnd = 13 + ((packed & 0x80) != 0
				? colourTableLength(packed) : 0);
		out.write(d, 6, screenEnd - 6);
		if (loops != null) {
			out.write(EXTENSION);
			out.write(APPLICATION);
			out.write(11);
			byte[] id = "NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII);
			out.write(id, 0, id.length);
			out.write(3);
			out.write(1);
			out.write(loops & 0xFF);
			out.write((loops >> 8) & 0xFF);
			out.write(0);
		}
		byte[] f = frames.toByteArray();
		out.write(f, 0, f.length);
		out.write(TRAILER);
		return out.toByteArray();
	}

	@Nullable
	private static Integer loopCount(byte[] d, int start, int end) {
		if (end - start < 12 || (d[start] & 0xFF) != 11) return null;
		String id = new String(d, start + 1, 11, StandardCharsets.ISO_8859_1);
		if (!id.equals("NETSCAPE2.0") && !id.equals("ANIMEXTS1.0")) {
			return null;
		}
		int pos = start + 12;
		while (pos < end) {
			int size = d[pos] & 0xFF;
			if (size == 0) return null;
			if (size == 3 && (d[pos + 1] & 0xFF) == 1) {
				return u16(d, pos + 2);
			}
			pos += size + 1;
		}
		return null;
	}

	private static int skipSubBlocks(byte[] d, int pos) throws IOException {
		while (true) {
			require(d, pos, 1);
			int size = d[pos++] & 0xFF;
			if (size == 0) return pos;
			require(d, pos, size);
			pos += size;
		}
	}

	private static int colourTableLength(int packed) {
		return 3 << ((packed & 0x07) + 1);
	}

	private static void require(byte[] d, int pos, int n) throws IOException {
		if (pos < 0 || d.length - pos < n) {
			throw new IOException("gif cut short");
		}
	}

	private static int u16(byte[] d, int off) {
		return (d[off] & 0xFF) | ((d[off + 1] & 0xFF) << 8);
	}
}
