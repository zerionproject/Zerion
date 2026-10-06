package com.professor.zerion.android.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ExternalViewerTagBypassTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static final String[] ACTIVE = {
			"#EXTM3U\nhttp://198.51.100.7/a.mp3\n",
			"[playlist]\nFile1=http://198.51.100.7/a.mp3\nNumberOfEntries=1\n",
			"http://198.51.100.7/a.mp3\n",
			"#EXT-X-VERSION:3\nhttps://198.51.100.7/seg.ts\n",
			"<html><img src=http://198.51.100.7/x></html>",
			"<asx version=\"3.0\"><entry><ref href=\"mms://198.51.100.7/x\"/>"
					+ "</entry></asx>",
			"#c\nhttp://198.51.100.7/a.mp3\n"};

	private static final String[] DECLARED = {"audio/mpeg", "audio/ogg",
			"audio/flac", "audio/mp4", "application/octet-stream",
			"application/pdf", "video/mp4", null};

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] le32(long v) {
		return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16),
				(byte) (v >> 24)};
	}

	private static byte[] syncSafe(int n) {
		return new byte[] {(byte) ((n >> 21) & 0x7F), (byte) ((n >> 14) & 0x7F),
				(byte) ((n >> 7) & 0x7F), (byte) (n & 0x7F)};
	}

	private static byte[] id3(int major, int flags, byte[] body) {
		byte[] tag = concat(ascii("ID3"), new byte[] {(byte) major, 0,
				(byte) flags}, syncSafe(body.length), body);
		if ((flags & 0x10) == 0) return tag;
		return concat(tag, ascii("3DI"), new byte[] {(byte) major, 0,
				(byte) flags}, syncSafe(body.length));
	}

	private static byte[] titleFrame(String title) {
		byte[] t = ascii(title);
		return concat(ascii("TIT2"), new byte[] {0, 0, 0,
				(byte) (t.length + 1), 0, 0, 0}, t);
	}

	private static byte[] apeHeaderTag(byte[] item) {
		long size = 32 + item.length;
		byte[] header = concat(ascii("APETAGEX"), le32(2000), le32(size),
				le32(1), le32(0xA0000000L), new byte[8]);
		byte[] footer = concat(ascii("APETAGEX"), le32(2000), le32(size),
				le32(1), le32(0x80000000L), new byte[8]);
		return concat(header, item, footer);
	}

	private static byte[] mpegFrames(int count) {
		byte[] frames = new byte[417 * count];
		for (int f = 0; f < count; f++) {
			int o = f * 417;
			frames[o] = (byte) 0xFF;
			frames[o + 1] = (byte) 0xFB;
			frames[o + 2] = (byte) 0x90;
			frames[o + 3] = 0x64;
			for (int i = 4; i < 417; i++) frames[o + i] = (byte) (i + f);
		}
		return frames;
	}

	private static byte[] box(String type, byte[] body) {
		int n = body.length + 8;
		return concat(new byte[] {(byte) (n >> 24), (byte) (n >> 16),
				(byte) (n >> 8), (byte) n}, ascii(type), body);
	}

	private static byte[][] leadingTags() {
		byte[] v23 = id3(3, 0, titleFrame("tagged"));
		return new byte[][] {
				id3(3, 0, new byte[0]),
				v23,
				id3(4, 0x10, titleFrame("with a footer")),
				id3(2, 0, new byte[40]),
				concat(v23, id3(4, 0, titleFrame("second"))),
				apeHeaderTag(ascii("Artist\0Someone")),
				concat(v23, apeHeaderTag(ascii("Year\0" + "2026")))};
	}

	private static String type(String declared, byte[] content) {
		return ExternalViewerTypes.typeForContent(declared, content);
	}

	@Test
	public void aPlaylistOrPageBehindLeadingTagsIsNeverHandedOver() {
		byte[][] tags = leadingTags();
		for (int t = 0; t < tags.length; t++) {
			for (String p : ACTIVE) {
				byte[] content = concat(tags[t], ascii(p));
				for (String declared : DECLARED) {
					assertNull("tag layout " + t + " then " + p.trim()
							+ " declared as " + declared, type(declared,
							content));
				}
			}
		}
	}

	@Test
	public void anAudioTypeNeedsSoundAfterTheTags() {
		byte[] tag = id3(3, 0, titleFrame("tagged"));
		byte[] jpeg = concat(new byte[] {(byte) 0xFF, (byte) 0xD8,
				(byte) 0xFF, (byte) 0xE1}, new byte[200]);
		assertNull("text after a tag", type("audio/mpeg", concat(tag,
				ascii("just a few words and nothing that plays"))));
		assertNull("a tag alone", type("audio/mpeg", tag));
		assertNull("a picture after a tag", type("audio/mpeg",
				concat(tag, jpeg)));
		assertNull("zeros after an APE tag", type("audio/ogg",
				concat(apeHeaderTag(ascii("Title\0x")), new byte[300])));
	}

	@Test
	public void aTagThatPlayersMightMeasureDifferentlyIsRefused() {
		byte[] playlist = ascii("#EXTM3U\nhttp://198.51.100.7/a.mp3\n");
		byte[] undefinedFooter = concat(ascii("ID3"),
				new byte[] {3, 0, 0x10, 0, 0, 0, 0},
				new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64, 0,
						0, 0, 0, 0, 0}, playlist);
		byte[] notSyncSafe = concat(ascii("ID3"),
				new byte[] {3, 0, 0, 0, 0, 0, (byte) 0x8A}, new byte[10],
				mpegFrames(2));
		byte[] unknownVersion = concat(ascii("ID3"),
				new byte[] {5, 0, 0, 0, 0, 0, 0}, playlist);
		byte[] pastTheEnd = concat(ascii("ID3"),
				new byte[] {3, 0, 0, 0, 0, 0x7F, 0x7F}, mpegFrames(1));
		byte[] apeFooterFirst = concat(ascii("APETAGEX"), le32(2000),
				le32(32), le32(0), le32(0), new byte[8], playlist);
		ByteArrayOutputStream many = new ByteArrayOutputStream();
		for (int i = 0; i < 40; i++) {
			byte[] t = id3(3, 0, new byte[0]);
			many.write(t, 0, t.length);
		}
		byte[] manyTags = concat(many.toByteArray(), mpegFrames(2));
		Object[][] cases = {{"an undefined footer flag", undefinedFooter},
				{"a size that is not sync safe", notSyncSafe},
				{"an unknown tag version", unknownVersion},
				{"a tag longer than the file", pastTheEnd},
				{"an APE footer where a header belongs", apeFooterFirst},
				{"more leading tags than any file has", manyTags}};
		for (Object[] c : cases) {
			for (String declared : new String[] {"audio/mpeg",
					"application/octet-stream"}) {
				assertNull(c[0] + " declared as " + declared,
						type(declared, (byte[]) c[1]));
			}
		}
	}

	@Test
	public void aReferenceMovieBehindATagIsRefused() {
		byte[] reference = concat(box("ftyp", ascii("qt  \0\0\2\0qt  ")),
				box("moov", box("rmra", box("rmda", concat(new byte[4],
						ascii("url "), ascii("http://198.51.100.7/x.mov"))))));
		byte[] tagged = concat(id3(3, 0, titleFrame("tagged")), reference);
		assertNull(type("audio/mp4", tagged));
		assertNull(type("application/octet-stream", tagged));
		assertNull(type("application/mp4", tagged));
	}

	@Test
	public void theStagedFileIsCheckedBehindATagLargerThanTheHead()
			throws Exception {
		byte[] cover = concat(ascii("APIC"), new byte[] {0, 4, (byte) 0x93,
				(byte) 0xE0, 0, 0}, new byte[300_000]);
		byte[] tag = id3(3, 0, cover);
		File playlist = folder.newFile("tagged-playlist.mp3");
		Files.write(playlist.toPath(), concat(tag,
				ascii("#EXTM3U\nhttp://198.51.100.7/a.mp3\n")));
		assertNull(ExternalViewerTypes.typeForFile("audio/mpeg", playlist));
		assertNull(ExternalViewerTypes.typeForFile("application/octet-stream",
				playlist));
		File song = folder.newFile("tagged-song.mp3");
		Files.write(song.toPath(), concat(tag, mpegFrames(3)));
		assertEquals("audio/mpeg",
				ExternalViewerTypes.typeForFile("audio/mpeg", song));
	}

	@Test
	public void taggedAudioIsHandedOver() {
		byte[][] tags = leadingTags();
		for (int t = 0; t < tags.length; t++) {
			assertEquals("tag layout " + t, "audio/mpeg", type("audio/mpeg",
					concat(tags[t], mpegFrames(3))));
		}
		byte[] tag = id3(3, 0, titleFrame("tagged"));
		assertEquals("zero padding after the tag", "audio/mpeg",
				type("audio/mpeg", concat(tag, new byte[600], mpegFrames(3))));
		assertNull("zero padding before a playlist", type("audio/mpeg",
				concat(tag, new byte[600], ascii("#EXTM3U\nhttp://198.51.100.7/"
						+ "a.mp3\n"))));
		assertEquals("audio/aac", type("audio/aac", concat(tag,
				new byte[] {(byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80,
						0x02, 0x1F, (byte) 0xFC}, new byte[100])));
		assertEquals("audio/flac", type("audio/flac", concat(tag,
				ascii("fLaC"), new byte[] {0, 0, 0, 34}, new byte[60])));
		assertEquals("audio/ogg", type("audio/ogg", concat(tag,
				ascii("OggS"), new byte[60])));
		assertEquals(ExternalViewerTypes.OPAQUE,
				type("application/octet-stream", concat(tag, mpegFrames(2))));
	}
}
