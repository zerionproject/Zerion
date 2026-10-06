package com.professor.zerion.android.attachment;

import android.content.Context;
import android.net.Uri;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.attachment.OggTestFiles.EMPTY_OPUS_TAGS;
import static com.professor.zerion.android.attachment.OggTestFiles.EMPTY_VORBIS_COMMENT;
import static com.professor.zerion.android.attachment.OggTestFiles.FIRST;
import static com.professor.zerion.android.attachment.OggTestFiles.LAST;
import static com.professor.zerion.android.attachment.OggTestFiles.ascii;
import static com.professor.zerion.android.attachment.OggTestFiles.audioPackets;
import static com.professor.zerion.android.attachment.OggTestFiles.concat;
import static com.professor.zerion.android.attachment.OggTestFiles.list;
import static com.professor.zerion.android.attachment.OggTestFiles.opus;
import static com.professor.zerion.android.attachment.OggTestFiles.opusHead;
import static com.professor.zerion.android.attachment.OggTestFiles.opusTags;
import static com.professor.zerion.android.attachment.OggTestFiles.packets;
import static com.professor.zerion.android.attachment.OggTestFiles.page;
import static com.professor.zerion.android.attachment.OggTestFiles.pages;
import static com.professor.zerion.android.attachment.OggTestFiles.readPages;
import static com.professor.zerion.android.attachment.OggTestFiles.vorbis;
import static com.professor.zerion.android.attachment.OggTestFiles.vorbisComment;
import static com.professor.zerion.android.attachment.OggTestFiles.vorbisId;
import static com.professor.zerion.android.attachment.OggTestFiles.vorbisSetup;
import static com.professor.zerion.android.attachment.OggTestFiles.zeros;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v1;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v23;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class OggCommentStripTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MAX = 16L * 1024 * 1024;
	private static final String MARKER = "ZtPlantedGps52.3702N4.8952E";
	private static final byte[] MARKER_BYTES =
			MARKER.getBytes(StandardCharsets.US_ASCII);

	private Context ctx;
	private SharedMediaSanitizer sanitizer;
	private final List<File> temp = new ArrayList<>();

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
		sanitizer = new SharedMediaSanitizer(ctx);
	}

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private Uri stage(byte[] data) throws IOException {
		File f = File.createTempFile("zt_ogg_", ".ogg", ctx.getCacheDir());
		temp.add(f);
		Files.write(f.toPath(), data);
		return Uri.fromFile(f);
	}

	private static boolean contains(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (hay[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private byte[] shared(byte[] ogg, String declared) throws IOException {
		SharedMediaSanitizer.Cleaned c =
				sanitizer.sanitize(stage(ogg), declared, ogg, MAX);
		assertEquals(declared, c.getMimeType());
		return c.getData();
	}

	private static void assertWellFormed(byte[] ogg) {
		List<OggTestFiles.Page> pages = readPages(ogg);
		java.util.Map<Long, Long> next = new java.util.HashMap<>();
		for (OggTestFiles.Page p : pages) {
			assertTrue("page checksum", p.checksumValid);
			Long expected = next.get(p.serial);
			if ((p.flags & FIRST) != 0) expected = 0L;
			assertEquals("page number of stream " + p.serial,
					expected, (Long) p.seq);
			next.put(p.serial, p.seq + 1);
		}
	}

	private static long[] granulesOf(byte[] ogg, long serial) {
		List<Long> g = new ArrayList<>();
		for (OggTestFiles.Page p : readPages(ogg)) {
			if (p.serial == serial && p.granule != 0 && p.granule != -1) {
				g.add(p.granule);
			}
		}
		long[] out = new long[g.size()];
		for (int i = 0; i < out.length; i++) out[i] = g.get(i);
		return out;
	}

	private static void assertPackets(List<byte[]> expected, byte[] ogg,
			long serial) {
		List<byte[]> actual = packets(readPages(ogg), serial);
		assertEquals("packets of stream " + serial, expected.size(),
				actual.size());
		for (int i = 0; i < expected.size(); i++) {
			assertArrayEquals("packet " + i + " of stream " + serial,
					expected.get(i), actual.get(i));
		}
	}

	private static List<byte[]> join(List<byte[]> a, List<byte[]> b) {
		List<byte[]> l = new ArrayList<>(a);
		l.addAll(b);
		return l;
	}

	@Test
	public void anOpusCommentHeaderIsReplacedByAnEmptyOne() throws Exception {
		List<byte[]> audio = audioPackets(40, 1);
		byte[] ogg = opus(0x1234, opusTags("LOCATION=" + MARKER,
				"ENCODER=a phone recorder"), audio, 255, 30);
		for (String declared : new String[] {"audio/ogg", "audio/opus",
				"application/ogg", "application/octet-stream"}) {
			byte[] out = shared(ogg, declared);
			assertFalse(declared + ": the comments were sent",
					contains(out, MARKER_BYTES));
			assertWellFormed(out);
			assertPackets(join(list(opusHead(), EMPTY_OPUS_TAGS), audio), out,
					0x1234);
			assertArrayEquals(granulesOf(ogg, 0x1234), granulesOf(out, 0x1234));
			assertArrayEquals(opus(0x1234, EMPTY_OPUS_TAGS, audio, 255, 30),
					out);
		}
	}

	@Test
	public void aVorbisCommentHeaderIsReplacedAndTheSetupKept()
			throws Exception {
		List<byte[]> audio = audioPackets(30, 2);
		byte[] setup = vorbisSetup(3900);
		byte[] ogg = vorbis(77, vorbisComment("ARTIST=" + MARKER,
				"DATE=2026-09-30"), setup, audio, 255, 25);
		byte[] out = shared(ogg, "audio/ogg");
		assertFalse("the Vorbis comments were sent",
				contains(out, MARKER_BYTES));
		assertWellFormed(out);
		assertPackets(join(list(vorbisId(), EMPTY_VORBIS_COMMENT, setup),
				audio), out, 77);
		assertArrayEquals(granulesOf(ogg, 77), granulesOf(out, 77));
		assertArrayEquals(vorbis(77, EMPTY_VORBIS_COMMENT, setup, audio, 255,
				25), out);
	}

	@Test
	public void commentsSpanningManyPagesAreReplacedAndLaterPagesRenumbered()
			throws Exception {
		StringBuilder art = new StringBuilder("METADATA_BLOCK_PICTURE=");
		for (int i = 0; i < 9000; i++) art.append("QUJDRA").append(i % 10);
		List<byte[]> audio = audioPackets(60, 3);
		byte[] setup = vorbisSetup(5000);
		for (int headerSegments : new int[] {255, 17}) {
			byte[] ogg = vorbis(9, vorbisComment(art.toString(),
					"LOCATION=" + MARKER), setup, audio, headerSegments, 20);
			assertTrue(readPages(ogg).size() > readPages(vorbis(9,
					EMPTY_VORBIS_COMMENT, setup, audio, headerSegments, 20))
					.size());
			byte[] out = shared(ogg, "audio/ogg");
			assertFalse("Vorbis comments over several pages were sent",
					contains(out, MARKER_BYTES));
			assertWellFormed(out);
			assertPackets(join(list(vorbisId(), EMPTY_VORBIS_COMMENT, setup),
					audio), out, 9);
			assertArrayEquals(granulesOf(ogg, 9), granulesOf(out, 9));
			List<OggTestFiles.Page> pages = readPages(out);
			assertTrue((pages.get(pages.size() - 1).flags & LAST) != 0);
		}
		byte[] ogg = opus(5, opusTags(art.toString(), art.toString(),
				"LOCATION=" + MARKER), audio, 255, 20);
		assertTrue(readPages(ogg).size() > readPages(opus(5, EMPTY_OPUS_TAGS,
				audio, 255, 20)).size());
		byte[] out = shared(ogg, "audio/ogg");
		assertFalse("Opus comments over several pages were sent",
				contains(out, MARKER_BYTES));
		assertWellFormed(out);
		assertArrayEquals(opus(5, EMPTY_OPUS_TAGS, audio, 255, 20), out);
	}

	@Test
	public void chainedStreamsAndStreamsInOtherCodecsAreHandled()
			throws Exception {
		List<byte[]> a = audioPackets(20, 4);
		List<byte[]> b = audioPackets(25, 5);
		byte[] chained = concat(
				opus(100, opusTags("TITLE=first " + MARKER), a, 255, 10),
				vorbis(200, vorbisComment("TITLE=second " + MARKER),
						vorbisSetup(700), b, 255, 10));
		byte[] out = shared(chained, "audio/ogg");
		assertFalse("the comments of chained streams were sent",
				contains(out, MARKER_BYTES));
		assertWellFormed(out);
		assertArrayEquals(concat(opus(100, EMPTY_OPUS_TAGS, a, 255, 10),
				vorbis(200, EMPTY_VORBIS_COMMENT, vorbisSetup(700), b, 255,
						10)), out);

		ByteArrayOutputStream grouped = new ByteArrayOutputStream();
		byte[] speexHead = concat(ascii("Speex   1.2"), new byte[69]);
		byte[] speexComment = concat(OggTestFiles.commentList("speex",
				"NOTE=" + MARKER));
		long opusSeq = pages(grouped, list(opusHead()), 1, 0, FIRST,
				zeros(1), false, 255);
		long speexSeq = pages(grouped, list(speexHead), 2, 0, FIRST,
				zeros(1), false, 255);
		opusSeq = pages(grouped, list(opusTags("LOCATION=" + MARKER)), 1,
				opusSeq, 0, zeros(1), false, 255);
		speexSeq = pages(grouped, list(speexComment), 2, speexSeq, 0,
				zeros(1), false, 255);
		pages(grouped, a, 1, opusSeq, 0, OggTestFiles.granules(a.size(), 960),
				true, 10);
		pages(grouped, b, 2, speexSeq, 0, OggTestFiles.granules(b.size(),
				160), true, 10);
		byte[] muxed = grouped.toByteArray();
		byte[] mixed = shared(muxed, "audio/ogg");
		assertFalse("the comments of a grouped stream were sent",
				contains(mixed, MARKER_BYTES));
		assertWellFormed(mixed);
		assertPackets(join(list(opusHead(), EMPTY_OPUS_TAGS), a), mixed, 1);
		assertPackets(join(list(speexHead, new byte[8]), b), mixed, 2);
	}

	@Test
	public void tagsAroundAnOggFileAreDropped() throws Exception {
		List<byte[]> audio = audioPackets(10, 6);
		byte[] clean = opus(3, EMPTY_OPUS_TAGS, audio, 255, 10);
		byte[] ogg = opus(3, opusTags("LOCATION=" + MARKER), audio, 255, 10);
		assertArrayEquals(clean, shared(concat(ogg,
				id3v1(ascii("trailing"))), "audio/ogg"));
		assertArrayEquals(clean, shared(concat(id3v23(ascii("leading")), ogg,
				id3v1(ascii("trailing"))), "audio/mpeg"));
	}

	@Test
	public void anOggFileThatCannotBeReadPageByPageIsRefused()
			throws Exception {
		List<byte[]> audio = audioPackets(10, 7);
		byte[] ogg = opus(3, opusTags("LOCATION=" + MARKER), audio, 255, 10);
		byte[] badChecksum = ogg.clone();
		badChecksum[ogg.length - 1] ^= 1;
		byte[] cut = java.util.Arrays.copyOf(ogg, ogg.length - 5);
		int firstPage = 27 + 1 + opusHead().length;
		byte[] junkBetween = concat(java.util.Arrays.copyOf(ogg, firstPage),
				ascii("junk"), java.util.Arrays.copyOfRange(ogg, firstPage,
						ogg.length));
		ByteArrayOutputStream shared = new ByteArrayOutputStream();
		long seq = pages(shared, list(opusHead()), 8, 0, FIRST, zeros(1),
				false, 255);
		pages(shared, join(list(opusTags("LOCATION=" + MARKER)), audio), 8,
				seq, 0, OggTestFiles.granules(audio.size() + 1, 960), true,
				255);
		byte[] headersOnly = concat(page(FIRST, 0, 4, 0, new byte[] {19},
				opusHead()));
		byte[] strayPage = concat(ogg, page(0, 0, 999, 0, new byte[] {4},
				ascii("data")));
		Object[][] cases = {{"a bad checksum", badChecksum},
				{"a page cut short", cut},
				{"bytes between pages", junkBetween},
				{"audio on the comment page", shared.toByteArray()},
				{"headers that never end", headersOnly},
				{"a page of a stream that never started", strayPage}};
		for (Object[] c : cases) {
			try {
				byte[] out = shared((byte[]) c[1], "audio/ogg");
				fail(c[0] + " was sent" + (contains(out, MARKER_BYTES)
						? " with its comments" : ""));
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void oggAttachedForAContactIsCleanedToo() throws Exception {
		List<byte[]> audio = audioPackets(15, 8);
		byte[] ogg = opus(21, opusTags("LOCATION=" + MARKER), audio, 255, 10);
		byte[] clean = opus(21, EMPTY_OPUS_TAGS, audio, 255, 10);
		assertArrayEquals(clean, AttachmentCreationTask.cleanAudio(ogg));
		assertArrayEquals(clean, AttachmentCreationTask.cleanAudio(
				concat(id3v23(ascii("leading")), ogg)));
	}
}
