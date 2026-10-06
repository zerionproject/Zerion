package com.professor.zerion.android.attachment;

import android.content.Context;
import android.net.Uri;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.utils.MetadataStripper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SharedMediaSanitizerContentTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MAX = 16L * 1024 * 1024;
	private static final byte[] MARKER =
			"ZtPlantedGps52.3702N4.8952E".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] SECOND_MARKER =
			"ZtPlantedOwnerName".getBytes(StandardCharsets.US_ASCII);
	private static final String[] LOOSE_TYPES = {"application/octet-stream",
			"application/pdf", "text/plain"};

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
		File f = File.createTempFile("zt_content_", ".bin", ctx.getCacheDir());
		temp.add(f);
		Files.write(f.toPath(), data);
		return Uri.fromFile(f);
	}

	private static byte[] withMarker(int... magic) {
		byte[] d = new byte[4096];
		for (int i = 0; i < magic.length; i++) d[i] = (byte) magic[i];
		for (int i = 32; i < d.length; i++) d[i] = (byte) (i * 7);
		System.arraycopy(MARKER, 0, d, 64, MARKER.length);
		return d;
	}

	private static byte[] ascii(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
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

	private void assertRefused(String what, String declared, byte[] data)
			throws IOException {
		try {
			SharedMediaSanitizer.Cleaned c =
					sanitizer.sanitize(stage(data), declared, data, MAX);
			fail(what + " declared as " + declared + " was sent as "
					+ c.getMimeType() + (Arrays.equals(data, c.getData())
					? ", unchanged with its metadata" : ""));
		} catch (IOException expected) {
		}
	}

	private void assertNeverSentWithMetadata(String what, String declared,
			byte[] data) throws IOException {
		SharedMediaSanitizer.Cleaned c;
		try {
			c = sanitizer.sanitize(stage(data), declared, data, MAX);
		} catch (IOException refused) {
			return;
		}
		assertFalse(what + " declared as " + declared
						+ " was sent with its metadata",
				contains(c.getData(), MARKER));
	}

	@Test
	public void tiffAndRawCameraFilesAreRefusedWhateverTheirType()
			throws Exception {
		int[][] magics = {{'I', 'I', 42, 0}, {'M', 'M', 0, 42},
				{'I', 'I', 43, 0}, {'M', 'M', 0, 43}, {'I', 'I', 'R', 'O'},
				{'I', 'I', 'U', 0}};
		for (int[] magic : magics) {
			for (String declared : LOOSE_TYPES) {
				assertRefused("TIFF " + Arrays.toString(magic), declared,
						withMarker(magic));
			}
		}
	}

	@Test
	public void jpegXlAndJpeg2000AreRefused() throws Exception {
		int[][] magics = {{0xFF, 0x0A},
				{0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, 0x87, 0x0A},
				{0, 0, 0, 0x0C, 'j', 'P', ' ', ' ', 0x0D, 0x0A, 0x87, 0x0A},
				{0xFF, 0x4F, 0xFF, 0x51}};
		for (int[] magic : magics) {
			for (String declared : LOOSE_TYPES) {
				assertRefused("image " + Arrays.toString(magic), declared,
						withMarker(magic));
			}
		}
	}

	@Test
	public void quickTimeWithoutAFileTypeBoxIsNeverSentUnchanged()
			throws Exception {
		for (String atom : new String[] {"moov", "wide", "mdat", "free",
				"skip", "pnot"}) {
			byte[] a = ascii(atom);
			byte[] data = withMarker(0, 0, 0, 0x10, a[0], a[1], a[2], a[3]);
			for (String declared : LOOSE_TYPES) {
				assertNeverSentWithMetadata("QuickTime starting with "
						+ atom, declared, data);
			}
		}
	}

	@Test
	public void matroskaWebmAndAviAreNeverSentUnchanged() throws Exception {
		byte[] mkv = withMarker(0x1A, 0x45, 0xDF, 0xA3);
		byte[] avi = withMarker('R', 'I', 'F', 'F', 0xF8, 0x0F, 0, 0,
				'A', 'V', 'I', ' ');
		for (String declared : LOOSE_TYPES) {
			assertNeverSentWithMetadata("Matroska", declared, mkv);
			assertNeverSentWithMetadata("AVI", declared, avi);
		}
	}

	@Test
	public void otherRiffFormsAreRefused() throws Exception {
		assertRefused("RIFF MIDI", "application/octet-stream",
				withMarker('R', 'I', 'F', 'F', 0xF8, 0x0F, 0, 0,
						'R', 'M', 'I', 'D'));
		assertRefused("RIFX", "application/octet-stream",
				withMarker('R', 'I', 'F', 'X', 0, 0, 0x0F, 0xF8,
						'W', 'A', 'V', 'E'));
		assertRefused("RF64", "audio/wav",
				withMarker('R', 'F', '6', '4', 0xFF, 0xFF, 0xFF, 0xFF,
						'W', 'A', 'V', 'E'));
	}

	private static byte[] chunk(String id, byte[] body) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(ascii(id), 0, 4);
		int n = body.length;
		out.write(n);
		out.write(n >> 8);
		out.write(n >> 16);
		out.write(n >> 24);
		out.write(body, 0, n);
		if ((n & 1) != 0) out.write(0);
		return out.toByteArray();
	}

	private static byte[] riff(String form, byte[]... chunks) {
		byte[] body = concat(chunks);
		int n = body.length + 4;
		return concat(ascii("RIFF"), new byte[] {(byte) n, (byte) (n >> 8),
				(byte) (n >> 16), (byte) (n >> 24)}, ascii(form), body);
	}

	static byte[] samples(int n) {
		byte[] s = new byte[n];
		for (int i = 0; i < n; i++) s[i] = (byte) (i * 31 + 5);
		return s;
	}

	@Test
	public void aWaveFileKeepsOnlyItsFormatAndSamples() throws Exception {
		byte[] fmt = {1, 0, 1, 0, 0x44, (byte) 0xAC, 0, 0, (byte) 0x88,
				0x58, 1, 0, 2, 0, 16, 0};
		byte[] info = concat(ascii("INFO"), chunk("IART", MARKER));
		byte[] data = samples(1001);
		byte[] wave = riff("WAVE", chunk("fmt ", fmt), chunk("LIST", info),
				chunk("data", data), chunk("bext", SECOND_MARKER));
		assertTrue(contains(wave, MARKER));
		for (String declared : new String[] {"audio/wav",
				"application/octet-stream"}) {
			SharedMediaSanitizer.Cleaned c =
					sanitizer.sanitize(stage(wave), declared, wave, MAX);
			byte[] out = c.getData();
			assertFalse(declared + ": the INFO list must go",
					contains(out, MARKER));
			assertFalse(declared + ": the broadcast chunk must go",
					contains(out, SECOND_MARKER));
			assertArrayEquals(riff("WAVE", chunk("fmt ", fmt),
					chunk("data", data)), out);
		}
	}

	static byte[] id3v23(byte[] title) {
		byte[] frame = concat(ascii("TIT2"), new byte[] {0, 0, 0,
				(byte) (title.length + 1), 0, 0, 0}, title);
		int n = frame.length;
		return concat(ascii("ID3"), new byte[] {3, 0, 0, 0, 0,
				(byte) ((n >> 7) & 0x7F), (byte) (n & 0x7F)}, frame);
	}

	static byte[] mpegFrames(int count) {
		byte[] frames = new byte[417 * count];
		for (int f = 0; f < count; f++) {
			int o = f * 417;
			frames[o] = (byte) 0xFF;
			frames[o + 1] = (byte) 0xFB;
			frames[o + 2] = (byte) 0x90;
			frames[o + 3] = 0x64;
			for (int i = 4; i < 417; i++) frames[o + i] = (byte) (i + f);
		}
		return frames;
	}

	static byte[] id3v1(byte[] title) {
		byte[] tag = new byte[128];
		tag[0] = 'T';
		tag[1] = 'A';
		tag[2] = 'G';
		System.arraycopy(title, 0, tag, 3, Math.min(30, title.length));
		return tag;
	}

	@Test
	public void anMp3LosesItsId3Tags() throws Exception {
		byte[] frames = mpegFrames(3);
		byte[] mp3 = concat(id3v23(MARKER), frames, id3v1(SECOND_MARKER));
		for (String declared : new String[] {"audio/mpeg",
				"application/octet-stream"}) {
			SharedMediaSanitizer.Cleaned c =
					sanitizer.sanitize(stage(mp3), declared, mp3, MAX);
			assertFalse(declared + ": the ID3v2 tag must go",
					contains(c.getData(), MARKER));
			assertFalse(declared + ": the ID3v1 tag must go",
					contains(c.getData(), SECOND_MARKER));
			assertArrayEquals(frames, c.getData());
			assertEquals(declared, c.getMimeType());
		}
	}

	@Test
	public void aTagThatHidesAnotherFileOrIsBrokenIsRefused()
			throws Exception {
		byte[] hidden = concat(id3v23(ascii("x")), withMarker('I', 'I', 42, 0));
		assertRefused("TIFF behind an ID3 tag", "audio/mpeg", hidden);
		byte[] broken = concat(ascii("ID3"), new byte[] {3, 0, 0, 0, 0,
				(byte) 0x80, 0}, mpegFrames(1));
		assertRefused("an ID3 tag with a bad size", "audio/mpeg", broken);
		byte[] cut = concat(ascii("ID3"), new byte[] {3, 0, 0, 0, 0x7F,
				0x7F, 0x7F}, MARKER);
		assertRefused("an ID3 tag longer than the file", "audio/mpeg", cut);
	}

	@Test
	public void documentsAndOtherFilesAreSentAsBefore() throws Exception {
		byte[] pdf = ascii("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n");
		byte[] text = ascii("plain notes\n");
		byte[] zip = concat(new byte[] {'P', 'K', 3, 4}, samples(200));
		byte[] ole = concat(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11,
				(byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
				samples(200));
		Object[][] cases = {{pdf, "application/pdf"}, {text, "text/plain"},
				{zip, "application/vnd.openxmlformats-officedocument"
						+ ".wordprocessingml.document"},
				{ole, "application/msword"}, {zip, "application/zip"}};
		for (Object[] c : cases) {
			byte[] data = (byte[]) c[0];
			String declared = (String) c[1];
			SharedMediaSanitizer.Cleaned out =
					sanitizer.sanitize(stage(data), declared, data, MAX);
			assertEquals(declared, out.getMimeType());
			assertArrayEquals(declared, data, out.getData());
		}
	}

	@Test
	public void anImageThatDoesNotFitInMemoryIsRefusedNotACrash()
			throws Exception {
		byte[] jpeg = withMarker(0xFF, 0xD8, 0xFF, 0xE0);
		Uri uri = stage(jpeg);
		try (MockedConstruction<MetadataStripper> ignored =
				mockConstruction(MetadataStripper.class, (m, c) ->
						when(m.stripImageMetadataOrThrow(any(), anyString()))
								.thenThrow(new OutOfMemoryError(
										"bitmap allocation failed")))) {
			SharedMediaSanitizer s = new SharedMediaSanitizer(ctx);
			try {
				s.sanitize(uri, "image/jpeg", jpeg, MAX);
				fail("an image that does not fit in memory was sent");
			} catch (IOException expected) {
			} catch (OutOfMemoryError e) {
				fail("the app would crash: " + e);
			}
		}
	}
}
