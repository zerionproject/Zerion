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

public class ExternalViewerContentTest {

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] padded(int... magic) {
		byte[] d = new byte[256];
		for (int i = 0; i < magic.length; i++) d[i] = (byte) magic[i];
		for (int i = magic.length; i < d.length; i++) d[i] = (byte) (i * 13);
		return d;
	}

	private static byte[] box(String type, byte[] body) {
		int n = body.length + 8;
		return concat(new byte[] {(byte) (n >> 24), (byte) (n >> 16),
				(byte) (n >> 8), (byte) n}, ascii(type), body);
	}

	private static final byte[] MP4 = concat(
			box("ftyp", ascii("isom\0\0\2\0isomiso2mp41")),
			box("moov", box("mvhd", new byte[100])),
			box("mdat", new byte[64]));

	private static final byte[] JPEG = padded(0xFF, 0xD8, 0xFF, 0xE0);
	private static final byte[] MP3 = padded(0xFF, 0xFB, 0x90, 0x64);
	private static final byte[] PDF = ascii("%PDF-1.7\n% received\n");

	private static final String[] PLAYLISTS = {
			"#EXTM3U\n#EXTINF:1,x\nhttp://198.51.100.7/track.mp3\n",
			"﻿#EXTM3U\nhttp://198.51.100.7/a\n",
			"\n\n  #EXT-X-VERSION:3\nhttps://198.51.100.7/seg.ts\n",
			"[playlist]\nFile1=http://198.51.100.7/stream\nNumberOfEntries=1\n",
			"[Reference]\nRef1=http://198.51.100.7/clip.asf\n",
			"<ASX version=\"3.0\"><Entry><Ref href=\"mms://198.51.100.7/x\"/>"
					+ "</Entry></ASX>",
			"<?xml version=\"1.0\"?><playlist><trackList/></playlist>",
			"<smil><body><video src=\"rtsp://198.51.100.7/v\"/></body></smil>",
			"<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"/>",
			"<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
			"v=0\r\no=- 0 0 IN IP4 198.51.100.7\r\ns=x\r\n",
			"rtsp://198.51.100.7/live\n",
			"http://198.51.100.7/track.mp3\n",
			"d8:announce27:http://198.51.100.7/announcee",
			"MIME-Version: 1.0\r\nContent-Type: multipart/related\r\n",
			"RTSPtext\nrtsp://198.51.100.7/q\n"};

	private static String type(String declared, byte[] content) {
		return ExternalViewerTypes.typeForContent(declared, content);
	}

	@Test
	public void aPlaylistIsNeverHandedToAPlayerUnderAMediaType() {
		for (String p : PLAYLISTS) {
			byte[] content = p.getBytes(StandardCharsets.UTF_8);
			for (String declared : new String[] {"video/mp4", "audio/mpeg",
					"audio/ogg", "image/jpeg", "application/octet-stream",
					"application/mp4", "application/ogg", "application/pdf",
					null}) {
				assertNull(declared + " <- " + p.trim(),
						type(declared, content));
			}
		}
	}

	@Test
	public void aUtf16PlaylistIsRecognisedToo() {
		byte[] le = concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
				"#EXTM3U\n".getBytes(StandardCharsets.UTF_16LE));
		byte[] be = concat(new byte[] {(byte) 0xFE, (byte) 0xFF},
				"<asx>".getBytes(StandardCharsets.UTF_16BE));
		assertNull(type("audio/mpeg", le));
		assertNull(type("application/octet-stream", be));
	}

	@Test
	public void aMediaTypeMustMatchItsContent() {
		assertNull("PDF as an image", type("image/png", PDF));
		assertNull("MP3 as a video", type("video/mp4", MP3));
		assertNull("JPEG as audio", type("audio/mpeg", JPEG));
		assertNull("MP4 as an image", type("image/jpeg", MP4));
		assertNull("text as video", type("video/webm", ascii("hello")));
		assertNull("empty video", type("video/mp4", new byte[0]));
	}

	@Test
	public void mediaThatMatchesItsTypeIsHandedOver() {
		assertEquals("image/jpeg", type("image/jpeg", JPEG));
		assertEquals("image/png", type("image/png", padded(0x89, 'P', 'N',
				'G', 0x0D, 0x0A, 0x1A, 0x0A)));
		assertEquals("image/tiff", type("image/tiff",
				padded('I', 'I', 42, 0)));
		assertEquals("video/mp4", type("video/mp4", MP4));
		assertEquals("video/quicktime", type("video/quicktime",
				concat(box("moov", box("mvhd", new byte[20])),
						box("mdat", new byte[8]))));
		assertEquals("video/webm", type("video/webm",
				padded(0x1A, 0x45, 0xDF, 0xA3)));
		assertEquals("audio/mpeg", type("audio/mpeg", MP3));
		assertEquals("audio/mpeg", type("audio/mpeg", concat(
				new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 10},
				new byte[10], MP3)));
		assertEquals("audio/aac", type("audio/aac",
				padded(0xFF, 0xF1, 0x50, 0x80)));
		assertEquals("audio/ogg", type("audio/ogg", padded('O', 'g', 'g',
				'S')));
		assertEquals("audio/flac", type("audio/flac", padded('f', 'L', 'a',
				'C')));
		assertEquals("audio/wav", type("audio/wav", padded('R', 'I', 'F',
				'F', 0, 1, 0, 0, 'W', 'A', 'V', 'E')));
		assertEquals("audio/mp4", type("audio/mp4", MP4));
		assertEquals("audio/amr", type("audio/amr", ascii("#!AMR\n\0\0")));
	}

	@Test
	public void documentsAndTextAreHandedOverAsBefore() {
		assertEquals("application/pdf", type("application/pdf", PDF));
		String docx = "application/vnd.openxmlformats-officedocument"
				+ ".wordprocessingml.document";
		assertEquals(docx, type(docx, padded('P', 'K', 3, 4)));
		assertEquals("application/msword", type("application/msword",
				padded(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)));
		assertEquals("application/zip", type("application/zip",
				padded('P', 'K', 3, 4)));
		assertEquals(ExternalViewerTypes.OPAQUE,
				type("application/octet-stream", padded(1, 2, 3)));
		assertEquals(ExternalViewerTypes.TEXT, type("text/plain",
				ascii("#EXTM3U\nhttp://198.51.100.7/\n")));
		assertEquals(ExternalViewerTypes.TEXT, type("audio/x-mpegurl",
				ascii("#EXTM3U\nhttp://198.51.100.7/\n")));
		assertEquals(ExternalViewerTypes.TEXT, type("text/markdown",
				ascii("![x](http://198.51.100.7/p.png)")));
	}

	@Test
	public void aReferenceMovieIsRefused() {
		byte[] rdrf = box("rdrf", concat(new byte[4], ascii("url "),
				new byte[] {0, 0, 0, 30}, ascii("http://198.51.100.7/x.mov")));
		byte[] reference = concat(
				box("ftyp", ascii("qt  \0\0\2\0qt  ")),
				box("moov", box("rmra", box("rmda", rdrf))));
		assertNull(type("video/quicktime", reference));
		assertNull(type("application/mp4", reference));
		assertNull(type("application/octet-stream", reference));
		byte[] compressed = concat(box("ftyp", ascii("qt  \0\0\2\0qt  ")),
				box("moov", box("cmov", new byte[40])));
		assertNull(type("video/mp4", compressed));
		byte[] late = concat(box("ftyp", ascii("isom\0\0\2\0isom")),
				box("mdat", new byte[300]),
				box("moov", concat(box("mvhd", new byte[20]),
						box("rmra", new byte[16]))));
		assertNull("a reference list after the media data",
				type("video/mp4", late));
		byte[] broken = concat(box("ftyp", ascii("isom\0\0\2\0isom")),
				new byte[] {0, 0, 0, 4}, ascii("moov"));
		assertNull("a box structure that cannot be followed",
				type("video/mp4", broken));
	}

	@Test
	public void theStagedFileIsWhatIsChecked() throws Exception {
		File playlist = folder.newFile("att.mp4");
		Files.write(playlist.toPath(), ascii("#EXTM3U\nhttp://198.51.100.7/\n"));
		assertNull(ExternalViewerTypes.typeForFile("video/mp4", playlist));
		File movie = folder.newFile("real.mp4");
		Files.write(movie.toPath(), MP4);
		assertEquals("video/mp4",
				ExternalViewerTypes.typeForFile("video/mp4", movie));
		File reference = folder.newFile("ref.mov");
		Files.write(reference.toPath(), concat(box("ftyp",
				ascii("qt  \0\0\2\0qt  ")), box("mdat", new byte[4000]),
				box("moov", box("rmra", new byte[8]))));
		assertNull(ExternalViewerTypes.typeForFile("video/quicktime",
				reference));
		File missing = new File(folder.getRoot(), "gone.mp4");
		assertNull(ExternalViewerTypes.typeForFile("video/mp4", missing));
	}
}
