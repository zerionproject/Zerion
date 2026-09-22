package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The chunked voice memo text format under random and hostile input: a part
 * either parses into bounded fields or is refused, never thrown on; every
 * split memo reassembles to the original text; and part counts, sequence
 * numbers and slice sizes outside the format's bounds are refused so a peer
 * cannot make the assembler reserve more than the format allows.
 */
public class VoiceMessageChunkFormatFuzzTest {

	private static final int RANDOM_INPUTS = 6000;
	private static final int ROUND_TRIPS = 40;
	private static final String BASE64 =
			"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

	private final Random random = new Random(73);

	@Test
	public void randomPartsAreParsedOrRefusedNeverThrown() {
		String[] pieces = {"[VMP:1:", "]", "0123456789abcdef",
				"0123456789ABCDEF", "0123456789abcde", ":", "0", "1", "23",
				"24", "25", "-1", "99999999999", "2147483648", "AAAA", "=",
				"A", " ", "[VOICE:", "é", "", "a"};
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			StringBuilder sb = new StringBuilder();
			int n = random.nextInt(14);
			for (int j = 0; j < n; j++) {
				sb.append(pieces[random.nextInt(pieces.length)]);
			}
			String text = sb.toString();
			VoiceMessageChunkFormat.Part part;
			try {
				part = VoiceMessageChunkFormat.parse(text);
			} catch (RuntimeException e) {
				throw new AssertionError(text + ": " + e, e);
			}
			if (part == null) continue;
			assertTrue(text, part.memoId.matches("[0-9a-f]{16}"));
			assertTrue(text, part.total >= 1 && part.total <= 24);
			assertTrue(text, part.seq >= 0 && part.seq < part.total);
			assertTrue(text, part.slice.length() <= 16_000);
			assertTrue(text, part.durationMs >= 0);
		}
	}

	@Test
	public void wellFormedPartsWithHostileFieldsAreRefused() {
		String id = VoiceMessageChunkFormat.newMemoId();
		assertNotNull(VoiceMessageChunkFormat.parse(part(id, 0, 1, 100, "AA")));
		assertNotNull(VoiceMessageChunkFormat.parse(part(id, 23, 24, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(part(id, 0, 25, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(part(id, 0, 0, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(part(id, 1, 1, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(part(id, 24, 24, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(part(id, -1, 2, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(
				part(id, 0, 2, 100, repeat('A', 16_001))));
		assertNotNull(VoiceMessageChunkFormat.parse(
				part(id, 0, 2, 100, repeat('A', 16_000))));
		assertNull(VoiceMessageChunkFormat.parse(
				part(id.toUpperCase(), 0, 2, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(
				part(id.substring(1), 0, 2, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(
				part(id, 0, 2, 100, "AA").replace("]", "")));
		assertNull(VoiceMessageChunkFormat.parse(
				part(id, 0, 99999999999L, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(
				part(id, 99999999999L, 2, 100, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(
				part(id, 0, 2, 99999999999L, "AA")));
		assertNull(VoiceMessageChunkFormat.parse(part(id, 0, 2, 100, "A A")));
		assertNull(VoiceMessageChunkFormat.parse(null));
		assertNull(VoiceMessageChunkFormat.parse(""));
	}

	@Test
	public void everySplitMemoReassemblesToTheOriginal() {
		for (int i = 0; i < ROUND_TRIPS; i++) {
			int bodyLen = VoiceMessageChunkFormat.CHUNK_THRESHOLD_CHARS
					+ random.nextInt(330_000);
			int durationMs = random.nextInt(600_000);
			String text = VoiceMessageFormat.buildFromBase64(durationMs,
					randomBase64(bodyLen));
			assertTrue(VoiceMessageChunkFormat.shouldChunk(text));
			String memoId = VoiceMessageChunkFormat.newMemoId();
			List<String> parts = VoiceMessageChunkFormat.split(text, memoId);
			assertTrue(parts.size() >= 2 && parts.size() <= 24);
			List<String> slices = new ArrayList<>();
			for (int seq = 0; seq < parts.size(); seq++) {
				VoiceMessageChunkFormat.Part p =
						VoiceMessageChunkFormat.parse(parts.get(seq));
				assertNotNull(p);
				assertEquals(memoId, p.memoId);
				assertEquals(seq, p.seq);
				assertEquals(parts.size(), p.total);
				assertEquals(durationMs, p.durationMs);
				slices.add(p.slice);
			}
			assertEquals(text,
					VoiceMessageChunkFormat.reassemble(durationMs, slices));
		}
	}

	@Test
	public void aMemoNeedingMoreThanTheMaximumPartsIsRefusedAtSplit() {
		String text = "[VOICE:1000:" + repeat('A', 24 * 16_000 + 1) + "]";
		try {
			VoiceMessageChunkFormat.split(text,
					VoiceMessageChunkFormat.newMemoId());
			fail();
		} catch (IllegalArgumentException expected) {
		}
		try {
			VoiceMessageChunkFormat.split("not a voice message",
					VoiceMessageChunkFormat.newMemoId());
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void shortMemosAreNotChunked() {
		String text = VoiceMessageFormat.buildFromBase64(1000,
				randomBase64(VoiceMessageChunkFormat.CHUNK_THRESHOLD_CHARS
						- 20));
		assertTrue(!VoiceMessageChunkFormat.shouldChunk(text));
		assertTrue(!VoiceMessageChunkFormat.shouldChunk(null));
		assertTrue(!VoiceMessageChunkFormat.shouldChunk(
				repeat('A', 30_000)));
	}

	private String randomBase64(int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(BASE64.charAt(random.nextInt(BASE64.length())));
		}
		return sb.toString();
	}

	private static String part(String id, long seq, long total,
			long duration, String slice) {
		return "[VMP:1:" + id + ":" + seq + ":" + total + ":" + duration
				+ ":" + slice + "]";
	}

	private static String repeat(char c, int n) {
		StringBuilder sb = new StringBuilder(n);
		for (int i = 0; i < n; i++) sb.append(c);
		return sb.toString();
	}
}
