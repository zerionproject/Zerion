package com.professor.zerion.android.attachment;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SharedMediaSanitizerDecodeBoundTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int BOUND = 4096;

	private static int decodeSampleFor(int width, int height)
			throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		byte[] jpeg = new byte[2048];
		jpeg[0] = (byte) 0xFF;
		jpeg[1] = (byte) 0xD8;
		jpeg[2] = (byte) 0xFF;
		jpeg[3] = (byte) 0xE0;
		File f = File.createTempFile("zt_bound_", ".jpg", ctx.getCacheDir());
		Files.write(f.toPath(), jpeg);
		List<Integer> samples = new ArrayList<>();
		try (MockedStatic<BitmapFactory> decoder =
				mockStatic(BitmapFactory.class)) {
			decoder.when(() -> BitmapFactory.decodeByteArray(
					any(byte[].class), anyInt(), anyInt(),
					any(BitmapFactory.Options.class))).thenAnswer(inv -> {
						BitmapFactory.Options o = inv.getArgument(3);
						if (o.inJustDecodeBounds) {
							o.outWidth = width;
							o.outHeight = height;
						} else {
							samples.add(o.inSampleSize);
						}
						return null;
					});
			try {
				new SharedMediaSanitizer(ctx).sanitize(Uri.fromFile(f),
						"image/jpeg", jpeg, 16L * 1024 * 1024);
			} catch (IOException expected) {
			}
		} finally {
			f.delete();
		}
		assertEquals("one full decode", 1, samples.size());
		return Math.max(1, samples.get(0));
	}

	private static void assertBounded(int width, int height)
			throws Exception {
		int s = decodeSampleFor(width, height);
		assertTrue(width + "x" + height + " would be decoded at "
						+ (width / s) + "x" + (height / s) + ", "
						+ ((long) (width / s) * (height / s) * 4 >> 20)
						+ " MiB",
				width / s <= BOUND && height / s <= BOUND);
	}

	@Test
	public void aVeryWideImageIsScaledDown() throws Exception {
		assertBounded(16384, 8000);
	}

	@Test
	public void aVeryTallImageIsScaledDown() throws Exception {
		assertBounded(3000, 16384);
	}

	@Test
	public void aLargeSquareImageIsScaledDown() throws Exception {
		assertBounded(16384, 16384);
	}

	@Test
	public void anOrdinaryPhotoKeepsItsSize() throws Exception {
		assertEquals(1, decodeSampleFor(4032, 3024));
		assertEquals(1, decodeSampleFor(4096, 4096));
	}
}
