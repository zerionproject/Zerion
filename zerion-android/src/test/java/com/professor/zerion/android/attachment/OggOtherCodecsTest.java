package com.professor.zerion.android.attachment;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.attachment.OggTestFiles.EMPTY_OPUS_TAGS;
import static com.professor.zerion.android.attachment.OggTestFiles.FIRST;
import static com.professor.zerion.android.attachment.OggTestFiles.ascii;
import static com.professor.zerion.android.attachment.OggTestFiles.audioPackets;
import static com.professor.zerion.android.attachment.OggTestFiles.commentList;
import static com.professor.zerion.android.attachment.OggTestFiles.concat;
import static com.professor.zerion.android.attachment.OggTestFiles.granules;
import static com.professor.zerion.android.attachment.OggTestFiles.le32;
import static com.professor.zerion.android.attachment.OggTestFiles.list;
import static com.professor.zerion.android.attachment.OggTestFiles.opus;
import static com.professor.zerion.android.attachment.OggTestFiles.opusTags;
import static com.professor.zerion.android.attachment.OggTestFiles.packets;
import static com.professor.zerion.android.attachment.OggTestFiles.pages;
import static com.professor.zerion.android.attachment.OggTestFiles.readPages;
import static com.professor.zerion.android.attachment.OggTestFiles.zeros;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class OggOtherCodecsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String MARKER = "ZtPlantedGps52.3702N4.8952E";
	private static final byte[] MARKER_BYTES =
			MARKER.getBytes(StandardCharsets.US_ASCII);

	private static final byte[] EMPTY_SPEEX_COMMENT = new byte[8];
	private static final byte[] EMPTY_THEORA_COMMENT = concat(
			new byte[] {(byte) 0x81, 't', 'h', 'e', 'o', 'r', 'a'},
			new byte[8]);
	private static final byte[] EMPTY_FLAC_COMMENT =
			{(byte) 0x84, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0};

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

	private static void assertWellFormed(byte[] ogg) {
		java.util.Map<Long, Long> next = new java.util.HashMap<>();
		for (OggTestFiles.Page p : readPages(ogg)) {
			assertTrue("page checksum", p.checksumValid);
			Long expected = next.get(p.serial);
			if ((p.flags & FIRST) != 0) expected = 0L;
			assertEquals("page number of stream " + p.serial, expected,
					(Long) p.seq);
			next.put(p.serial, p.seq + 1);
		}
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

	private static byte[] stream(long serial, byte[] id, List<byte[]> headers,
			List<byte[]> data, long step) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		long seq = pages(out, list(id), serial, 0, FIRST, zeros(1), false,
				255);
		seq = pages(out, headers, serial, seq, 0, zeros(headers.size()),
				false, 255);
		pages(out, data, serial, seq, 0, granules(data.size(), step), true,
				10);
		return out.toByteArray();
	}

	static byte[] speexHeader(int extraHeaders) {
		byte[] h = new byte[80];
		System.arraycopy(ascii("Speex   1.2.1"), 0, h, 0, 13);
		System.arraycopy(le32(1), 0, h, 28, 4);
		System.arraycopy(le32(80), 0, h, 32, 4);
		System.arraycopy(le32(16000), 0, h, 36, 4);
		System.arraycopy(le32(1), 0, h, 48, 4);
		System.arraycopy(le32(320), 0, h, 56, 4);
		System.arraycopy(le32(1), 0, h, 64, 4);
		System.arraycopy(le32(extraHeaders), 0, h, 68, 4);
		return h;
	}

	static byte[] theoraId() {
		byte[] id = new byte[42];
		byte[] magic = {(byte) 0x80, 't', 'h', 'e', 'o', 'r', 'a', 3, 2, 1};
		System.arraycopy(magic, 0, id, 0, magic.length);
		id[11] = 20;
		id[13] = 15;
		return id;
	}

	static byte[] theoraComment(String... comments) {
		return concat(new byte[] {(byte) 0x81, 't', 'h', 'e', 'o', 'r', 'a'},
				commentList("Xiph.Org libtheora 1.1 20090822", comments));
	}

	static byte[] theoraSetup(int length) {
		byte[] s = new byte[length];
		byte[] magic = {(byte) 0x82, 't', 'h', 'e', 'o', 'r', 'a'};
		System.arraycopy(magic, 0, s, 0, magic.length);
		for (int i = magic.length; i < length; i++) s[i] = (byte) (i * 5);
		return s;
	}

	static byte[] flacId(int count, boolean streamInfoLast) {
		byte[] p = new byte[51];
		byte[] magic = {0x7F, 'F', 'L', 'A', 'C', 1, 0};
		System.arraycopy(magic, 0, p, 0, magic.length);
		p[7] = (byte) (count >> 8);
		p[8] = (byte) count;
		System.arraycopy(ascii("fLaC"), 0, p, 9, 4);
		p[13] = (byte) (streamInfoLast ? 0x80 : 0);
		p[16] = 34;
		for (int i = 17; i < 51; i++) p[i] = (byte) (i * 3);
		return p;
	}

	static byte[] flacBlock(int type, boolean last, byte[] body) {
		return concat(new byte[] {(byte) ((last ? 0x80 : 0) | type),
				(byte) (body.length >> 16), (byte) (body.length >> 8),
				(byte) body.length}, body);
	}

	static List<byte[]> flacFrames(int count) {
		List<byte[]> frames = new ArrayList<>();
		for (byte[] p : audioPackets(count, 9)) {
			p[0] = (byte) 0xFF;
			p[1] = (byte) 0xF8;
			frames.add(p);
		}
		return frames;
	}

	private static byte[] cleaned(byte[] ogg) throws IOException {
		byte[] viaChannel = SharedMediaSanitizer.cleanStream(ogg);
		byte[] viaChat = AttachmentCreationTask.cleanAudio(ogg);
		assertArrayEquals("a contact and a channel get the same file",
				viaChannel, viaChat);
		return viaChat;
	}

	private static void assertRefused(String what, byte[] ogg) {
		try {
			byte[] out = AttachmentCreationTask.cleanAudio(ogg);
			fail(what + " was sent" + (contains(out, MARKER_BYTES)
					? " with its metadata" : ""));
		} catch (IOException expected) {
		}
		try {
			SharedMediaSanitizer.cleanStream(ogg);
			fail(what + " was shared");
		} catch (IOException expected) {
		}
	}

	@Test
	public void aSpeexCommentHeaderIsReplacedByAnEmptyOne() throws Exception {
		List<byte[]> audio = audioPackets(30, 1);
		byte[] comment = commentList("Encoded with Speex 1.2.1",
				"LOCATION=" + MARKER, "AUTHOR=a phone recorder");
		byte[] ogg = stream(41, speexHeader(0), list(comment), audio, 320);
		byte[] out = cleaned(ogg);
		assertFalse("the Speex comments were sent",
				contains(out, MARKER_BYTES));
		assertWellFormed(out);
		assertPackets(join(list(speexHeader(0), EMPTY_SPEEX_COMMENT), audio),
				out, 41);
		assertArrayEquals(stream(41, speexHeader(0),
				list(EMPTY_SPEEX_COMMENT), audio, 320), out);
	}

	@Test
	public void aTheoraCommentHeaderIsReplacedAndTheSetupKept()
			throws Exception {
		List<byte[]> video = audioPackets(25, 2);
		byte[] setup = theoraSetup(2600);
		byte[] ogg = stream(7, theoraId(), list(theoraComment(
				"TITLE=" + MARKER, "DATE=2026-09-30"), setup), video, 1);
		byte[] out = cleaned(ogg);
		assertFalse("the Theora comments were sent",
				contains(out, MARKER_BYTES));
		assertWellFormed(out);
		assertPackets(join(list(theoraId(), EMPTY_THEORA_COMMENT, setup),
				video), out, 7);
	}

	@Test
	public void theMetadataBlocksOfAFlacStreamAreReplaced() throws Exception {
		List<byte[]> frames = flacFrames(20);
		byte[] comment = flacBlock(4, false, commentList("reference libFLAC",
				"ARTIST=" + MARKER));
		byte[] picture = flacBlock(6, false, concat(new byte[32],
				MARKER_BYTES, new byte[200]));
		byte[] padding = flacBlock(1, true, new byte[512]);
		byte[] ogg = stream(3, flacId(3, false),
				list(comment, picture, padding), frames, 4096);
		byte[] out = cleaned(ogg);
		assertFalse("the FLAC metadata was sent", contains(out, MARKER_BYTES));
		assertWellFormed(out);
		assertPackets(join(list(flacId(1, false), EMPTY_FLAC_COMMENT),
				frames), out, 3);
	}

	@Test
	public void aFlacStreamWithoutMetadataBlocksIsKeptAsItIs()
			throws Exception {
		List<byte[]> frames = flacFrames(12);
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		long seq = pages(b, list(flacId(0, true)), 5, 0, FIRST, zeros(1),
				false, 255);
		pages(b, frames, 5, seq, 0, granules(frames.size(), 4096), true, 10);
		byte[] ogg = b.toByteArray();
		assertArrayEquals(ogg, cleaned(ogg));
	}

	@Test
	public void aSkeletonStreamIsLeftOut() throws Exception {
		List<byte[]> audio = audioPackets(15, 4);
		byte[] fishead = concat(ascii("fishead\0"), new byte[56]);
		byte[] fisbone = concat(ascii("fisbone\0"), new byte[44],
				ascii("Content-Type: audio/opus\r\nName: " + MARKER
						+ "\r\nTitle: " + MARKER + "\r\n"));
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		long skel = pages(b, list(fishead), 9, 0, FIRST, zeros(1), false,
				255);
		long op = pages(b, list(OggTestFiles.opusHead()), 1, 0, FIRST,
				zeros(1), false, 255);
		skel = pages(b, list(fisbone), 9, skel, 0, zeros(1), false, 255);
		pages(b, list(new byte[0]), 9, skel, 0, zeros(1), true, 255);
		op = pages(b, list(opusTags("TITLE=" + MARKER)), 1, op, 0, zeros(1),
				false, 255);
		pages(b, audio, 1, op, 0, granules(audio.size(), 960), true, 10);
		byte[] out = cleaned(b.toByteArray());
		assertFalse("the Skeleton names were sent",
				contains(out, MARKER_BYTES));
		assertWellFormed(out);
		assertTrue(packets(readPages(out), 9).isEmpty());
		assertArrayEquals(opus(1, EMPTY_OPUS_TAGS, audio, 255, 10), out);
	}

	@Test
	public void streamsWhoseMetadataCannotBeRemovedAreRefused() {
		List<byte[]> data = audioPackets(10, 5);
		byte[] kate = stream(2, concat(new byte[] {(byte) 0x80, 'k', 'a',
				't', 'e', 0, 0, 0}, new byte[56]), list(concat(new byte[] {
				(byte) 0x81, 'k', 'a', 't', 'e', 0, 0, 0},
				commentList("kate", "TITLE=" + MARKER))), data, 1);
		byte[] unknown = stream(3, concat(ascii("CMML\0\0\0\0"),
				new byte[20]), list(ascii("<head><title>" + MARKER
				+ "</title></head>")), data, 1);
		byte[] speexExtra = stream(4, speexHeader(1), list(commentList(
				"speex", "TITLE=" + MARKER), ascii("extra " + MARKER)),
				data, 320);
		byte[] flacUnknownCount = stream(6, flacId(0, false), list(
				flacBlock(4, true, commentList("x", "A=" + MARKER))),
				flacFrames(5), 4096);
		byte[] flacWrongLast = stream(6, flacId(2, false), list(
				flacBlock(4, true, commentList("x", "A=" + MARKER)),
				flacBlock(1, true, new byte[8])), flacFrames(5), 4096);
		ByteArrayOutputStream skeletonOnly = new ByteArrayOutputStream();
		long seq = pages(skeletonOnly, list(concat(ascii("fishead\0"),
				new byte[56])), 9, 0, FIRST, zeros(1), false, 255);
		pages(skeletonOnly, list(new byte[0]), 9, seq, 0, zeros(1), true,
				255);
		assertRefused("a Kate stream", kate);
		assertRefused("a stream in an unknown codec", unknown);
		assertRefused("Speex with extra headers", speexExtra);
		assertRefused("FLAC with an unknown header count", flacUnknownCount);
		assertRefused("FLAC whose blocks contradict their count",
				flacWrongLast);
		assertRefused("a file with only a Skeleton stream",
				skeletonOnly.toByteArray());
	}

	@Test
	public void aSpeexStreamNextToOpusLosesItsCommentsToo() throws Exception {
		List<byte[]> a = audioPackets(20, 4);
		List<byte[]> b = audioPackets(25, 5);
		ByteArrayOutputStream grouped = new ByteArrayOutputStream();
		long opusSeq = pages(grouped, list(OggTestFiles.opusHead()), 1, 0,
				FIRST, zeros(1), false, 255);
		long speexSeq = pages(grouped, list(speexHeader(0)), 2, 0, FIRST,
				zeros(1), false, 255);
		opusSeq = pages(grouped, list(opusTags("LOCATION=" + MARKER)), 1,
				opusSeq, 0, zeros(1), false, 255);
		speexSeq = pages(grouped, list(commentList("speex",
				"NOTE=" + MARKER)), 2, speexSeq, 0, zeros(1), false, 255);
		pages(grouped, a, 1, opusSeq, 0, granules(a.size(), 960), true, 10);
		pages(grouped, b, 2, speexSeq, 0, granules(b.size(), 160), true, 10);
		byte[] out = cleaned(grouped.toByteArray());
		assertFalse(contains(out, MARKER_BYTES));
		assertWellFormed(out);
		assertPackets(join(list(OggTestFiles.opusHead(), EMPTY_OPUS_TAGS), a),
				out, 1);
		assertPackets(join(list(speexHeader(0), EMPTY_SPEEX_COMMENT), b),
				out, 2);
	}
}
