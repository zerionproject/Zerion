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

import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v23;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.mpegFrames;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SharedMediaSanitizerGapsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MAX = 16L * 1024 * 1024;
	private static final byte[] MARKER =
			"ZtPlantedGps52.3702N4.8952E".getBytes(StandardCharsets.US_ASCII);
	private static final String[] LOOSE_TYPES = {"audio/mpeg",
			"application/octet-stream", "application/pdf", "text/plain"};

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
		File f = File.createTempFile("zt_gaps_", ".bin", ctx.getCacheDir());
		temp.add(f);
		Files.write(f.toPath(), data);
		return Uri.fromFile(f);
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	private static byte[] withMarker(int... magic) {
		byte[] d = new byte[1024];
		for (int i = 0; i < magic.length; i++) d[i] = (byte) magic[i];
		for (int i = 32; i < d.length; i++) d[i] = (byte) (i * 7);
		System.arraycopy(MARKER, 0, d, 64, MARKER.length);
		return d;
	}

	private static byte[] withMarker(byte[] magic) {
		int[] m = new int[magic.length];
		for (int i = 0; i < m.length; i++) m[i] = magic[i] & 0xFF;
		return withMarker(m);
	}

	private void assertRefused(String what, String declared, byte[] data)
			throws IOException {
		try {
			SharedMediaSanitizer.Cleaned c =
					sanitizer.sanitize(stage(data), declared, data, MAX);
			fail(what + " declared as " + declared + " was sent as "
					+ c.getMimeType() + (containsMarker(c.getData())
					? " with its metadata" : ""));
		} catch (IOException expected) {
		}
	}

	private static boolean containsMarker(byte[] hay) {
		outer:
		for (int i = 0; i + MARKER.length <= hay.length; i++) {
			for (int j = 0; j < MARKER.length; j++) {
				if (hay[i + j] != MARKER[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static byte[][] hiddenFiles() {
		return new byte[][] {
				withMarker(0xFF, 0xD8, 0xFF, 0xE1, 0, 0x40, 'E', 'x', 'i', 'f',
						0, 0),
				withMarker(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A),
				withMarker('I', 'I', 42, 0),
				withMarker(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4',
						'2'),
				withMarker(0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i',
						'c'),
				withMarker('R', 'I', 'F', 'F', 0, 4, 0, 0, 'W', 'E', 'B',
						'P'),
				withMarker(0x1A, 0x45, 0xDF, 0xA3),
				withMarker('f', 'L', 'a', 'C', 0x84, 0, 0, 40),
				withMarker('O', 'g', 'g', 'S', 0, 2)};
	}

	@Test
	public void aFileHiddenAFewBytesAfterTheTagsIsRefused() throws Exception {
		byte[] tag = id3v23(ascii("title"));
		byte[][] hidden = hiddenFiles();
		for (int gap : new int[] {1, 3, 64, 1000, 4000}) {
			for (int h = 0; h < hidden.length; h++) {
				byte[] junk = new byte[gap];
				byte[] data = concat(tag, junk, hidden[h]);
				for (String declared : new String[] {"audio/mpeg",
						"application/octet-stream"}) {
					assertRefused("file " + h + " " + gap
							+ " bytes after an ID3 tag", declared, data);
				}
			}
		}
		byte[] frameThenJpeg = concat(tag, new byte[] {(byte) 0xFF,
				(byte) 0xFB, (byte) 0x90, 0x64}, hidden[0]);
		assertRefused("a picture after one frame header", "audio/mpeg",
				frameThenJpeg);
	}

	@Test
	public void formatsWhoseMetadataCannotBeRemovedAreRefused()
			throws Exception {
		byte[][] formats = {
				withMarker(0x76, 0x2F, 0x31, 0x01, 2, 0, 0, 0),
				withMarker(ascii("FUJIFILMCCD-RAW 0201FF383501")),
				withMarker('I', 'I', 0x1A, 0, 0, 0, 'H', 'E', 'A', 'P', 'C',
						'C', 'D', 'R'),
				withMarker(0, 'M', 'R', 'M', 0, 0, 0x0B, 0x24),
				withMarker('F', 'O', 'V', 'b', 0, 0, 2, 0)};
		String[] names = {"OpenEXR", "Fuji RAF", "Canon CRW", "Minolta MRW",
				"Sigma X3F"};
		for (int i = 0; i < formats.length; i++) {
			for (String declared : LOOSE_TYPES) {
				assertRefused(names[i], declared, formats[i]);
			}
			assertRefused(names[i] + " behind a tag", "audio/mpeg",
					concat(id3v23(ascii("t")), new byte[5], formats[i]));
		}
	}

	@Test
	public void anIcoFileIsSentAsItIs() throws Exception {
		byte[] ico = withMarker(0, 0, 1, 0, 1, 0, 16, 16, 0, 0, 1, 0, 32, 0);
		SharedMediaSanitizer.Cleaned c = sanitizer.sanitize(stage(ico),
				"application/octet-stream", ico, MAX);
		assertArrayEquals(ico, c.getData());
		assertEquals("application/octet-stream", c.getMimeType());
	}

	@Test
	public void taggedAudioWithPaddingIsStillSentWithoutItsTag()
			throws Exception {
		byte[] frames = mpegFrames(12);
		byte[] padded = concat(new byte[700], frames);
		byte[] mp3 = concat(id3v23(MARKER), padded);
		SharedMediaSanitizer.Cleaned c = sanitizer.sanitize(stage(mp3),
				"audio/mpeg", mp3, MAX);
		assertArrayEquals(padded, c.getData());
		byte[] bare = mpegFrames(20);
		c = sanitizer.sanitize(stage(bare), "audio/mpeg", bare, MAX);
		assertArrayEquals(bare, c.getData());
		assertEquals("audio/mpeg", c.getMimeType());
	}
}
