package com.professor.zerion.android.attachment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SharedImageFormatsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long MAX = 50L * 1024 * 1024;
	private static final byte[] MARKER =
			"ZtPlantedGps52.3702N4.8952E".getBytes(StandardCharsets.US_ASCII);

	private Context ctx;
	private final List<File> temp = new ArrayList<>();
	private final List<Integer> samples = new ArrayList<>();

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
		samples.clear();
	}

	@After
	public void tearDown() {
		for (File f : temp) f.delete();
	}

	private Uri stage(byte[] data) throws IOException {
		File f = File.createTempFile("zt_image_", ".bin", ctx.getCacheDir());
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

	private static byte[] le(long v, int n) {
		byte[] b = new byte[n];
		for (int i = 0; i < n; i++) b[i] = (byte) (v >> (8 * i));
		return b;
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] p : parts) out.write(p, 0, p.length);
		return out.toByteArray();
	}

	static byte[] bmpWithLinkedProfile(int width, int height) {
		byte[] path = concat(("C:\\Users\\" + new String(MARKER,
				StandardCharsets.US_ASCII) + "\\profile.icc")
				.getBytes(StandardCharsets.US_ASCII), new byte[1]);
		int row = (width * 3 + 3) & ~3;
		int pixels = row * height;
		int headerEnd = 14 + 124;
		int profileOffset = 124 + pixels;
		byte[] header = concat(le(124, 4), le(width, 4), le(height, 4),
				le(1, 2), le(24, 2), le(0, 4), le(pixels, 4), le(2835, 4),
				le(2835, 4), le(0, 4), le(0, 4), new byte[16],
				le(0x4C494E4BL, 4), new byte[36], new byte[12], le(4, 4),
				le(profileOffset, 4), le(path.length, 4), le(0, 4));
		byte[] body = new byte[pixels];
		for (int i = 0; i < pixels; i++) body[i] = (byte) (i * 7);
		int size = headerEnd + pixels + path.length;
		return concat(new byte[] {'B', 'M'}, le(size, 4), le(0, 4),
				le(headerEnd, 4), header, body, path);
	}

	static byte[] avifWithExif() {
		byte[] ftyp = concat(new byte[] {0, 0, 0, 0x20}, "ftypavif"
				.getBytes(StandardCharsets.US_ASCII), new byte[4],
				"avifmif1miafMA1B".getBytes(StandardCharsets.US_ASCII));
		return concat(ftyp, new byte[200], "Exif\0\0".getBytes(
				StandardCharsets.US_ASCII), MARKER, new byte[3000]);
	}

	static byte[] largeJpeg() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(0xD8);
		byte[] comment = new byte[65533];
		System.arraycopy(MARKER, 0, comment, 10, MARKER.length);
		while (out.size() < 17 * 1024 * 1024) {
			out.write(0xFF);
			out.write(0xFE);
			out.write(0xFF);
			out.write(0xFF);
			out.write(comment, 0, comment.length);
		}
		out.write(0xFF);
		out.write(0xD9);
		return out.toByteArray();
	}

	private SharedMediaSanitizer.Cleaned share(byte[] data, String declared,
			int width, int height) throws IOException {
		Uri uri = stage(data);
		try (MockedStatic<BitmapFactory> decoder =
				mockStatic(BitmapFactory.class)) {
			decoder.when(() -> BitmapFactory.decodeByteArray(
					any(byte[].class), anyInt(), anyInt(),
					any(BitmapFactory.Options.class))).thenAnswer(inv -> {
						BitmapFactory.Options o = inv.getArgument(3);
						if (o.inJustDecodeBounds) {
							o.outWidth = width;
							o.outHeight = height;
							return null;
						}
						samples.add(o.inSampleSize);
						return Bitmap.createBitmap(32, 24,
								Bitmap.Config.ARGB_8888);
					});
			return new SharedMediaSanitizer(ctx).sanitize(uri, declared, data,
					MAX);
		}
	}

	@Test
	public void aWindowsBitmapIsSentAsAPngWithoutItsProfilePath()
			throws Exception {
		byte[] bmp = bmpWithLinkedProfile(40, 30);
		assertTrue(contains(bmp, MARKER));
		for (String declared : new String[] {"image/bmp", "image/x-ms-bmp",
				"application/octet-stream"}) {
			samples.clear();
			SharedMediaSanitizer.Cleaned c = share(bmp, declared, 40, 30);
			assertEquals(declared, "image/png", c.getMimeType());
			assertFalse(contains(c.getData(), MARKER));
			assertEquals("decoded once to be encoded again", 1,
					samples.size());
		}
	}

	@Test
	public void anAvifImageIsSentAsAJpegWithoutItsMetadata() throws Exception {
		byte[] avif = avifWithExif();
		for (String declared : new String[] {"image/avif",
				"application/octet-stream"}) {
			samples.clear();
			SharedMediaSanitizer.Cleaned c = share(avif, declared, 4000, 3000);
			assertEquals(declared, "image/jpeg", c.getMimeType());
			assertFalse(contains(c.getData(), MARKER));
			assertEquals(1, samples.size());
		}
	}

	@Test
	public void anImageFileLargerThan16MbIsScaledAndSent() throws Exception {
		byte[] jpeg = largeJpeg();
		assertTrue(jpeg.length > 16 * 1024 * 1024);
		SharedMediaSanitizer.Cleaned c = share(jpeg, "image/jpeg", 12000,
				9000);
		assertEquals("image/jpeg", c.getMimeType());
		assertFalse(contains(c.getData(), MARKER));
		assertEquals(1, samples.size());
		assertTrue("decoded at full size", samples.get(0) >= 4);
	}
}
