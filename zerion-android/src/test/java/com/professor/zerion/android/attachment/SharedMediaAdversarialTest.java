package com.professor.zerion.android.attachment;

import com.professor.zerion.android.util.SafeImageDecoder;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SharedMediaAdversarialTest {

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static byte[] gif(byte[] afterTrailer) {
		byte[] header = concat(ascii("GIF89a"), new byte[] {1, 0, 1, 0,
				(byte) 0x80, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF,
				(byte) 0xFF});
		byte[] comment = concat(new byte[] {0x21, (byte) 0xFE, 5},
				ascii("hello"), new byte[] {0});
		byte[] image = {0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 0x01, 0};
		return concat(header, comment, image, new byte[] {0x3B},
				afterTrailer);
	}

	private static byte[] id3(int size) {
		return concat(ascii("ID3"), new byte[] {4, 0, 0,
				(byte) ((size >> 21) & 0x7F), (byte) ((size >> 14) & 0x7F),
				(byte) ((size >> 7) & 0x7F), (byte) (size & 0x7F)},
				new byte[size]);
	}

	private static final byte[] MP3_FRAME =
			{(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x00, 1, 2, 3, 4, 5, 6};

	@Test
	public void aPageAppendedToAGifIsDropped() throws Exception {
		byte[] page = ascii("<html><script>fetch('http://x')</script></html>");
		byte[] rebuilt = GifMetadataStripper.withoutMetadata(gif(page));
		assertFalse("the page survived the rebuild", contains(rebuilt, page));
		assertFalse("the comment survived the rebuild",
				contains(rebuilt, ascii("hello")));
		assertEquals(0x3B, rebuilt[rebuilt.length - 1] & 0xFF);
		assertArrayEquals(rebuilt,
				GifMetadataStripper.withoutMetadata(gif(new byte[0])));
	}

	@Test
	public void markupBehindAnAudioTagIsNotSentAsAudio() {
		byte[] page = concat(id3(40),
				ascii("<html><body>http://x</body></html>"));
		byte[] playlist = concat(id3(40), ascii("#EXTM3U\nhttp://x/a.mp3\n"));
		byte[] words = concat(id3(40), ascii("just some words and more words"));
		for (byte[] c : new byte[][] {page, playlist, words}) {
			assertTrue(AudioTagStripper.hasLeadingTag(c));
			try {
				SharedMediaSanitizer.cleanStream(AudioTagStripper.withoutTags(c));
				fail("non-audio behind a tag was sent as audio");
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void soundBehindAnAudioTagIsSentWithoutTheTag() throws Exception {
		byte[] tagged = concat(id3(40), MP3_FRAME);
		byte[] clean = SharedMediaSanitizer.cleanStream(
				AudioTagStripper.withoutTags(tagged));
		assertArrayEquals(MP3_FRAME, clean);
		byte[] padded = concat(id3(40), new byte[300], MP3_FRAME);
		byte[] cleanPadded = SharedMediaSanitizer.cleanStream(
				AudioTagStripper.withoutTags(padded));
		assertTrue(contains(cleanPadded, MP3_FRAME));
		byte[] amr = concat(id3(8), ascii("#!AMR\n"), new byte[32]);
		assertTrue(SharedMediaSanitizer.cleanStream(
				AudioTagStripper.withoutTags(amr)).length > 0);
	}

	@Test
	public void aFileOfAnotherKindBehindATagIsRefused() {
		byte[][] hidden = {
				concat(ascii("GIF89a"), new byte[40]),
				concat(new byte[] {(byte) 0x89}, ascii("PNG\r\n"),
						new byte[] {0x1A, 0x0A}, new byte[40]),
				concat(ascii("RIFF"), new byte[] {100, 0, 0, 0}, ascii("AVI "),
						new byte[40]),
				concat(new byte[] {0, 0, 0, 0x18}, ascii("ftypisom"),
						new byte[40]),
				concat(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3},
						new byte[40]),
				concat(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF,
						(byte) 0xE1}, new byte[40]),
				concat(ascii("II*\0"), new byte[40])};
		for (byte[] rest : hidden) {
			assertTrue(SharedMediaSanitizer.hidesAnotherContainer(rest));
			try {
				SharedMediaSanitizer.cleanStream(rest);
				fail("a hidden container was sent as audio");
			} catch (IOException expected) {
			}
		}
		byte[] afterAFewBytes = concat(new byte[] {7, 7, 7}, ascii("OggS"),
				new byte[40]);
		assertTrue(SharedMediaSanitizer.hidesAnotherContainer(afterAFewBytes));
	}

	@Test
	public void imagesThatCannotBeReencodedAreRefusedWhateverTheirType() {
		byte[][] uncleanable = {
				concat(ascii("II*\0"), new byte[40]),
				concat(ascii("MM\0*"), new byte[40]),
				concat(new byte[] {(byte) 0xFF, 0x0A}, new byte[40]),
				concat(new byte[] {0, 0, 0, 0x0C}, ascii("jP  "),
						new byte[] {0x0D, 0x0A, (byte) 0x87, 0x0A},
						new byte[40]),
				concat(ascii("8BPS"), new byte[40]),
				concat(new byte[] {0x76, 0x2F, 0x31, 0x01}, new byte[40]),
				concat(ascii("FUJIFILMCCD-RAW"), new byte[40])};
		for (byte[] d : uncleanable) {
			assertTrue(SharedMediaSanitizer.cannotBeCleaned(d));
		}
		byte[] riffWebp = concat(ascii("RIFF"), new byte[] {100, 0, 0, 0},
				ascii("WEBP"), new byte[40]);
		assertTrue("a RIFF form that is neither WAVE nor AVI",
				SharedMediaSanitizer.cannotBeCleaned(riffWebp)
						|| SafeImageDecoder.hasAllowedMagic(riffWebp));
		byte[] wave = concat(ascii("RIFF"), new byte[] {100, 0, 0, 0},
				ascii("WAVE"), new byte[40]);
		assertFalse(SharedMediaSanitizer.cannotBeCleaned(wave));
	}

	@Test
	public void containersAreRemuxedWhateverTheDeclaredType() {
		byte[] mp4 = concat(new byte[] {0, 0, 0, 0x18}, ascii("ftypisom"),
				new byte[40]);
		byte[] webm = concat(new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3},
				new byte[40]);
		byte[] avi = concat(ascii("RIFF"), new byte[] {100, 0, 0, 0},
				ascii("AVI "), new byte[40]);
		for (String declared : new String[] {"audio/mpeg", "image/jpeg",
				"application/octet-stream", "text/plain", "video/mp4"}) {
			assertTrue(declared, SharedMediaSanitizer.needsRemux(declared, mp4));
			assertTrue(declared, SharedMediaSanitizer.needsRemux(declared, webm));
			assertTrue(declared, SharedMediaSanitizer.needsRemux(declared, avi));
		}
		assertFalse(SharedMediaSanitizer.needsRemux("audio/mpeg", MP3_FRAME));
		assertTrue(SharedMediaSanitizer.needsRemux("audio/3gpp", MP3_FRAME));
	}

	@Test
	public void theReencodedTypeFollowsTheBytesNotTheLabel() {
		byte[] bmp = concat(ascii("BM"), new byte[12], new byte[] {40, 0, 0, 0},
				new byte[40]);
		byte[] avif = concat(new byte[] {0, 0, 0, 0x1C}, ascii("ftypavif"),
				new byte[40]);
		assertEquals("image/png",
				SharedMediaSanitizer.reencodedType("image/jpeg", bmp));
		assertEquals("image/jpeg",
				SharedMediaSanitizer.reencodedType("image/avif", avif));
		assertEquals("image/jpeg", SharedMediaSanitizer.reencodedType(
				"application/octet-stream", new byte[] {(byte) 0xFF,
						(byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
		assertEquals("image/jpeg", SharedMediaSanitizer.reencodedType(
				"text/html", new byte[] {(byte) 0xFF, (byte) 0xD8}));
	}
}
