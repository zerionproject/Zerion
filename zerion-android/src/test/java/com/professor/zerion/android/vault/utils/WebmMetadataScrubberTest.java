package com.professor.zerion.android.vault.utils;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WebmMetadataScrubberTest {

	private static final long TIMECODE_SCALE = 0x2AD7B1L;
	private static final long DURATION = 0x4489L;
	private static final long DOC_TYPE = 0x4282L;
	private static final long TRACK_NUMBER = 0xD7L;
	private static final long TIMECODE = 0xE7L;
	private static final long SIMPLE_BLOCK = 0xA3L;
	private static final long SEEK = 0x4DBBL;
	private static final long VIDEO = 0xE0L;
	private static final long PIXEL_WIDTH = 0xB0L;

	private final List<File> temp = new ArrayList<>();

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] id(long id) {
		int length = id > 0xFFFFFFL ? 4 : id > 0xFFFFL ? 3 : id > 0xFFL ? 2 : 1;
		byte[] b = new byte[length];
		for (int i = 0; i < length; i++) {
			b[i] = (byte) (id >> (8 * (length - 1 - i)));
		}
		return b;
	}

	private static byte[] size(long size, int length) {
		byte[] b = new byte[length];
		for (int i = 0; i < length; i++) {
			b[i] = (byte) (size >> (8 * (length - 1 - i)));
		}
		b[0] |= (byte) (0x80 >> (length - 1));
		return b;
	}

	static byte[] el(long id, byte[]... body) {
		byte[] b = concat(body);
		return concat(id(id), size(b.length, b.length < 127 ? 1 : 8), b);
	}

	private static byte[] unknown(long id, byte[]... body) {
		return concat(id(id), new byte[] {0x01, -1, -1, -1, -1, -1, -1, -1},
				concat(body));
	}

	private static byte[] u32le(long v) {
		return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16),
				(byte) (v >> 24)};
	}

	private static byte[] vorbisComment(byte[] vendor, byte[]... comments) {
		ByteArrayOutputStream list = new ByteArrayOutputStream();
		for (byte[] c : comments) {
			list.write(u32le(c.length), 0, 4);
			list.write(c, 0, c.length);
		}
		return concat(new byte[] {3, 'v', 'o', 'r', 'b', 'i', 's'},
				u32le(vendor.length), vendor, u32le(comments.length),
				list.toByteArray(), new byte[] {1});
	}

	private static byte[] xiphLaced(byte[] id, byte[] comment, byte[] setup) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(2);
		for (byte[] p : new byte[][] {id, comment}) {
			int left = p.length;
			while (left >= 255) {
				out.write(255);
				left -= 255;
			}
			out.write(left);
		}
		byte[] all = concat(id, comment, setup);
		out.write(all, 0, all.length);
		return out.toByteArray();
	}

	private static byte[] blank(byte[] b) {
		return new byte[b.length];
	}

	private static final class Webm {

		byte[] muxingApp = ascii("android");
		byte[] writingApp = ascii("android");
		byte[] title = ascii("ZtPlantedTitle");
		byte[] date = {0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F, 0x60, 0x71};
		byte[] trackName = ascii("ZtPlantedTrackName");
		byte[] vendor = ascii("android");
		byte[] comment = ascii("ARTIST=ZtPlantedArtist");
		byte[] sample = concat(ascii("Android"), new byte[300]);
		byte[] extra = new byte[0];
		boolean unknownSegment;
		boolean unknownCluster;

		Webm blanked() {
			Webm w = new Webm();
			w.muxingApp = blank(muxingApp);
			w.writingApp = blank(writingApp);
			w.title = blank(title);
			w.date = blank(date);
			w.trackName = blank(trackName);
			w.vendor = blank(vendor);
			w.comment = blank(comment);
			w.sample = sample;
			w.extra = extra;
			w.unknownSegment = unknownSegment;
			w.unknownCluster = unknownCluster;
			return w;
		}

		byte[] codecPrivate() {
			byte[] idHeader = concat(new byte[] {1, 'v', 'o', 'r', 'b', 'i',
					's'}, new byte[23]);
			byte[] setup = concat(new byte[] {5, 'v', 'o', 'r', 'b', 'i', 's'},
					new byte[400]);
			return xiphLaced(idHeader, vorbisComment(vendor, comment), setup);
		}

		byte[] bytes() {
			byte[] header = el(WebmMetadataScrubber.EBML,
					el(DOC_TYPE, ascii("webm")));
			byte[] seekHead = el(WebmMetadataScrubber.SEEK_HEAD,
					el(SEEK, new byte[12]));
			byte[] info = el(WebmMetadataScrubber.INFO,
					el(TIMECODE_SCALE, new byte[] {0x0F, 0x42, 0x40}),
					el(DURATION, new byte[8]),
					el(WebmMetadataScrubber.MUXING_APP, muxingApp),
					el(WebmMetadataScrubber.WRITING_APP, writingApp),
					el(WebmMetadataScrubber.TITLE, title),
					el(WebmMetadataScrubber.DATE_UTC, date));
			byte[] video = el(WebmMetadataScrubber.TRACK_ENTRY,
					el(TRACK_NUMBER, new byte[] {1}),
					el(WebmMetadataScrubber.CODEC_ID, ascii("V_VP8")),
					el(WebmMetadataScrubber.NAME, trackName),
					el(VIDEO, el(PIXEL_WIDTH, new byte[] {1, 0x40})));
			byte[] audio = el(WebmMetadataScrubber.TRACK_ENTRY,
					el(TRACK_NUMBER, new byte[] {2}),
					el(WebmMetadataScrubber.CODEC_PRIVATE, codecPrivate()),
					el(WebmMetadataScrubber.CODEC_ID, ascii("A_VORBIS")));
			byte[] tracks = el(WebmMetadataScrubber.TRACKS, video, audio);
			byte[] clusterBody = concat(el(TIMECODE, new byte[] {0}),
					el(SIMPLE_BLOCK, sample));
			byte[] cluster = unknownCluster
					? unknown(WebmMetadataScrubber.CLUSTER, clusterBody)
					: el(WebmMetadataScrubber.CLUSTER, clusterBody);
			byte[] cues = el(WebmMetadataScrubber.CUES, new byte[20]);
			byte[] body = concat(seekHead, el(WebmMetadataScrubber.VOID,
					new byte[30]), info, tracks, cluster, cues, extra);
			byte[] segment = unknownSegment
					? unknown(WebmMetadataScrubber.SEGMENT, body)
					: el(WebmMetadataScrubber.SEGMENT, body);
			return concat(header, segment);
		}
	}

	private File write(byte[] webm) throws IOException {
		File f = File.createTempFile("zt_webm_", ".webm");
		temp.add(f);
		Files.write(f.toPath(), webm);
		return f;
	}

	private byte[] scrubbed(byte[] in) throws IOException {
		File f = write(in);
		WebmMetadataScrubber.scrub(f);
		return Files.readAllBytes(f.toPath());
	}

	private static int indexOf(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	private static void assertNoWriterDetails(byte[] out) {
		for (String s : new String[] {"android", "ZtPlanted"}) {
			assertEquals(s + " is still in the file", -1,
					indexOf(out, ascii(s)));
		}
	}

	private static void assertRefused(String what, byte[] webm, File f)
			throws IOException {
		Files.write(f.toPath(), webm);
		try {
			WebmMetadataScrubber.scrub(f);
			fail(what + " was accepted");
		} catch (IOException expected) {
		}
	}

	@Test
	public void writerTitleDateAndTrackNamesAreBlankedInPlace()
			throws Exception {
		Webm planted = new Webm();
		byte[] in = planted.bytes();
		assertTrue(indexOf(in, ascii("android")) >= 0);
		byte[] out = scrubbed(in);
		assertEquals(in.length, out.length);
		assertArrayEquals(planted.blanked().bytes(), out);
		assertNoWriterDetails(out);
		assertTrue(indexOf(out, ascii("webm")) >= 0);
		assertTrue(indexOf(out, ascii("A_VORBIS")) >= 0);
	}

	@Test
	public void theSamplesThemselvesAreNotTouched() throws Exception {
		Webm planted = new Webm();
		byte[] out = scrubbed(planted.bytes());
		assertTrue("the sample was changed",
				indexOf(out, planted.sample) >= 0);
	}

	@Test
	public void segmentsAndClustersOfUnknownSizeAreFollowed()
			throws Exception {
		Webm planted = new Webm();
		planted.unknownSegment = true;
		planted.unknownCluster = true;
		byte[] out = scrubbed(planted.bytes());
		assertArrayEquals(planted.blanked().bytes(), out);
		planted.unknownSegment = false;
		out = scrubbed(planted.bytes());
		assertArrayEquals(planted.blanked().bytes(), out);
	}

	@Test
	public void aFileWithNothingToBlankIsLeftAsItIs() throws Exception {
		byte[] in = new Webm().blanked().bytes();
		assertArrayEquals(in, scrubbed(in));
	}

	@Test
	public void tagsAttachmentsAndChaptersAreRefused() throws Exception {
		File f = write(new byte[0]);
		long[] refused = {WebmMetadataScrubber.TAGS,
				WebmMetadataScrubber.ATTACHMENTS,
				WebmMetadataScrubber.CHAPTERS};
		for (long id : refused) {
			Webm w = new Webm();
			w.extra = el(id, el(0x7373L, ascii("ZtPlantedTag")));
			assertRefused(Long.toHexString(id), w.bytes(), f);
		}
	}

	@Test
	public void writerDetailsLeftAnywhereElseAreRefused() throws Exception {
		File f = write(new byte[0]);
		Webm inVoid = new Webm();
		inVoid.extra = el(WebmMetadataScrubber.VOID, ascii("ANDROID"));
		assertRefused("a writer name in a void element", inVoid.bytes(), f);
		Webm inTrack = new Webm();
		inTrack.extra = el(WebmMetadataScrubber.TRACKS,
				el(WebmMetadataScrubber.TRACK_ENTRY,
						el(0x22B59CL, ascii("android"))));
		assertRefused("a writer name in another track field",
				inTrack.bytes(), f);
	}

	@Test
	public void filesThatCannotBeFollowedAreRefused() throws Exception {
		File f = write(new byte[0]);
		byte[] good = new Webm().bytes();
		assertRefused("a file cut short",
				Arrays.copyOf(good, good.length - 10), f);
		assertRefused("an MP4 file", concat(new byte[] {0, 0, 0, 16},
				ascii("ftypisom"), new byte[4]), f);
		assertRefused("a file without a segment",
				el(WebmMetadataScrubber.EBML, el(DOC_TYPE, ascii("webm"))), f);
		Webm checksummed = new Webm();
		checksummed.extra = el(WebmMetadataScrubber.INFO,
				el(WebmMetadataScrubber.CRC32, new byte[4]));
		assertRefused("info with a checksum", checksummed.bytes(), f);
		Webm unknownInfo = new Webm();
		unknownInfo.extra = unknown(WebmMetadataScrubber.INFO,
				el(WebmMetadataScrubber.TITLE, ascii("x")));
		assertRefused("info of unknown size", unknownInfo.bytes(), f);
		Webm overrun = new Webm();
		overrun.extra = concat(id(WebmMetadataScrubber.INFO), size(4000, 2),
				new byte[10]);
		assertRefused("an element that runs past its parent",
				overrun.bytes(), f);
		byte[] badId = concat(good, new byte[] {0x00, (byte) 0x81, 0});
		assertRefused("an element id without a marker", badId, f);
	}

	@Test
	public void vorbisHeadersThatCannotBeFollowedAreRefused()
			throws Exception {
		byte[] comment = vorbisComment(ascii("android"), ascii("A=b"));
		byte[] id = new byte[30];
		byte[] setup = new byte[40];
		byte[][] broken = {
				new byte[] {1, 30},
				xiphLaced(id, Arrays.copyOf(comment, comment.length - 1),
						setup),
				xiphLaced(id, concat(new byte[] {4}, comment), setup),
				xiphLaced(id, concat(Arrays.copyOf(comment, 7),
						u32le(1000), new byte[8]), setup),
				Arrays.copyOf(xiphLaced(id, comment, setup), 40)};
		for (int i = 0; i < broken.length; i++) {
			try {
				WebmMetadataScrubber.blankVorbisComment(broken[i].clone());
				fail("broken vorbis headers " + i + " were accepted");
			} catch (IOException expected) {
			}
		}
		byte[] ok = xiphLaced(id, comment, setup);
		WebmMetadataScrubber.blankVorbisComment(ok);
		assertEquals(-1, indexOf(ok, ascii("android")));
		assertEquals(-1, indexOf(ok, ascii("A=b")));
		assertFalse(Arrays.equals(xiphLaced(id, comment, setup), ok));
	}
}
