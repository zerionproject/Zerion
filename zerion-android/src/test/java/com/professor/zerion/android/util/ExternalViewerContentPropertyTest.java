package com.professor.zerion.android.util;

import com.professor.zerion.android.attachment.SharedMediaSanitizerProbe;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ExternalViewerContentPropertyTest {

	private static final int ROUNDS = 20_000;

	private static final String[] FETCHING = {
			"#EXTM3U\nhttp://198.51.100.7/a.mp3\n",
			"#EXTM3U\r\n#EXTINF:1,a\r\nhttps://example.invalid/a\r\n",
			"[playlist]\nFile1=http://198.51.100.7/a\nNumberOfEntries=1\n",
			"http://198.51.100.7/a.mp3\n",
			"rtsp://198.51.100.7/stream\n",
			"#c\n#d\nhttp://198.51.100.7/a.mp3\n",
			"<html><img src=http://198.51.100.7/x></html>",
			"<?xml version=\"1.0\"?><smil><body><audio src=\"http://"
					+ "198.51.100.7/a\"/></body></smil>",
			"<asx version=\"3.0\"><entry><ref href=\"mms://198.51.100.7/x\"/>"
					+ "</entry></asx>",
			"MIME-Version: 1.0\r\nContent-Type: text/html\r\n\r\n<html>"};

	private static final String[] DECLARED = {"audio/mpeg", "audio/mp4",
			"audio/aac", "audio/ogg", "audio/flac", "audio/x-wav",
			"video/mp4", "video/webm", "image/jpeg",
			"application/octet-stream", "application/pdf", null};

	private static void write(ByteArrayOutputStream out, byte[] b) {
		out.write(b, 0, b.length);
	}

	private static byte[] syncSafe(int n) {
		return new byte[] {(byte) ((n >> 21) & 0x7F),
				(byte) ((n >> 14) & 0x7F), (byte) ((n >> 7) & 0x7F),
				(byte) (n & 0x7F)};
	}

	private static byte[] le32(long v) {
		return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16),
				(byte) (v >> 24)};
	}

	private static final int[] TAGS = {0, 1};

	private static void prefixElement(Random r, ByteArrayOutputStream out,
			boolean any) {
		prefixElement(r, out, r.nextInt(7));
	}

	private static void prefixElement(Random r, ByteArrayOutputStream out,
			int kind) {
		switch (kind) {
			case 0: {
				int size = r.nextInt(64);
				int[] flags = {0, 0x10, 0x20, 0x40, 0x80, r.nextInt(256)};
				int flag = flags[r.nextInt(flags.length)];
				write(out, "ID3".getBytes(StandardCharsets.ISO_8859_1));
				out.write(2 + r.nextInt(4));
				out.write(r.nextBoolean() ? 0 : r.nextInt(256));
				out.write(flag);
				if (r.nextInt(5) == 0) {
					byte[] raw = new byte[4];
					r.nextBytes(raw);
					write(out, raw);
				} else {
					write(out, syncSafe(size));
				}
				write(out, new byte[size]);
				if ((flag & 0x10) != 0) {
					write(out, "3DI".getBytes(StandardCharsets.ISO_8859_1));
					write(out, new byte[] {4, 0, (byte) flag});
					write(out, syncSafe(size));
				}
				break;
			}
			case 1: {
				int items = r.nextInt(3);
				int size = 32 + r.nextInt(64);
				write(out, "APETAGEX".getBytes(StandardCharsets.ISO_8859_1));
				write(out, le32(r.nextBoolean() ? 2000 : 1000));
				write(out, le32(size));
				write(out, le32(items));
				write(out, le32(r.nextBoolean() ? 0xA0000000L : 0x80000000L));
				write(out, new byte[8]);
				write(out, new byte[size]);
				break;
			}
			case 2:
				write(out, new byte[r.nextInt(128)]);
				break;
			case 3: {
				byte[] junk = new byte[1 + r.nextInt(3)];
				r.nextBytes(junk);
				write(out, junk);
				break;
			}
			case 4:
				write(out, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
				break;
			case 5:
				write(out, "TAG".getBytes(StandardCharsets.ISO_8859_1));
				write(out, new byte[125]);
				break;
			default:
				break;
		}
	}

	private static byte[] encoded(Random r, String text) {
		switch (r.nextInt(5)) {
			case 0:
				return text.getBytes(StandardCharsets.UTF_16LE);
			case 1:
				return text.getBytes(StandardCharsets.UTF_16BE);
			case 2:
				return text.getBytes(StandardCharsets.UTF_16);
			default:
				return text.getBytes(StandardCharsets.UTF_8);
		}
	}

	@Test
	public void fetchingContentIsNeverHandedOverAsMediaOrOpaqueBytes() {
		Random r = new Random(0x5EED);
		for (int round = 0; round < ROUNDS; round++) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			String declared = DECLARED[r.nextInt(DECLARED.length)];
			boolean media = declared != null && (declared.startsWith("audio/")
					|| declared.startsWith("video/")
					|| declared.startsWith("image/"));
			if (media) {
				int elements = r.nextInt(5);
				for (int i = 0; i < elements; i++) {
					prefixElement(r, out, false);
				}
			} else {
				int tags = r.nextInt(4);
				for (int i = 0; i < tags; i++) {
					prefixElement(r, out, TAGS[r.nextInt(TAGS.length)]);
				}
				if (r.nextBoolean()) prefixElement(r, out, 2);
				if (r.nextBoolean()) prefixElement(r, out, 4);
			}
			String payload = FETCHING[r.nextInt(FETCHING.length)];
			write(out, encoded(r, payload));
			byte[] content = out.toByteArray();
			String handed = ExternalViewerTypes.typeForContent(declared,
					content);
			if (handed != null && !handed.equals("text/plain")) {
				fail("round " + round + ": content ending in "
						+ payload.trim().split("\n")[0] + " declared as "
						+ declared + " was handed over as " + handed
						+ "; first bytes " + hexHead(content));
			}
		}
	}

	private static String hexHead(byte[] content) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(content.length, 48); i++) {
			sb.append(String.format("%02x", content[i] & 0xFF));
		}
		return sb.toString();
	}

	private static byte[] mpegFrames(int count) {
		byte[] frame = new byte[417];
		frame[0] = (byte) 0xFF;
		frame[1] = (byte) 0xFB;
		frame[2] = (byte) 0x90;
		frame[3] = (byte) 0x64;
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < count; i++) write(out, frame);
		return out.toByteArray();
	}

	@Test
	public void realTaggedAudioIsStillHandedOverAsAudio() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, "ID3".getBytes(StandardCharsets.ISO_8859_1));
		write(out, new byte[] {3, 0, 0});
		write(out, syncSafe(32));
		write(out, new byte[32]);
		write(out, mpegFrames(4));
		assertTrue(String.valueOf(ExternalViewerTypes.typeForContent(
				"audio/mpeg", out.toByteArray())).startsWith("audio/"));
	}

	private static final byte[][] HIDDEN = {
			{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0, 16,
					'E', 'x', 'i', 'f', 0, 0},
			{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'},
			{'I', 'I', 42, 0, 8, 0, 0, 0},
			{0, 0, 0, 24, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'},
			{'R', 'I', 'F', 'F', 16, 0, 0, 0, 'W', 'E', 'B', 'P'}};

	@Test
	public void aContainerShortlyAfterTheTagsIsAlwaysSeen() {
		Random r = new Random(0xC0FFEE);
		for (int round = 0; round < 2_000; round++) {
			byte[] container = HIDDEN[r.nextInt(HIDDEN.length)];
			byte[] junk = new byte[r.nextInt(64)];
			for (int i = 0; i < junk.length; i++) {
				junk[i] = (byte) (r.nextInt(0x70) + 0x10);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			write(out, junk);
			write(out, container);
			write(out, new byte[256]);
			if (!SharedMediaSanitizerProbe.hidesAnotherContainer(
					out.toByteArray())) {
				fail("round " + round + ": a container after " + junk.length
						+ " bytes was not seen");
			}
		}
	}
}
