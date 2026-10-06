package com.professor.zerion.android.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

@RunWith(AndroidJUnit4.class)
public class SharedMediaSanitizerDeviceTest {

	private static final long MAX = 50L * 1024 * 1024;
	private static final String MAKE = "ZtPlantedMake";
	private static final String MODEL = "ZtPlantedModel";
	private static final String AUTHOR = "ZtPlantedAuthor";

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

	private File tempFile(String suffix) throws IOException {
		File f = File.createTempFile("zt_sanitize_", suffix, ctx.getCacheDir());
		temp.add(f);
		return f;
	}

	private static Bitmap sample() {
		Bitmap b = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
		for (int x = 0; x < 96; x++) {
			for (int y = 0; y < 64; y++) {
				b.setPixel(x, y, Color.rgb(x * 2, y * 3, 120));
			}
		}
		return b;
	}

	private File jpegWithGps() throws IOException {
		File f = tempFile(".jpg");
		try (FileOutputStream out = new FileOutputStream(f)) {
			assertTrue(sample().compress(Bitmap.CompressFormat.JPEG, 90, out));
		}
		ExifInterface exif = new ExifInterface(f.getAbsolutePath());
		exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "52/1,22/1,12/1");
		exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N");
		exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "4/1,53/1,24/1");
		exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E");
		exif.setAttribute(ExifInterface.TAG_MAKE, MAKE);
		exif.setAttribute(ExifInterface.TAG_MODEL, MODEL);
		exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
				"2026:09:30 10:11:12");
		exif.saveAttributes();
		return f;
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

	private static void assertNoExifIdentity(byte[] jpeg) throws IOException {
		ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
		assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE));
		assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE));
		assertNull(exif.getAttribute(ExifInterface.TAG_MAKE));
		assertNull(exif.getAttribute(ExifInterface.TAG_MODEL));
		assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL));
		assertFalse(contains(jpeg, MAKE.getBytes(StandardCharsets.US_ASCII)));
		assertFalse(contains(jpeg, MODEL.getBytes(StandardCharsets.US_ASCII)));
	}

	@Test
	public void aCameraJpegLosesGpsAndDeviceIdentity() throws Exception {
		File f = jpegWithGps();
		byte[] raw = Files.readAllBytes(f.toPath());
		ExifInterface planted = new ExifInterface(new ByteArrayInputStream(raw));
		assertNotNull("GPS planted", planted.getAttribute(
				ExifInterface.TAG_GPS_LATITUDE));
		assertEquals(MAKE, planted.getAttribute(ExifInterface.TAG_MAKE));

		SharedMediaSanitizer.Cleaned c =
				sanitizer.sanitize(Uri.fromFile(f), "image/jpeg", raw, MAX);
		assertEquals("image/jpeg", c.getMimeType());
		assertNoExifIdentity(c.getData());
		Bitmap decoded = BitmapFactory.decodeByteArray(c.getData(), 0,
				c.getData().length);
		assertNotNull(decoded);
		assertEquals(96, decoded.getWidth());
		assertEquals(64, decoded.getHeight());
	}

	@Test
	public void aJpegReportedWithAnotherTypeIsStillCleaned() throws Exception {
		File f = jpegWithGps();
		byte[] raw = Files.readAllBytes(f.toPath());
		for (String declared : new String[] {"application/octet-stream",
				"text/plain", "application/pdf"}) {
			SharedMediaSanitizer.Cleaned c =
					sanitizer.sanitize(Uri.fromFile(f), declared, raw, MAX);
			assertEquals(declared, "image/jpeg", c.getMimeType());
			assertNoExifIdentity(c.getData());
		}
	}

	@Test
	public void aPngLosesItsTextChunks() throws Exception {
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		assertTrue(sample().compress(Bitmap.CompressFormat.PNG, 100, png));
		byte[] withText = insertTextChunk(png.toByteArray(), "Author", AUTHOR);
		assertTrue(contains(withText, AUTHOR.getBytes(StandardCharsets.US_ASCII)));
		assertNotNull(BitmapFactory.decodeByteArray(withText, 0,
				withText.length));
		File f = tempFile(".png");
		Files.write(f.toPath(), withText);

		SharedMediaSanitizer.Cleaned c =
				sanitizer.sanitize(Uri.fromFile(f), "image/png", withText, MAX);
		assertEquals("image/png", c.getMimeType());
		assertFalse(contains(c.getData(),
				AUTHOR.getBytes(StandardCharsets.US_ASCII)));
		assertFalse(contains(c.getData(),
				"tEXt".getBytes(StandardCharsets.US_ASCII)));
	}

	@Test
	public void anImageThatCannotBeCleanedIsRefused() throws Exception {
		byte[] fakeJpeg = new byte[4096];
		fakeJpeg[0] = (byte) 0xFF;
		fakeJpeg[1] = (byte) 0xD8;
		fakeJpeg[2] = (byte) 0xFF;
		Arrays.fill(fakeJpeg, 3, fakeJpeg.length, (byte) 0x41);
		byte[] tiff = new byte[4096];
		tiff[0] = 'I';
		tiff[1] = 'I';
		tiff[2] = 42;
		File f = tempFile(".bin");
		Files.write(f.toPath(), fakeJpeg);
		for (Object[] c : new Object[][] {{fakeJpeg, "image/jpeg"},
				{tiff, "image/tiff"}, {fakeJpeg, "application/octet-stream"}}) {
			try {
				sanitizer.sanitize(Uri.fromFile(f), (String) c[1],
						(byte[]) c[0], MAX);
				fail((String) c[1]);
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void aVideoLosesItsLocationAtom() throws Exception {
		File f = tempFile(".mp4");
		writeMp4WithLocation(f);
		byte[] raw = Files.readAllBytes(f.toPath());
		assertNotNull("location planted", location(f));
		byte[] xyz = {(byte) 0xA9, 'x', 'y', 'z'};
		assertTrue(contains(raw, xyz));

		for (String declared : new String[] {"video/mp4",
				"application/octet-stream"}) {
			SharedMediaSanitizer.Cleaned c =
					sanitizer.sanitize(Uri.fromFile(f), declared, raw, MAX);
			assertEquals("video/mp4", c.getMimeType());
			assertFalse(contains(c.getData(), xyz));
			File out = tempFile(".mp4");
			Files.write(out.toPath(), c.getData());
			assertNull(location(out));
			MediaExtractor ex = new MediaExtractor();
			try {
				ex.setDataSource(out.getAbsolutePath());
				assertEquals(1, ex.getTrackCount());
			} finally {
				ex.release();
			}
		}
	}

	@Test
	public void mediaThatCannotBeRemuxedIsRefused() throws Exception {
		byte[] fake = new byte[4096];
		fake[4] = 'f';
		fake[5] = 't';
		fake[6] = 'y';
		fake[7] = 'p';
		fake[8] = 'i';
		fake[9] = 's';
		fake[10] = 'o';
		fake[11] = 'm';
		File f = tempFile(".mp4");
		Files.write(f.toPath(), fake);
		for (String declared : new String[] {"video/mp4",
				"application/octet-stream"}) {
			try {
				sanitizer.sanitize(Uri.fromFile(f), declared, fake, MAX);
				fail(declared);
			} catch (IOException expected) {
			}
		}
	}

	private static byte[] withDimensions(byte[] jpeg, int width, int height) {
		byte[] out = jpeg.clone();
		for (int i = 2; i + 9 < out.length; i++) {
			int marker = out[i + 1] & 0xFF;
			if ((out[i] & 0xFF) == 0xFF && (marker == 0xC0 || marker == 0xC2)) {
				out[i + 5] = (byte) (height >> 8);
				out[i + 6] = (byte) height;
				out[i + 7] = (byte) (width >> 8);
				out[i + 8] = (byte) width;
				return out;
			}
		}
		throw new AssertionError("no frame header");
	}

	@Test
	public void aVeryWideImageIsScaledDownInsteadOfCrashing() throws Exception {
		ByteArrayOutputStream small = new ByteArrayOutputStream();
		assertTrue(sample().compress(Bitmap.CompressFormat.JPEG, 90, small));
		byte[] wide = withDimensions(small.toByteArray(), 16384, 8000);
		BitmapFactory.Options probe = new BitmapFactory.Options();
		probe.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(wide, 0, wide.length, probe);
		assertEquals(16384, probe.outWidth);
		assertEquals(8000, probe.outHeight);
		File f = tempFile(".jpg");
		Files.write(f.toPath(), wide);
		SharedMediaSanitizer.Cleaned c;
		try {
			c = sanitizer.sanitize(Uri.fromFile(f), "image/jpeg", wide, MAX);
		} catch (IOException refused) {
			return;
		}
		BitmapFactory.Options out = new BitmapFactory.Options();
		out.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(c.getData(), 0, c.getData().length, out);
		assertTrue(out.outWidth + "x" + out.outHeight,
				out.outWidth <= 4096 && out.outHeight <= 4096);
	}

	@Test
	public void quickTimeWithoutAFileTypeBoxLosesItsLocation() throws Exception {
		File f = tempFile(".mp4");
		writeMp4WithLocation(f);
		byte[] raw = Files.readAllBytes(f.toPath());
		int ftyp = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
				| ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
		assertEquals('f', raw[4]);
		byte[] headless = Arrays.copyOfRange(raw, ftyp, raw.length);
		File g = tempFile(".bin");
		Files.write(g.toPath(), headless);
		byte[] xyz = {(byte) 0xA9, 'x', 'y', 'z'};
		assertTrue(contains(headless, xyz));
		SharedMediaSanitizer.Cleaned c;
		try {
			c = sanitizer.sanitize(Uri.fromFile(g), "application/octet-stream",
					headless, MAX);
		} catch (IOException refused) {
			return;
		}
		assertEquals("video/mp4", c.getMimeType());
		assertFalse(contains(c.getData(), xyz));
	}

	@Test
	public void formatsThatCannotBeCleanedAreRefusedWhateverTheirType()
			throws Exception {
		byte[][] magics = {{'I', 'I', 42, 0}, {'M', 'M', 0, 42},
				{(byte) 0xFF, 0x0A},
				{0, 0, 0, 0x0C, 'J', 'X', 'L', ' ', 0x0D, 0x0A, (byte) 0x87,
						0x0A}};
		for (byte[] magic : magics) {
			byte[] data = new byte[4096];
			System.arraycopy(magic, 0, data, 0, magic.length);
			File f = tempFile(".bin");
			Files.write(f.toPath(), data);
			try {
				sanitizer.sanitize(Uri.fromFile(f), "application/octet-stream",
						data, MAX);
				fail(Arrays.toString(magic));
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void otherFilesPassThroughUnchanged() throws Exception {
		byte[] text = "plain notes".getBytes(StandardCharsets.UTF_8);
		File f = tempFile(".txt");
		Files.write(f.toPath(), text);
		SharedMediaSanitizer.Cleaned c =
				sanitizer.sanitize(Uri.fromFile(f), "text/plain", text, MAX);
		assertEquals("text/plain", c.getMimeType());
		assertTrue(Arrays.equals(text, c.getData()));
	}

	private static String location(File f) {
		MediaMetadataRetriever r = new MediaMetadataRetriever();
		try {
			r.setDataSource(f.getAbsolutePath());
			return r.extractMetadata(
					MediaMetadataRetriever.METADATA_KEY_LOCATION);
		} finally {
			try {
				r.release();
			} catch (IOException ignored) {
			}
		}
	}

	private static byte[] insertTextChunk(byte[] png, String key, String value) {
		int ihdrEnd = 8 + 4 + 4 + 13 + 4;
		byte[] data = (key + "\0" + value).getBytes(StandardCharsets.ISO_8859_1);
		byte[] type = "tEXt".getBytes(StandardCharsets.US_ASCII);
		CRC32 crc = new CRC32();
		crc.update(type);
		crc.update(data);
		ByteBuffer chunk = ByteBuffer.allocate(12 + data.length);
		chunk.putInt(data.length).put(type).put(data)
				.putInt((int) crc.getValue());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(png, 0, ihdrEnd);
		out.write(chunk.array(), 0, chunk.capacity());
		out.write(png, ihdrEnd, png.length - ihdrEnd);
		return out.toByteArray();
	}

	private static void writeMp4WithLocation(File f) throws IOException {
		int w = 320, h = 240;
		MediaFormat fmt = MediaFormat.createVideoFormat("video/avc", w, h);
		fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
				MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
		fmt.setInteger(MediaFormat.KEY_BIT_RATE, 250_000);
		fmt.setInteger(MediaFormat.KEY_FRAME_RATE, 10);
		fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
		MediaCodec enc = MediaCodec.createEncoderByType("video/avc");
		MediaMuxer mux = new MediaMuxer(f.getAbsolutePath(),
				MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
		mux.setLocation(52.3702f, 4.8952f);
		enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		enc.start();
		byte[] frame = new byte[w * h * 3 / 2];
		Arrays.fill(frame, (byte) 0x80);
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		int track = -1;
		int fed = 0;
		int frames = 20;
		int written = 0;
		boolean inputDone = false;
		boolean outputDone = false;
		long deadline = System.currentTimeMillis() + 30_000;
		try {
			while (!outputDone && System.currentTimeMillis() < deadline) {
				if (!inputDone) {
					int in = enc.dequeueInputBuffer(10_000);
					if (in >= 0) {
						ByteBuffer b = enc.getInputBuffer(in);
						b.clear();
						long pts = fed * 100_000L;
						if (fed == frames) {
							enc.queueInputBuffer(in, 0, 0, pts,
									MediaCodec.BUFFER_FLAG_END_OF_STREAM);
							inputDone = true;
						} else {
							int n = Math.min(b.remaining(), frame.length);
							b.put(frame, 0, n);
							enc.queueInputBuffer(in, 0, n, pts, 0);
							fed++;
						}
					}
				}
				int out = enc.dequeueOutputBuffer(info, 10_000);
				if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					track = mux.addTrack(enc.getOutputFormat());
					mux.start();
				} else if (out >= 0) {
					ByteBuffer ob = enc.getOutputBuffer(out);
					boolean config = (info.flags
							& MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
					if (!config && info.size > 0 && track >= 0) {
						ob.position(info.offset);
						ob.limit(info.offset + info.size);
						mux.writeSampleData(track, ob, info);
						written++;
					}
					enc.releaseOutputBuffer(out, false);
					if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)
							!= 0) {
						outputDone = true;
					}
				}
			}
		} finally {
			enc.stop();
			enc.release();
			if (track >= 0) mux.stop();
			mux.release();
		}
		assertTrue("encoder produced samples", written > 0);
	}
}
