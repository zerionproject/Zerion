package com.professor.zerion.android.conversation;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

public final class TestPictures {

	private static final int WIDTH = 8;
	private static final int HEIGHT = 6;

	private TestPictures() {
	}

	public static byte[] png(byte[] comment) {
		return png(comment, WIDTH, HEIGHT);
	}

	public static byte[] png(byte[] comment, int width, int height) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A,
				0x0A}, 0, 8);
		ByteArrayOutputStream header = new ByteArrayOutputStream();
		be32(header, width);
		be32(header, height);
		header.write(new byte[] {8, 2, 0, 0, 0}, 0, 5);
		chunk(out, "IHDR", header.toByteArray());
		ByteArrayOutputStream text = new ByteArrayOutputStream();
		byte[] key = "Comment".getBytes(StandardCharsets.US_ASCII);
		text.write(key, 0, key.length);
		text.write(0);
		text.write(comment, 0, comment.length);
		chunk(out, "tEXt", text.toByteArray());
		byte[] raw = new byte[height * (1 + width * 3)];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = i % (1 + width * 3) == 0 ? 0 : (byte) (i * 7);
		}
		Deflater deflater = new Deflater();
		deflater.setInput(raw);
		deflater.finish();
		byte[] buf = new byte[1024];
		ByteArrayOutputStream packed = new ByteArrayOutputStream();
		while (!deflater.finished()) {
			int n = deflater.deflate(buf);
			packed.write(buf, 0, n);
		}
		deflater.end();
		chunk(out, "IDAT", packed.toByteArray());
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String type,
			byte[] data) {
		byte[] name = type.getBytes(StandardCharsets.US_ASCII);
		be32(out, data.length);
		out.write(name, 0, name.length);
		out.write(data, 0, data.length);
		CRC32 crc = new CRC32();
		crc.update(name);
		crc.update(data);
		be32(out, (int) crc.getValue());
	}

	private static void be32(ByteArrayOutputStream out, int v) {
		out.write(v >>> 24);
		out.write(v >>> 16);
		out.write(v >>> 8);
		out.write(v);
	}
}
