package com.professor.zerion.android.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ExternalViewerOctetStreamTest {

	private static final String[] LOOSE = {"application/octet-stream",
			"application/pdf", "application/zip", "audio/mpeg", "video/mp4",
			null};

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] box(String type, byte[] body) {
		int n = body.length + 8;
		return concat(new byte[] {(byte) (n >> 24), (byte) (n >> 16),
				(byte) (n >> 8), (byte) n}, ascii(type), body);
	}

	private static String type(String declared, byte[] content) {
		return ExternalViewerTypes.typeForContent(declared, content);
	}

	private static void assertRefusedEverywhere(String what, byte[] content) {
		for (String declared : LOOSE) {
			assertNull(what + " declared as " + declared,
					type(declared, content));
		}
	}

	@Test
	public void addressesAfterCommentLinesAreRefused() {
		String[] lists = {"#c\nhttp://198.51.100.7/a.mp3\n",
				"# made on my phone\n#\n\n   \nhttps://198.51.100.7/a\n",
				"#x\r\n#y\r\nrtsp://198.51.100.7/live\r\n",
				"#!\nmms://198.51.100.7/stream\n",
				"\n#EXTINF:1,x\nhttp://198.51.100.7/b.mp3\n"};
		for (String l : lists) {
			assertRefusedEverywhere(l.trim(), ascii(l));
		}
	}

	@Test
	public void utf16WithoutAByteOrderMarkIsRefused() {
		String[] texts = {"<html><img src=http://198.51.100.7/x></html>",
				"#EXTM3U\nhttp://198.51.100.7/a.mp3\n",
				"http://198.51.100.7/a.mp3\n",
				"[playlist]\nFile1=http://198.51.100.7/a\n",
				"<svg/>"};
		for (Charset cs : new Charset[] {StandardCharsets.UTF_16BE,
				StandardCharsets.UTF_16LE}) {
			for (String t : texts) {
				assertRefusedEverywhere(cs + " " + t.trim(), t.getBytes(cs));
				assertRefusedEverywhere(cs + " after NUL " + t.trim(),
						concat(new byte[2], t.getBytes(cs)));
			}
		}
	}

	@Test
	public void markupBehindNulBytesIsRefused() {
		assertRefusedEverywhere("one NUL then a page", concat(new byte[1],
				ascii("<html><img src=http://198.51.100.7/x/></html>")));
		assertRefusedEverywhere("NULs then an image", concat(new byte[4],
				ascii("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")));
		assertRefusedEverywhere("NUL and a line then a playlist",
				concat(new byte[] {0, '\n'},
						ascii("#EXTM3U\nhttp://198.51.100.7/a\n")));
	}

	@Test
	public void aMailMessageOrWebArchiveIsRefusedWhateverComesFirst() {
		String body = "MIME-Version: 1.0\r\nContent-Type: multipart/related;"
				+ " boundary=\"b\"\r\n\r\n--b\r\nContent-Type: text/html\r\n"
				+ "Content-Location: http://198.51.100.7/\r\n\r\n"
				+ "<img src=http://198.51.100.7/x>\r\n--b--\r\n";
		String[] firsts = {"Subject: holiday\r\n",
				"Date: Wed, 30 Sep 2026 10:00:00 +0200\r\n",
				"Message-ID: <a@b>\r\n",
				"Snapshot-Content-Location: http://198.51.100.7/\r\n",
				"X-Saved-By: a browser\r\n",
				"Received: from x by y\r\n"};
		for (String first : firsts) {
			assertRefusedEverywhere(first.trim(), ascii(first + body));
		}
		assertRefusedEverywhere("a date header alone", ascii(
				"Date: Wed, 30 Sep 2026 10:00:00 +0200\r\nSubject: x\r\n"));
	}

	@Test
	public void binaryFilesMediaAndDocumentsAreStillHandedOver() {
		String opaque = ExternalViewerTypes.OPAQUE;
		for (int seed = 1; seed <= 300; seed++) {
			byte[] d = new byte[2048];
			new Random(seed).nextBytes(d);
			d[0] = (byte) (seed % 2 == 0 ? 0 : 0x8F);
			assertEquals("random bytes " + seed, opaque,
					type("application/octet-stream", d));
		}
		byte[] wideFtyp = concat(box("ftyp", concat(ascii("isom\0\0\2\0"),
				ascii("isomiso2avc1mp41mp42dashiso6cmfcM4V ms41hvc1"))),
				box("moov", box("mvhd", new byte[100])),
				box("mdat", new byte[64]));
		assertEquals(60, wideFtyp[3]);
		assertEquals("video/mp4", type("video/mp4", wideFtyp));
		assertEquals(opaque, type("application/octet-stream", wideFtyp));
		byte[] google = concat(box("free", concat(
				ascii("IsoMedia File Produced by Google, 5-11-2011"),
				new byte[40])), box("ftyp", ascii("isom\0\0\2\0isom")),
				box("moov", box("mvhd", new byte[100])));
		assertEquals(91, google[3]);
		assertEquals("video/mp4", type("video/mp4", google));
		assertEquals(opaque, type("application/octet-stream", google));
		assertEquals(opaque, type("application/octet-stream",
				"just some notes, nothing more".getBytes(
						StandardCharsets.UTF_16BE)));
		assertEquals(opaque, type("application/octet-stream",
				"just some notes, nothing more".getBytes(
						StandardCharsets.UTF_16LE)));
		assertEquals(opaque, type("application/octet-stream",
				ascii("#!/bin/sh\n# a script\necho hello\n")));
		assertEquals(opaque, type("application/octet-stream",
				ascii("Name: Alice\nPhone: 0123\nNotes: none\n")));
		assertEquals(opaque, type("application/octet-stream",
				concat(new byte[] {0, 1, 0, 0, 0, 0x0F, 0, (byte) 0x80},
						ascii("OS/2 cmap glyf head"), new byte[100])));
		assertEquals(opaque, type("application/octet-stream",
				concat(new byte[] {0, 0, 1, 0, 1, 0, 16, 16, 0, 0, 1, 0,
						32, 0}, new byte[200])));
		assertEquals(opaque, type("application/octet-stream",
				concat(ascii("SQLite format 3\0"), new byte[200])));
		assertEquals("application/gzip", type("application/gzip",
				new byte[] {0x1F, (byte) 0x8B, 8, 0, 0, 0, 0, 0, 0, 3}));
		assertEquals("application/pdf", type("application/pdf",
				ascii("%PDF-1.7\n1 0 obj << >> endobj\n")));
		String docx = "application/vnd.openxmlformats-officedocument"
				+ ".wordprocessingml.document";
		assertEquals(docx, type(docx, concat(ascii("PK\3\4"),
				new byte[100])));
	}
}
