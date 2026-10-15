package com.professor.zerion.android.attachment;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

@RunWith(AndroidJUnit4.class)
public class CleanImageEncodingDeviceTest {

	private static final int RED = Color.rgb(210, 30, 30);
	private static final int BLUE = Color.rgb(30, 30, 210);

	private static Bitmap leftRedRightBlue() {
		Bitmap b = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
		for (int y = 0; y < 64; y++) {
			for (int x = 0; x < 96; x++) b.setPixel(x, y, x < 48 ? RED : BLUE);
		}
		return b;
	}

	private static byte[] encode(Bitmap b, Bitmap.CompressFormat format) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(b.compress(format, 90, out));
		return out.toByteArray();
	}

	private static boolean reddish(int pixel) {
		return Color.red(pixel) > 150 && Color.blue(pixel) < 100;
	}

	@Test
	public void theEncoderOutputKeepsNothingButTheImage() throws Exception {
		Bitmap b = leftRedRightBlue();
		Bitmap.CompressFormat[] formats = {Bitmap.CompressFormat.JPEG,
				Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP};
		for (Bitmap.CompressFormat format : formats) {
			byte[] raw = encode(b, format);
			byte[] clean = ImageScrubber.scrub(raw);
			assertArrayEquals(format.name(), clean, ImageScrubber.scrub(clean));
			Bitmap decoded = BitmapFactory.decodeByteArray(clean, 0,
					clean.length);
			assertNotNull(format.name(), decoded);
			assertEquals(96, decoded.getWidth());
			assertEquals(64, decoded.getHeight());
			assertTrue(format.name(), reddish(decoded.getPixel(10, 32)));
			assertTrue(format.name(), !reddish(decoded.getPixel(85, 32)));
		}
		byte[] jpeg = encode(b, Bitmap.CompressFormat.JPEG);
		assertTrue("the encoder adds segments of its own",
				ImageScrubber.jpeg(jpeg).length < jpeg.length);
	}

	@Test
	public void aSidewaysPhotoIsSentUprightAndBare() throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		File f = File.createTempFile("zt_turned_", ".jpg", ctx.getCacheDir());
		try {
			try (FileOutputStream out = new FileOutputStream(f)) {
				out.write(encode(leftRedRightBlue(),
						Bitmap.CompressFormat.JPEG));
			}
			ExifInterface exif = new ExifInterface(f.getAbsolutePath());
			exif.setAttribute(ExifInterface.TAG_ORIENTATION,
					String.valueOf(ExifInterface.ORIENTATION_ROTATE_90));
			exif.saveAttributes();
			byte[] photo = Files.readAllBytes(f.toPath());
			SharedMediaSanitizer.Cleaned c = new SharedMediaSanitizer(ctx)
					.sanitize(Uri.fromFile(f), "image/jpeg", photo,
							16L * 1024 * 1024);
			byte[] sent = c.getData();
			assertArrayEquals(sent, ImageScrubber.jpeg(sent));
			Bitmap decoded = BitmapFactory.decodeByteArray(sent, 0,
					sent.length);
			assertNotNull(decoded);
			assertEquals(64, decoded.getWidth());
			assertEquals(96, decoded.getHeight());
			assertTrue(reddish(decoded.getPixel(32, 10)));
			assertTrue(!reddish(decoded.getPixel(32, 85)));
		} finally {
			f.delete();
		}
	}
}
