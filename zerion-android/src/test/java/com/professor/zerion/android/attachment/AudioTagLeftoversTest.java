package com.professor.zerion.android.attachment;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v1;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v23;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.mpegFrames;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class AudioTagLeftoversTest {

	private static final byte[] MARKER =
			"ZtPlantedLyricist".getBytes(StandardCharsets.US_ASCII);

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

	private static String field(String id, byte[] value) {
		return id + String.format("%05d", value.length)
				+ new String(value, StandardCharsets.ISO_8859_1);
	}

	static byte[] lyrics3v2(byte[] lyrics) {
		String body = "LYRICSBEGIN" + field("IND", ascii("10"))
				+ field("LYR", lyrics) + field("AUT", ascii("Someone"));
		return ascii(body + String.format("%06d", body.length())
				+ "LYRICS200");
	}

	private static void assertRefused(String what, byte[] d) {
		try {
			byte[] out = AudioTagStripper.withoutTags(d);
			fail(what + " was accepted" + (contains(out, MARKER)
					? " with the tag in it" : ""));
		} catch (IOException expected) {
		}
	}

	@Test
	public void lyrics3BlocksAreRemoved() throws Exception {
		byte[] frames = mpegFrames(4);
		byte[] v2 = concat(id3v23(ascii("t")), frames, lyrics3v2(MARKER),
				id3v1(ascii("title")));
		assertArrayEquals(frames, AudioTagStripper.withoutTags(v2));
		byte[] v2Alone = concat(frames, lyrics3v2(MARKER));
		assertArrayEquals(frames, AudioTagStripper.withoutTags(v2Alone));
		byte[] v1 = concat(frames, ascii("LYRICSBEGIN"), MARKER,
				ascii("\r\nsecond line LYRICSEND"), id3v1(ascii("title")));
		assertArrayEquals(frames, AudioTagStripper.withoutTags(v1));
	}

	@Test
	public void aLyrics3BlockThatCannotBeMeasuredIsRefused() {
		byte[] frames = mpegFrames(3);
		byte[] v2 = lyrics3v2(MARKER);
		byte[] wrongSize = v2.clone();
		wrongSize[v2.length - 15 + 5] += 3;
		byte[] notDigits = v2.clone();
		notDigits[v2.length - 15] = 'x';
		assertRefused("a Lyrics3 size that points elsewhere",
				concat(frames, wrongSize, id3v1(ascii("t"))));
		assertRefused("a Lyrics3 size that is not a number",
				concat(frames, notDigits));
		assertRefused("a Lyrics3 v1 end without its start",
				concat(frames, MARKER, ascii("LYRICSEND")));
		byte[] probe = ascii("LYRICSBEGININD0000210LYR00020Lyric by "
				+ "ZtPlantedLyricist.AUT00005Alice000064LYRICS200");
		assertRefused("a Lyrics3 block with a stale size",
				concat(id3v23(ascii("t")), frames, probe,
						id3v1(ascii("t"))));
	}

	@Test
	public void id3TagsInsideTheStreamAreRemoved() throws Exception {
		byte[] a = mpegFrames(2);
		byte[] b = mpegFrames(3);
		byte[] joined = concat(a, id3v23(MARKER), b);
		assertArrayEquals(concat(a, b), AudioTagStripper.withoutTags(joined));
		byte[] twice = concat(id3v23(ascii("lead")), a, id3v23(MARKER), b,
				id3v23(ascii("appended without a footer")));
		assertArrayEquals(concat(a, b), AudioTagStripper.withoutTags(twice));
		byte[] cut = concat(a, ascii("ID3"), new byte[] {3, 0, 0, 0, 0, 0x7F,
				0x7F}, ascii("TIT2"), MARKER);
		assertRefused("a tag inside the stream that runs past its end", cut);
	}

	@Test
	public void moreTrailingTagsThanAFileCarriesAreRefused() {
		ByteArrayOutputStream tags = new ByteArrayOutputStream();
		for (int i = 0; i < 9; i++) {
			byte[] t = id3v1(i == 0 ? MARKER : ascii("tag " + i));
			tags.write(t, 0, t.length);
		}
		assertRefused("nine trailing tags", concat(mpegFrames(2),
				tags.toByteArray()));
	}

	@Test
	public void anApeTagAtTheStartIsRemoved() throws Exception {
		byte[] item = concat(le32(MARKER.length), le32(0), ascii("Artist\0"),
				MARKER);
		long size = 32 + item.length;
		byte[] ape = concat(ascii("APETAGEX"), le32(2000), le32(size),
				le32(1), le32(0xA0000000L), new byte[8], item,
				ascii("APETAGEX"), le32(2000), le32(size), le32(1),
				le32(0x80000000L), new byte[8]);
		byte[] frames = mpegFrames(3);
		assertArrayEquals(frames, AudioTagStripper.withoutTags(
				concat(ape, frames)));
		assertArrayEquals(frames, AudioTagStripper.withoutTags(
				concat(id3v23(ascii("t")), ape, frames)));
		byte[] footerFirst = concat(ascii("APETAGEX"), le32(2000), le32(size),
				le32(1), le32(0), new byte[8], item, frames);
		assertRefused("an APE footer where a header belongs", footerFirst);
	}

	@Test
	public void soundThatContainsTheLettersOfATagIsLeftAlone()
			throws Exception {
		byte[] frames = mpegFrames(6);
		byte[][] lookalikes = {
				concat(ascii("ID3"), new byte[] {(byte) 0xFF, 0, 0, 0, 0, 0,
						1}),
				concat(ascii("ID3"), new byte[] {3, 0, 0x1F, 0, 0, 0, 1}),
				concat(ascii("ID3"), new byte[] {3, 0, 0, (byte) 0x90, 0, 0,
						1}),
				concat(ascii("ID3"), new byte[] {3, 0, 0, 0, 0, 0, 5},
						new byte[] {(byte) 0xAB})};
		for (byte[] l : lookalikes) {
			byte[] sound = frames.clone();
			System.arraycopy(l, 0, sound, 600, l.length);
			assertArrayEquals(sound, AudioTagStripper.withoutTags(sound));
		}
		byte[] afterTags = concat(id3v23(ascii("t")), frames);
		assertFalse(contains(AudioTagStripper.withoutTags(afterTags),
				ascii("ID3")));
	}
}
