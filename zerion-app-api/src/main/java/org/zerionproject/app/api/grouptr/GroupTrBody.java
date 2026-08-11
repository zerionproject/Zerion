package org.zerionproject.app.api.grouptr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class GroupTrBody {

	public static final byte MAGIC_BYTE = (byte) 0xFF;
	public static final byte VERSION_1 = (byte) 0x01;

	public static final byte TYPE_TEXT = (byte) 0x01;
	public static final byte TYPE_VOICE = (byte) 0x02;
	public static final byte TYPE_IMAGE = (byte) 0x03;
	public static final byte TYPE_VIDEO = (byte) 0x04;

	public static final int MAX_MIME_LENGTH = 64;

	public enum Kind {TEXT, VOICE, IMAGE, VIDEO}

	public static final class Parsed {
		public final Kind kind;
		public final String text;
		public final byte[] payload;
		public final long durationMs;
		public final String mime;

		private Parsed(Kind kind, String text, byte[] payload,
				long durationMs, String mime) {
			this.kind = kind;
			this.text = text;
			this.payload = payload;
			this.durationMs = durationMs;
			this.mime = mime;
		}
	}

	private GroupTrBody() {
	}

	public static byte[] encodeText(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	public static byte[] encodeVoice(byte[] opus, long durationMs) {
		ByteArrayOutputStream out = new ByteArrayOutputStream(opus.length + 7);
		out.write(MAGIC_BYTE);
		out.write(VERSION_1);
		out.write(TYPE_VOICE);
		writeUint32(out, durationMs);
		out.write(opus, 0, opus.length);
		return out.toByteArray();
	}

	public static byte[] encodeImage(byte[] image, String mime) {
		byte[] mimeBytes = mime == null
				? new byte[0]
				: mime.getBytes(StandardCharsets.UTF_8);
		if (mimeBytes.length > MAX_MIME_LENGTH) {
			throw new IllegalArgumentException("mime too long");
		}
		ByteArrayOutputStream out =
				new ByteArrayOutputStream(image.length + 4 + mimeBytes.length);
		out.write(MAGIC_BYTE);
		out.write(VERSION_1);
		out.write(TYPE_IMAGE);
		out.write(mimeBytes.length & 0xff);
		out.write(mimeBytes, 0, mimeBytes.length);
		out.write(image, 0, image.length);
		return out.toByteArray();
	}

	public static byte[] encodeVideo(byte[] video, String mime,
			long durationMs) {
		byte[] mimeBytes = mime == null
				? new byte[0]
				: mime.getBytes(StandardCharsets.UTF_8);
		if (mimeBytes.length > MAX_MIME_LENGTH) {
			throw new IllegalArgumentException("mime too long");
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(
				video.length + 8 + mimeBytes.length);
		out.write(MAGIC_BYTE);
		out.write(VERSION_1);
		out.write(TYPE_VIDEO);
		writeUint32(out, durationMs);
		out.write(mimeBytes.length & 0xff);
		out.write(mimeBytes, 0, mimeBytes.length);
		out.write(video, 0, video.length);
		return out.toByteArray();
	}

	public static Kind kindOf(byte[] body) {
		if (body == null || body.length < 3 || body[0] != MAGIC_BYTE
				|| body[1] != VERSION_1) {
			return Kind.TEXT;
		}
		switch (body[2]) {
			case TYPE_VOICE:
				return Kind.VOICE;
			case TYPE_IMAGE:
				return Kind.IMAGE;
			case TYPE_VIDEO:
				return Kind.VIDEO;
			default:
				return Kind.TEXT;
		}
	}

	public static Parsed parse(byte[] body) {
		if (body == null) return text("");
		if (body.length < 3 || body[0] != MAGIC_BYTE
				|| body[1] != VERSION_1) {
			return text(new String(body, StandardCharsets.UTF_8));
		}
		byte type = body[2];
		if (type == TYPE_TEXT) {
			String t = new String(body, 3, body.length - 3,
					StandardCharsets.UTF_8);
			return text(t);
		}
		if (type == TYPE_VOICE && body.length >= 7) {
			long durationMs = readUint32(body, 3);
			byte[] opus = new byte[body.length - 7];
			System.arraycopy(body, 7, opus, 0, opus.length);
			return new Parsed(Kind.VOICE, "", opus, durationMs, null);
		}
		if (type == TYPE_IMAGE && body.length >= 4) {
			int mimeLen = body[3] & 0xff;
			if (mimeLen > MAX_MIME_LENGTH || 4 + mimeLen > body.length) {
				return text("");
			}
			String mime = new String(body, 4, mimeLen,
					StandardCharsets.UTF_8);
			int imgStart = 4 + mimeLen;
			byte[] image = new byte[body.length - imgStart];
			System.arraycopy(body, imgStart, image, 0, image.length);
			return new Parsed(Kind.IMAGE, "", image, 0L, mime);
		}
		if (type == TYPE_VIDEO && body.length >= 8) {
			long durationMs = readUint32(body, 3);
			int mimeLen = body[7] & 0xff;
			if (mimeLen > MAX_MIME_LENGTH || 8 + mimeLen > body.length) {
				return text("");
			}
			String mime = new String(body, 8, mimeLen,
					StandardCharsets.UTF_8);
			int vidStart = 8 + mimeLen;
			byte[] video = new byte[body.length - vidStart];
			System.arraycopy(body, vidStart, video, 0, video.length);
			return new Parsed(Kind.VIDEO, "", video, durationMs, mime);
		}
		return text(new String(body, StandardCharsets.UTF_8));
	}

	private static Parsed text(String t) {
		return new Parsed(Kind.TEXT, t, null, 0L, null);
	}

	private static void writeUint32(ByteArrayOutputStream out, long v) {
		out.write((int) ((v >>> 24) & 0xff));
		out.write((int) ((v >>> 16) & 0xff));
		out.write((int) ((v >>> 8) & 0xff));
		out.write((int) (v & 0xff));
	}

	private static long readUint32(byte[] b, int offset) {
		return (((long) (b[offset] & 0xff)) << 24)
				| (((long) (b[offset + 1] & 0xff)) << 16)
				| (((long) (b[offset + 2] & 0xff)) << 8)
				| ((long) (b[offset + 3] & 0xff));
	}
}
