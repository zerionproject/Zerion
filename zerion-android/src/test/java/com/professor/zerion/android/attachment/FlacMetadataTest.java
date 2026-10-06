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

import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v1;
import static com.professor.zerion.android.attachment.SharedMediaSanitizerContentTest.id3v23;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class FlacMetadataTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MAX = 16L * 1024 * 1024;
	private static final byte[] MARKER =
			"ZtPlantedGps52.3702N4.8952E".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] PICTURE_MARKER =
			"ZtPlantedCoverExif".getBytes(StandardCharsets.US_ASCII);
	private static final byte[] APP_MARKER =
			"ZtPlantedInfoChunk".getBytes(StandardCharsets.US_ASCII);

	private static final int STREAMINFO = 0;
	private static final int PADDING = 1;
	private static final int APPLICATION = 2;
	private static final int SEEKTABLE = 3;
	private static final int VORBIS_COMMENT = 4;
	private static final int CUESHEET = 5;
	private static final int PICTURE = 6;

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
		File f = File.createTempFile("zt_flac_", ".flac", ctx.getCacheDir());
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

	private static byte[] le32(long v) {
		return new byte[] {(byte) v, (byte) (v >> 8), (byte) (v >> 16),
				(byte) (v >> 24)};
	}

	private static byte[] be32(long v) {
		return new byte[] {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8),
				(byte) v};
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

	static byte[] block(int type, boolean last, byte[] body) {
		int n = body.length;
		return concat(new byte[] {(byte) ((last ? 0x80 : 0) | type),
				(byte) (n >> 16), (byte) (n >> 8), (byte) n}, body);
	}

	static byte[] streamInfo() {
		byte[] s = new byte[34];
		s[0] = 0x10;
		s[2] = 0x10;
		s[10] = 0x0A;
		s[11] = (byte) 0xC4;
		s[12] = 0x42;
		s[13] = (byte) 0xF0;
		for (int i = 18; i < 34; i++) s[i] = (byte) (i * 29);
		return s;
	}

	static byte[] seekTable() {
		byte[] s = new byte[36];
		for (int i = 0; i < s.length; i++) s[i] = (byte) (i * 3);
		return s;
	}

	static byte[] vorbisComment(byte[] marker) {
		byte[] vendor = ascii("reference libFLAC 1.4.3 20230623");
		byte[] c1 = concat(ascii("LOCATION="), marker);
		byte[] c2 = ascii("ARTIST=Someone");
		return concat(le32(vendor.length), vendor, le32(2), le32(c1.length),
				c1, le32(c2.length), c2);
	}

	static byte[] picture(byte[] marker) {
		byte[] mime = ascii("image/jpeg");
		byte[] jpeg = concat(new byte[] {(byte) 0xFF, (byte) 0xD8,
				(byte) 0xFF, (byte) 0xE1, 0, 0x20}, ascii("Exif\0\0"), marker,
				new byte[40]);
		return concat(be32(3), be32(mime.length), mime, be32(0), be32(64),
				be32(64), be32(24), be32(0), be32(jpeg.length), jpeg);
	}

	static byte[] frames() {
		byte[] f = new byte[3000];
		for (int i = 0; i < f.length; i++) f[i] = (byte) (i * 13 + 1);
		f[0] = (byte) 0xFF;
		f[1] = (byte) 0xF8;
		f[1500] = (byte) 0xFF;
		f[1501] = (byte) 0xF8;
		return f;
	}

	private static byte[] taggedFlac() {
		return concat(ascii("fLaC"),
				block(STREAMINFO, false, streamInfo()),
				block(SEEKTABLE, false, seekTable()),
				block(VORBIS_COMMENT, false, vorbisComment(MARKER)),
				block(PICTURE, false, picture(PICTURE_MARKER)),
				block(APPLICATION, false, concat(ascii("riff"), ascii("LIST"),
						le32(APP_MARKER.length + 4), ascii("INFO"),
						APP_MARKER)),
				block(CUESHEET, false, new byte[432]),
				block(PADDING, true, new byte[8192]),
				frames());
	}

	private static byte[] cleanFlac() {
		return concat(ascii("fLaC"),
				block(STREAMINFO, false, streamInfo()),
				block(SEEKTABLE, true, seekTable()),
				frames());
	}

	private void assertClean(String what, byte[] out) {
		assertFalse(what + ": the comments were sent", contains(out, MARKER));
		assertFalse(what + ": the picture was sent",
				contains(out, PICTURE_MARKER));
		assertFalse(what + ": the application data was sent",
				contains(out, APP_MARKER));
		assertArrayEquals(what, cleanFlac(), out);
	}

	@Test
	public void aSharedFlacStreamKeepsOnlyWhatADecoderNeeds()
			throws Exception {
		byte[] flac = taggedFlac();
		for (String declared : new String[] {"audio/flac", "audio/x-flac",
				"application/octet-stream"}) {
			SharedMediaSanitizer.Cleaned c = sanitizer.sanitize(stage(flac),
					declared, flac, MAX);
			assertClean(declared, c.getData());
			assertEquals(declared, c.getMimeType());
		}
	}

	@Test
	public void theLastBlockFlagMovesToTheLastBlockKept() throws Exception {
		byte[] flac = concat(ascii("fLaC"),
				block(STREAMINFO, false, streamInfo()),
				block(VORBIS_COMMENT, true, vorbisComment(MARKER)),
				frames());
		byte[] expected = concat(ascii("fLaC"),
				block(STREAMINFO, true, streamInfo()), frames());
		SharedMediaSanitizer.Cleaned c = sanitizer.sanitize(stage(flac),
				"audio/flac", flac, MAX);
		assertArrayEquals(expected, c.getData());
		assertEquals((byte) 0x80, c.getData()[4]);
	}

	@Test
	public void tagsAroundAFlacStreamGoToo() throws Exception {
		byte[] flac = concat(id3v23(ascii("before")), taggedFlac(),
				id3v1(ascii("after")));
		for (String declared : new String[] {"audio/flac", "audio/mpeg"}) {
			SharedMediaSanitizer.Cleaned c = sanitizer.sanitize(stage(flac),
					declared, flac, MAX);
			assertClean(declared + " with tags around it", c.getData());
		}
		byte[] trailing = concat(taggedFlac(), id3v1(MARKER));
		SharedMediaSanitizer.Cleaned c = sanitizer.sanitize(stage(trailing),
				"audio/flac", trailing, MAX);
		assertClean("an ID3v1 tag after the frames", c.getData());
	}

	@Test
	public void aFlacStreamWithMalformedMetadataIsRefused() throws Exception {
		byte[] frames = frames();
		byte[][] broken = {
				concat(ascii("fLaC"), block(VORBIS_COMMENT, false,
						vorbisComment(MARKER)), block(STREAMINFO, true,
						streamInfo()), frames),
				concat(ascii("fLaC"), block(STREAMINFO, true, new byte[33]),
						frames),
				concat(ascii("fLaC"), block(STREAMINFO, false, streamInfo()),
						new byte[] {VORBIS_COMMENT, 0x10, 0, 0}, MARKER),
				concat(ascii("fLaC"), block(STREAMINFO, false, streamInfo()),
						block(127, true, MARKER), frames),
				concat(ascii("fLaC"), block(STREAMINFO, false, streamInfo()),
						block(VORBIS_COMMENT, false, vorbisComment(MARKER))),
				concat(ascii("fLaC"), block(STREAMINFO, false, streamInfo()),
						block(STREAMINFO, true, streamInfo()), frames),
				concat(ascii("fLaC"), block(STREAMINFO, true, streamInfo()),
						MARKER, frames)};
		for (int i = 0; i < broken.length; i++) {
			try {
				SharedMediaSanitizer.Cleaned c = sanitizer.sanitize(
						stage(broken[i]), "audio/flac", broken[i], MAX);
				fail("malformed stream " + i + " was sent"
						+ (contains(c.getData(), MARKER)
						? " with its comments" : ""));
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void aFlacStreamAttachedForAContactIsCleanedToo() throws Exception {
		assertClean("attached",
				AttachmentCreationTask.cleanAudio(taggedFlac()));
		assertClean("attached behind a tag",
				AttachmentCreationTask.cleanAudio(concat(
						id3v23(ascii("before")), taggedFlac())));
		try {
			AttachmentCreationTask.cleanAudio(concat(ascii("fLaC"),
					block(STREAMINFO, true, new byte[33]), frames()));
			fail("a malformed stream was attached");
		} catch (IOException expected) {
		}
	}
}
