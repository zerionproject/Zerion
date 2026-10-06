package com.professor.zerion.android.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExternalViewerAdversarialTest {

	private static final String OCTET = ExternalViewerTypes.OPAQUE;
	private static final String TEXT = ExternalViewerTypes.TEXT;

	private static final byte[] MP3_FRAME =
			{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00, 1, 2, 3, 4, 5, 6};

	@Nullable
	private static String type(String declared, byte[] content) {
		return ExternalViewerTypes.typeForContent(declared, content);
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] repeat(byte b, int n) {
		byte[] out = new byte[n];
		Arrays.fill(out, b);
		return out;
	}

	private static byte[] be32(long v) {
		return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8),
				(byte) v};
	}

	private static byte[] box(String type, byte[]... body) {
		byte[] b = concat(body);
		return concat(be32(b.length + 8), ascii(type), b);
	}

	private static byte[] largeBox(String type, byte[]... body) {
		byte[] b = concat(body);
		long size = b.length + 16;
		return concat(be32(1), ascii(type), be32(size >>> 32),
				be32(size & 0xFFFFFFFFL), b);
	}

	private static byte[] fullBox(String type, int flags, byte[]... body) {
		return box(type, concat(new byte[] {0, (byte) (flags >> 16),
				(byte) (flags >> 8), (byte) flags}, concat(body)));
	}

	private static byte[] ftyp(String brand) {
		return box("ftyp", ascii(brand), be32(0), ascii(brand));
	}

	private static byte[] id3Header(int size, int flags, int major) {
		return concat(ascii("ID3"), new byte[] {(byte) major, 0, (byte) flags,
				(byte) ((size >> 21) & 0x7F), (byte) ((size >> 14) & 0x7F),
				(byte) ((size >> 7) & 0x7F), (byte) (size & 0x7F)});
	}

	private static byte[] id3(int size, int flags, int major) {
		return concat(id3Header(size, flags, major), new byte[size]);
	}

	private static byte[] movie(byte[]... moovChildren) {
		return concat(ftyp("isom"), box("moov", moovChildren),
				box("mdat", new byte[64]));
	}

	private static byte[] dataReference(int entryFlags) {
		byte[] url = entryFlags == 1 ? fullBox("url ", 1)
				: fullBox("url ", entryFlags, ascii("http://x/a.mp4\0"));
		byte[] dref = fullBox("dref", 0, be32(1), url);
		return box("trak", box("mdia", box("minf", box("dinf", dref))));
	}

	@Test
	public void polyglotsAreNeverHandedOverAsMarkupOrPlaylists() {
		byte[] html = ascii("<html><script>fetch('http://x')</script>");
		byte[] gifHtml = concat(ascii("GIF89a"), new byte[] {1, 0, 1, 0, 0, 0,
				0}, html);
		byte[] jpegHtml = concat(new byte[] {(byte) 0xFF, (byte) 0xD8,
				(byte) 0xFF, (byte) 0xE0}, html);
		byte[] pdfHtml = concat(ascii("%PDF-1.7\n"), html);
		for (String declared : new String[] {"text/html", "image/svg+xml",
				"application/xhtml+xml", "audio/x-mpegurl"}) {
			assertEquals(declared, TEXT, type(declared, gifHtml));
			assertEquals(declared, TEXT, type(declared, jpegHtml));
			assertEquals(declared, TEXT, type(declared, pdfHtml));
		}
		assertEquals("image/gif", type("image/gif", gifHtml));
		assertEquals("image/jpeg", type("image/jpeg", jpegHtml));
		assertNull("markup under an image type is refused",
				type("image/gif", html));
		assertNull("markup under a document type is refused",
				type("application/pdf", html));
		assertNull("markup as opaque bytes is refused",
				type(OCTET, html));
		String pdf = type("application/pdf", pdfHtml);
		assertTrue("a PDF polyglot keeps the document type the user is"
				+ " warned about", pdf == null
				|| pdf.equals("application/pdf"));
	}

	@Test
	public void markupBehindPaddingLargerThanTheOldHeadIsRefused() {
		byte[] html = ascii("<html><body><script src='http://x/'>");
		for (int pad : new int[] {1000, 1024, 1100, 1445, 2048, 4000}) {
			byte[] spaces = concat(repeat((byte) ' ', pad), html);
			byte[] newlines = concat(repeat((byte) '\n', pad), html);
			byte[] nuls = concat(repeat((byte) 0, pad), html);
			assertNull("spaces " + pad, type(OCTET, spaces));
			assertNull("newlines " + pad, type(OCTET, newlines));
			assertNull("nuls " + pad, type(OCTET, nuls));
			assertNull("spaces under pdf " + pad,
					type("application/pdf", spaces));
			byte[] commentsThenUrl = concat(repeat((byte) '#', pad),
					ascii("\nhttp://x/a.mp3\n"));
			assertNull("comment padding " + pad, type(OCTET, commentsThenUrl));
		}
	}

	@Test
	public void encodedAndTaggedMarkupIsRefused() {
		String page = "<html><a href='http://x'>";
		byte[] utf8Bom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB,
				(byte) 0xBF}, ascii(page));
		byte[] utf16Le = concat(new byte[] {(byte) 0xFF, (byte) 0xFE},
				page.getBytes(StandardCharsets.UTF_16LE));
		byte[] utf16Be = concat(new byte[] {(byte) 0xFE, (byte) 0xFF},
				page.getBytes(StandardCharsets.UTF_16BE));
		byte[] utf16NoBom = page.getBytes(StandardCharsets.UTF_16LE);
		byte[] utf16BeNoBom = page.getBytes(StandardCharsets.UTF_16BE);
		byte[] behindId3 = concat(id3(64, 0, 3), ascii(page));
		byte[] behindApe = concat(apeTag(32), ascii(page));
		byte[] behindNulsAndBom = concat(new byte[8], utf8Bom);
		for (byte[] c : new byte[][] {utf8Bom, utf16Le, utf16Be, utf16NoBom,
				utf16BeNoBom, behindId3, behindApe, behindNulsAndBom}) {
			for (String declared : new String[] {OCTET, "application/pdf",
					"audio/mpeg", "image/png", "video/mp4",
					"application/zip"}) {
				assertNull(declared + " " + Arrays.toString(
						Arrays.copyOf(c, 12)), type(declared, c));
			}
		}
	}

	private static byte[] apeTag(int size) {
		byte[] h = new byte[32];
		System.arraycopy(ascii("APETAGEX"), 0, h, 0, 8);
		h[8] = (byte) 0xD0; h[9] = 0x07;
		h[12] = (byte) size;
		h[23] = (byte) 0xA0;
		return concat(h, new byte[size]);
	}

	@Test
	public void playlistsAndDescriptionsAreRefusedUnderEveryType() {
		String[] lists = {
				"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttp://x/a.m3u8\n",
				"﻿#EXTM3U\r\nhttp://x/a.ts\r\n",
				"http://x/a.mp3\n",
				"# a comment\n\n# another\nhttp://x/a.mp3\n",
				"[playlist]\nFile1=http://x/a.mp3\n",
				"<asx version=\"3.0\"><entry><ref href=\"http://x\"/>",
				"v=0\r\no=- 1 1 IN IP4 1.2.3.4\r\n",
				"rtsptext\r\nrtsp://x/stream\r\n",
				"d8:announce20:http://tracker/announce4:info",
				"From: <Saved by Blink>\r\nSnapshot-Content-Location: http://x\r\n"
						+ "Subject: p\r\nMIME-Version: 1.0\r\n",
				"<?xml version=\"1.0\"?><playlist><track><location>http://x",
				"<smil><body><video src=\"http://x/a.mp4\"/></body></smil>",
				"<!-- --><svg xmlns=\"http://www.w3.org/2000/svg\">",
				"\n\n\t  <svg onload=\"fetch('http://x')\">",
				"mime-version: 1.0\ncontent-type: multipart/related\n",
				"snapshot-content-location: http://x/\nsubject: s\n"};
		for (String list : lists) {
			byte[] c = list.getBytes(StandardCharsets.UTF_8);
			for (String declared : new String[] {OCTET, "audio/mpeg",
					"audio/ogg", "video/mp4", "image/png", "application/pdf",
					"application/zip", "font/ttf"}) {
				assertNull(declared + ": " + list.substring(0,
						Math.min(20, list.length())), type(declared, c));
			}
			assertEquals(TEXT, type("audio/x-mpegurl", c));
		}
	}

	@Test
	public void aMediaTypeMustMatchItsBytesEvenWhenTheBytesAreAnotherMedium() {
		byte[] ebml = concat(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3},
				new byte[32]);
		byte[] ogg = concat(ascii("OggS"), new byte[32]);
		byte[] asf = concat(new byte[] {0x30, 0x26, (byte) 0xB2, 0x75,
				(byte) 0x8E, 0x66, (byte) 0xCF, 0x11}, new byte[32]);
		byte[] riffAvi = concat(ascii("RIFF"), be32(100), ascii("AVI "),
				new byte[32]);
		byte[] png = concat(new byte[] {(byte) 0x89}, ascii("PNG\r\n"),
				new byte[] {0x1A, 0x0A}, new byte[32]);
		assertEquals("video/mp4", type("video/mp4", ebml));
		assertEquals("audio/mpeg", type("audio/mpeg", ogg));
		assertEquals("image/png", type("image/png", concat(ascii("GIF89a"),
				new byte[32])));
		assertNull("ASF under a video type", type("video/x-ms-wmv", asf));
		assertNull("AVI under a video type", type("video/avi", riffAvi));
		assertNull("Ogg under a video type", type("video/ogg", ogg));
		assertNull("an image under an audio type", type("audio/mpeg", png));
		assertNull("an audio stream under an image type",
				type("image/jpeg", MP3_FRAME));
		assertNull("text under an audio type",
				type("audio/mpeg", ascii("just some words here")));
		assertEquals("application/zip", type("application/zip", png));
	}

	@Test
	public void tagBoundariesAreMeasuredLikeAPlayerOrRefused() {
		byte[] oneTag = concat(id3(100, 0, 4), MP3_FRAME);
		assertEquals("audio/mpeg", type("audio/mpeg", oneTag));
		byte[] footerV24 = concat(id3(100, 0x10, 4), new byte[10], MP3_FRAME);
		assertEquals("audio/mpeg", type("audio/mpeg", footerV24));
		byte[] footerV23 = concat(id3(100, 0x10, 3), new byte[10], MP3_FRAME);
		assertNull("a v2.3 footer flag is undefined", type("audio/mpeg",
				footerV23));
		byte[] oversized = concat(id3Header(5000, 0, 4), MP3_FRAME);
		assertNull("a tag that runs past the end", type("audio/mpeg",
				oversized));
		byte[] sixteen = MP3_FRAME;
		for (int i = 0; i < 16; i++) sixteen = concat(id3(2, 0, 4), sixteen);
		assertEquals("audio/mpeg", type("audio/mpeg", sixteen));
		byte[] seventeen = concat(id3(2, 0, 4), sixteen);
		assertNull("too many tags to follow", type("audio/mpeg", seventeen));
		byte[] badVersion = concat(id3(2, 0, 5), MP3_FRAME);
		assertNull(type("audio/mpeg", badVersion));
		byte[] syncsafeViolation = concat(ascii("ID3"), new byte[] {4, 0, 0,
				(byte) 0x80, 0, 0, 2}, new byte[2], MP3_FRAME);
		assertNull(type("audio/mpeg", syncsafeViolation));
		byte[] paddedFrame = concat(id3(2, 0, 4), new byte[200], MP3_FRAME);
		assertEquals("audio/mpeg", type("audio/mpeg", paddedFrame));
	}

	@Test
	public void isoMediaThatPointsAtOtherFilesIsRefused() {
		byte[] rmra = box("rmra", box("rmda", box("rdrf", ascii("url "),
				be32(10), ascii("http://x/"))));
		assertNull(type("video/mp4", movie(box("mvhd", new byte[100]), rmra)));
		assertNull(type("video/mp4", movie(rmra)));
		assertNull("compressed movie", type("video/mp4",
				movie(box("cmov", new byte[16]))));
		byte[] moovLast = concat(ftyp("isom"), box("mdat", new byte[64]),
				box("moov", rmra));
		assertNull("movie after the data", type("video/mp4", moovLast));
		byte[] largeFtyp = concat(largeBox("ftyp", ascii("isom"), be32(0)),
				box("moov", rmra));
		assertNull("64 bit box sizes are followed", type("video/mp4",
				largeFtyp));
		byte[] fragmented = concat(ftyp("iso5"), box("moof", new byte[16]),
				box("moov", rmra));
		assertNull(type("video/mp4", fragmented));
		byte[] heifWithMovie = concat(ftyp("heic"), box("meta", new byte[16]),
				box("moov", rmra));
		assertNull("a still image container with a reference movie",
				type("image/heic", heifWithMovie));
		byte[] behindTag = concat(id3(50, 0, 4), movie(rmra));
		assertNull(type("audio/mp4", behindTag));
		byte[] truncatedMoov = concat(ftyp("isom"), concat(be32(100000),
				ascii("moov"), rmra));
		assertNull("a movie box larger than the file is still read",
				type("video/mp4", truncatedMoov));
	}

	@Test
	public void externalDataReferencesAreRefused() {
		byte[] selfContained = movie(box("mvhd", new byte[100]),
				dataReference(1));
		assertEquals("video/mp4", type("video/mp4", selfContained));
		byte[] external = movie(box("mvhd", new byte[100]), dataReference(0));
		assertNull("a track whose data lives in another file",
				type("video/mp4", external));
		assertNull(type("audio/mp4", external));
		byte[] urn = movie(box("trak", box("mdia", box("minf", box("dinf",
				fullBox("dref", 0, be32(1), fullBox("urn ", 0,
						ascii("urn:x\0http://x/\0"))))))));
		assertNull("a URN data reference", type("video/mp4", urn));
		byte[] alias = movie(box("trak", box("mdia", box("minf", box("dinf",
				fullBox("dref", 0, be32(1), box("alis", new byte[40])))))));
		assertNull("a QuickTime alias reference", type("video/mp4", alias));
		byte[] secondTrack = movie(box("mvhd", new byte[100]),
				dataReference(1), dataReference(0));
		assertNull("the second track is checked too", type("video/mp4",
				secondTrack));
		byte[] selfContainedWithLocation = movie(box("trak", box("mdia",
				box("minf", box("dinf", fullBox("dref", 0, be32(1),
						fullBox("url ", 1, ascii("http://x/a.mp4\0"))))))));
		assertNull("a self-contained flag with a location is contradictory",
				type("video/mp4", selfContainedWithLocation));
		byte[] wrongCount = movie(box("trak", box("mdia", box("minf",
				box("dinf", fullBox("dref", 0, be32(5), fullBox("url ", 1)))))));
		assertNull("an entry count the box cannot hold", type("video/mp4",
				wrongCount));
	}

	@Test
	public void malformedBoxStructuresFailClosed() {
		byte[] tooManyBoxes = ftyp("isom");
		for (int i = 0; i < 1030; i++) {
			tooManyBoxes = concat(tooManyBoxes, box("free"));
		}
		tooManyBoxes = concat(tooManyBoxes, box("moov", new byte[8]));
		assertNull("more boxes than can be followed", type("video/mp4",
				tooManyBoxes));
		byte[] tinyBox = concat(ftyp("isom"), be32(3), ascii("moov"));
		assertNull("a box smaller than its header", type("video/mp4",
				tinyBox));
		byte[] negativeLarge = concat(ftyp("isom"), be32(1), ascii("moov"),
				be32(0x80000000L), be32(0), new byte[16]);
		assertNull("a negative 64 bit size", type("video/mp4", negativeLarge));
		byte[] manyChildren = ftyp("isom");
		byte[] children = new byte[0];
		for (int i = 0; i < 1030; i++) children = concat(children, box("free"));
		manyChildren = concat(manyChildren, box("moov", children));
		assertNull("more movie children than can be followed",
				type("video/mp4", manyChildren));
	}

	@Test
	public void declaredTypeTricksDoNotReachAnActiveViewer() {
		String[] shownAsText = {"application/x-webarchive",
				"application/x-webarchive-xml", "application/vnd.rn-realmedia",
				"application/vnd.rn-realmedia-vbr", "audio/vnd.rn-realaudio",
				"application/x-m3u8", "application/vnd.apple.mpegurl.audio",
				"text/x-c", "text/vnd.wap.wml", "application/vnd.wap.xhtml+xml",
				"application/mathml+xml", "application/xslt+xml",
				"video/x-ms-wmx", "application/x-ms-asx"};
		for (String t : shownAsText) {
			assertEquals(t, TEXT, ExternalViewerTypes.effectiveType(t));
		}
		String[] opaque = {"image/svg+xml\u0000", "image/svg+xml ",
				"image/svg+xml​", "text/html\n", "text/html\u0085",
				"image/сvg+xml", "image/svg+xml /x", "text/html;;",
				"/text/html", "text/html/", "te xt/html"};
		for (String t : opaque) {
			String e = ExternalViewerTypes.effectiveType(t);
			assertTrue(t + " -> " + e, e.equals(ExternalViewerTypes.OPAQUE)
					|| e.equals(TEXT));
			assertFalse(t, e.contains("html") && !e.equals(TEXT));
		}
		assertEquals(TEXT, ExternalViewerTypes.effectiveType(
				"text/html;;charset=x"));
	}

	@Test
	public void harmlessMediaIsStillHandedOver() {
		byte[] mp4 = movie(box("mvhd", new byte[100]), box("trak",
				box("tkhd", new byte[84]), box("mdia", box("minf",
						box("dinf", fullBox("dref", 0, be32(1),
								fullBox("url ", 1)))))));
		assertEquals("video/mp4", type("video/mp4", mp4));
		assertEquals("audio/mp4", type("audio/mp4", mp4));
		assertEquals("audio/mpeg", type("audio/mpeg", MP3_FRAME));
		assertEquals("audio/ogg", type("audio/ogg", concat(ascii("OggS"),
				new byte[40])));
		assertEquals("audio/flac", type("audio/flac", concat(ascii("fLaC"),
				new byte[40])));
		assertEquals("image/gif", type("image/gif", concat(ascii("GIF89a"),
				new byte[40])));
		assertEquals("image/heic", type("image/heic", concat(ftyp("heic"),
				box("meta", new byte[40]))));
		assertNotNull(type(OCTET, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
		assertEquals("application/pdf", type("application/pdf",
				ascii("%PDF-1.4\n%âã\n1 0 obj")));
	}
}
