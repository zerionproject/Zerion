package com.professor.zerion.android.attachment;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v1;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v23;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.mpegFrames;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.samples;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class MpegAudioFramesTest {

	private static final byte[] MARKER =
			"ZtPlantedEncoderSettings".getBytes(StandardCharsets.US_ASCII);

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
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

	private static byte[] text(int length) {
		byte[] t = new byte[length];
		for (int i = 0; i < length; i++) {
			t[i] = i < MARKER.length ? MARKER[i] : (byte) ('a' + i % 26);
		}
		return t;
	}

	private static void put(byte[] d, int off, String s) {
		byte[] b = ascii(s);
		System.arraycopy(b, 0, d, off, b.length);
	}

	static byte[] encoderFrame(String tag, String encoder) {
		byte[] f = new byte[417];
		f[0] = (byte) 0xFF;
		f[1] = (byte) 0xFB;
		f[2] = (byte) 0x90;
		f[3] = 0x64;
		put(f, 36, tag);
		f[43] = 0x0F;
		f[47] = 0x40;
		f[50] = 0x1A;
		for (int i = 0; i < 100; i++) f[52 + i] = (byte) (i * 2);
		put(f, 156, encoder);
		return f;
	}

	static byte[] adtsFrame(int length, int seed, boolean crc) {
		byte[] f = new byte[length];
		f[0] = (byte) 0xFF;
		f[1] = (byte) (crc ? 0xF0 : 0xF1);
		f[2] = 0x50;
		f[3] = (byte) (0x80 | ((length >> 11) & 3));
		f[4] = (byte) (length >> 3);
		f[5] = (byte) (((length & 7) << 5) | 0x1F);
		f[6] = (byte) 0xFC;
		for (int i = 7; i < length; i++) f[i] = (byte) (i + seed);
		return f;
	}

	static byte[] adtsFrames(int count) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < count; i++) {
			byte[] f = adtsFrame(200 + 37 * i, i, false);
			out.write(f, 0, f.length);
		}
		return out.toByteArray();
	}

	private static int length(int b1, int b2) {
		return MpegAudioFrames.frameLength(new byte[] {(byte) 0xFF,
				(byte) b1, (byte) b2, 0x64, 0x02, 0x1F, (byte) 0xFC, 0}, 0);
	}

	private static void assertRefused(String what, byte[] d) {
		try {
			byte[] out = MpegAudioFrames.rebuilt(d);
			fail(what + " was rebuilt into " + out.length + " bytes");
		} catch (IOException expected) {
		}
	}

	@Test
	public void frameLengthsFollowTheTablesOfEachVersionAndLayer() {
		int[][] cases = {
				{0xFF, 0xC0, 416}, {0xFF, 0xC2, 420}, {0xFF, 0xC4, 384},
				{0xFF, 0xE8, 672},
				{0xFD, 0xA4, 576}, {0xFD, 0xA2, 627}, {0xFD, 0xE8, 1728},
				{0xFB, 0x90, 417}, {0xFB, 0x92, 418}, {0xFB, 0xE8, 1440},
				{0xFB, 0x14, 96}, {0xFA, 0x90, 417},
				{0xF7, 0x90, 312}, {0xF7, 0xE4, 512},
				{0xF5, 0x84, 384}, {0xF5, 0xEA, 1441},
				{0xF3, 0x80, 208}, {0xF3, 0x8A, 289},
				{0xE7, 0x18, 192},
				{0xE5, 0xE4, 1920},
				{0xE3, 0x48, 288}, {0xE3, 0x40, 208}};
		for (int[] c : cases) {
			assertEquals(Integer.toHexString(c[0]) + " "
					+ Integer.toHexString(c[1]), c[2], length(c[0], c[1]));
		}
	}

	@Test
	public void headersThatDoNotDescribeAFrameAreRefused() {
		int[][] cases = {{0xFB, 0x00}, {0xFB, 0x04}, {0xFB, 0xF0},
				{0xFB, 0x9C}, {0xEB, 0x90}, {0xE1, 0x50}, {0xE0, 0x50},
				{0x7B, 0x90}};
		for (int[] c : cases) {
			assertEquals(Integer.toHexString(c[0]) + " "
					+ Integer.toHexString(c[1]), -1, length(c[0], c[1]));
		}
		assertEquals(-1, MpegAudioFrames.frameLength(new byte[] {
				(byte) 0xFE, (byte) 0xFB, (byte) 0x90, 0x64}, 0));
		assertEquals(-1, MpegAudioFrames.frameLength(new byte[] {
				(byte) 0xFF, (byte) 0xFB, (byte) 0x90}, 0));
	}

	@Test
	public void adtsFrameLengthsComeFromTheHeader() {
		assertEquals(300, MpegAudioFrames.frameLength(
				adtsFrame(300, 0, false), 0));
		assertEquals(8191, MpegAudioFrames.frameLength(
				adtsFrame(8191, 0, false), 0));
		assertEquals(8, MpegAudioFrames.frameLength(
				adtsFrame(8, 0, false), 0));
		assertEquals(10, MpegAudioFrames.frameLength(
				adtsFrame(10, 0, true), 0));
		byte[] mpeg2 = adtsFrame(300, 0, false);
		mpeg2[1] = (byte) 0xF9;
		assertEquals(300, MpegAudioFrames.frameLength(mpeg2, 0));

		byte[] tooShort = adtsFrame(7, 0, false);
		byte[] crcTooShort = adtsFrame(9, 0, true);
		byte[] blocksTooShort = adtsFrame(13, 0, true);
		blocksTooShort[6] = (byte) 0xFE;
		byte[] badRate = adtsFrame(300, 0, false);
		badRate[2] = 0x74;
		byte[] noLength = adtsFrame(300, 0, false);
		noLength[3] = (byte) 0x80;
		noLength[4] = 0;
		noLength[5] = 0x1F;
		byte[] cut = Arrays.copyOf(adtsFrame(300, 0, false), 6);
		for (byte[] bad : new byte[][] {tooShort, crcTooShort,
				blocksTooShort, badRate, noLength, cut}) {
			assertEquals(-1, MpegAudioFrames.frameLength(bad, 0));
		}
	}

	@Test
	public void aFirstFrameThatCarriesAnEncoderHeaderIsDropped()
			throws Exception {
		byte[] audio = mpegFrames(5);
		String[][] headers = {{"Xing", "LAME3.100"}, {"Info", "LAME3.100"},
				{"Info", "Lavc58.134"}, {"VBRI", ""}, {"", "LAME3.99r"}};
		for (String[] h : headers) {
			byte[] stream = concat(encoderFrame(h[0], h[1]), audio);
			byte[] out = MpegAudioFrames.rebuilt(stream);
			assertArrayEquals(Arrays.toString(h), audio, out);
			assertFalse(contains(out, ascii("LAME")));
			assertFalse(contains(out, ascii("Lavc")));
		}
		assertArrayEquals(audio, MpegAudioFrames.rebuilt(audio));
	}

	@Test
	public void bytesBetweenFramesAreDropped() throws Exception {
		byte[] a = mpegFrames(3);
		byte[] b = mpegFrames(4);
		byte[] junk = text(90);
		assertArrayEquals(concat(a, b),
				MpegAudioFrames.rebuilt(concat(a, junk, b)));
		byte[] lone = concat(text(40), new byte[] {(byte) 0xFF, (byte) 0xFB,
				(byte) 0x90, 0x64}, text(30));
		byte[] out = MpegAudioFrames.rebuilt(concat(a, lone, b));
		assertArrayEquals(concat(a, b), out);
		assertFalse(contains(out, MARKER));
		byte[] otherRate = mpegFrames(1);
		otherRate[2] = (byte) 0x94;
		otherRate = Arrays.copyOf(otherRate, 384);
		assertArrayEquals(concat(a, b),
				MpegAudioFrames.rebuilt(concat(a, otherRate, b)));
	}

	@Test
	public void bytesBeforeAndAfterTheFramesAreDropped() throws Exception {
		byte[] frames = mpegFrames(4);
		assertArrayEquals(frames,
				MpegAudioFrames.rebuilt(concat(frames, text(100))));
		assertArrayEquals(frames, MpegAudioFrames.rebuilt(
				concat(frames, Arrays.copyOf(mpegFrames(1), 200))));
		assertArrayEquals(frames,
				MpegAudioFrames.rebuilt(concat(new byte[300], frames)));
		assertArrayEquals(frames, MpegAudioFrames.rebuilt(
				concat(new byte[50], frames, text(60), new byte[10])));
	}

	@Test
	public void aStreamThatIsMostlyNotFramesIsRefused() throws Exception {
		assertRefused("one frame in a lot of text",
				concat(mpegFrames(1), text(1000)));
		assertRefused("a header in front of text", concat(new byte[] {
				(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64}, text(2000)));
		assertRefused("only an encoder header",
				encoderFrame("Xing", "LAME3.100"));
		assertRefused("nothing", new byte[0]);
		byte[] freeFormat = mpegFrames(6);
		for (int i = 0; i < 6; i++) freeFormat[i * 417 + 2] = 0x04;
		assertRefused("free format", freeFormat);
		byte[] two = mpegFrames(2);
		assertArrayEquals(two, MpegAudioFrames.rebuilt(
				concat(two, text(two.length))));
		assertRefused("more text than sound",
				concat(two, text(two.length + 1)));
	}

	@Test
	public void adtsStreamsAreRebuiltFromTheirFrames() throws Exception {
		byte[] frames = adtsFrames(6);
		assertArrayEquals(frames, MpegAudioFrames.rebuilt(frames));
		byte[] a = adtsFrames(3);
		assertArrayEquals(concat(a, frames), MpegAudioFrames.rebuilt(
				concat(new byte[20], a, text(70), frames, text(33))));
		byte[] crc = concat(adtsFrame(150, 1, true), adtsFrame(160, 2, true),
				adtsFrame(170, 3, true));
		assertArrayEquals(crc, MpegAudioFrames.rebuilt(crc));
		assertArrayEquals(frames, MpegAudioFrames.rebuilt(
				concat(frames, mpegFrames(1))));
	}

	@Test
	public void adtsWhoseLengthsDoNotMatchTheStreamIsRefused() {
		byte[] wrong = concat(new byte[] {(byte) 0xFF, (byte) 0xF1, 0x50,
				(byte) 0x80, 0x02, 0x1F, (byte) 0xFC}, samples(300));
		assertRefused("a header that claims 16 bytes", wrong);
		byte[] longer = adtsFrames(4);
		longer[4] = (byte) (longer[4] + 1);
		assertRefused("a first frame longer than it is",
				Arrays.copyOf(longer, 200 + 50));
		byte[] tiny = concat(adtsFrame(7, 0, false), adtsFrame(7, 0, false),
				text(5));
		assertRefused("frames shorter than their header", tiny);
	}

	@Test
	public void tagsAreRemovedAsBeforeAndTheEncoderFrameWithThem()
			throws Exception {
		byte[] audio = mpegFrames(4);
		byte[] mp3 = concat(id3v23(MARKER), encoderFrame("Info", "LAME3.100"),
				audio, text(64), id3v1(ascii("title")));
		byte[] clean = SharedMediaSanitizer.cleanStream(
				AudioTagStripper.withoutTags(mp3));
		assertArrayEquals(audio, clean);
		assertArrayEquals(audio, AttachmentCreationTask.cleanAudio(mp3));
		byte[] aac = concat(id3v23(MARKER), adtsFrames(3), text(20));
		assertArrayEquals(adtsFrames(3), AttachmentCreationTask.cleanAudio(aac));
		assertEquals("audio/aac", SharedMediaSanitizer.audioType(
				AttachmentCreationTask.cleanAudio(aac)));
	}

	@Test
	public void audioThatIsMostlyJunkIsRefusedOnEveryPath() {
		byte[] junky = concat(id3v23(ascii("t")), mpegFrames(1), text(3000));
		try {
			SharedMediaSanitizer.cleanStream(AudioTagStripper.withoutTags(
					junky));
			fail("mostly junk was cleaned for sharing");
		} catch (IOException expected) {
		}
		try {
			AttachmentCreationTask.cleanAudio(junky);
			fail("mostly junk was cleaned for a chat");
		} catch (IOException expected) {
		}
	}
}
