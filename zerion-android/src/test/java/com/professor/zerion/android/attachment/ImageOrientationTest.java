package com.professor.zerion.android.attachment;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.attachment.ImageScrubberTest.DHT;
import static com.professor.zerion.android.attachment.ImageScrubberTest.DQT;
import static com.professor.zerion.android.attachment.ImageScrubberTest.EOI;
import static com.professor.zerion.android.attachment.ImageScrubberTest.MARKER;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SCAN;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOF;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOI;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOS;
import static com.professor.zerion.android.attachment.ImageScrubberTest.ascii;
import static com.professor.zerion.android.attachment.ImageScrubberTest.bytes;
import static com.professor.zerion.android.attachment.ImageScrubberTest.cleanJpeg;
import static com.professor.zerion.android.attachment.ImageScrubberTest.cleanPng;
import static com.professor.zerion.android.attachment.ImageScrubberTest.concat;
import static com.professor.zerion.android.attachment.ImageScrubberTest.segment;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ImageOrientationTest {

	private static final int W = 4;
	private static final int H = 2;

	public static byte[] jpegWithOrientation(int orientation) {
		byte[] tiff = concat(ascii("MM"), bytes(0, 42, 0, 0, 0, 8, 0, 1, 0x01,
				0x12, 0, 3, 0, 0, 0, 1, 0, orientation, 0, 0, 0, 0, 0, 0));
		return concat(SOI, segment(0xE1, concat(ascii("Exif\0\0"), tiff)),
				DQT, SOF, DHT, SOS, SCAN, EOI);
	}

	@Test
	public void everyOrientationIsReadFromTheOriginalBytes() {
		for (int o = 1; o <= 8; o++) {
			byte[] jpeg = jpegWithOrientation(o);
			assertEquals(o, ImageOrientation.of(jpeg));
			assertEquals(o, ImageOrientation.of(new ByteArrayInputStream(
					jpeg)));
		}
	}

	@Test
	public void anImageWithoutAReadableOrientationIsUpright() {
		assertEquals(1, ImageOrientation.of(cleanJpeg()));
		assertEquals(1, ImageOrientation.of(cleanPng()));
		assertEquals(1, ImageOrientation.of(MARKER));
		assertEquals(1, ImageOrientation.of(new byte[0]));
		byte[] cut = jpegWithOrientation(6);
		assertEquals(1, ImageOrientation.of(java.util.Arrays.copyOf(cut, 12)));
	}

	@Test
	public void readingTheOrientationLeavesTheStreamReadable()
			throws Exception {
		byte[] jpeg = jpegWithOrientation(8);
		InputStream in = new BufferedInputStream(new ByteArrayInputStream(
				jpeg));
		in.mark(ImageOrientation.READ_LIMIT);
		assertEquals(8, ImageOrientation.of(in));
		in.reset();
		ByteArrayOutputStream all = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1) all.write(b);
		assertArrayEquals(jpeg, all.toByteArray());
	}

	private static float[] placed(int orientation) {
		Matrix m = ImageOrientation.matrix(orientation);
		RectF bounds = new RectF(0, 0, W, H);
		m.mapRect(bounds);
		float[] corners = {0, 0, W, 0, 0, H};
		m.mapPoints(corners);
		for (int i = 0; i < corners.length; i += 2) {
			corners[i] -= bounds.left;
			corners[i + 1] -= bounds.top;
		}
		return new float[] {bounds.width(), bounds.height(), corners[0],
				corners[1], corners[2], corners[3], corners[4], corners[5]};
	}

	@Test
	public void eachOrientationPutsTheTopLeftCornerWhereItBelongs() {
		assertNull(ImageOrientation.matrix(0));
		assertNull(ImageOrientation.matrix(1));
		assertNull(ImageOrientation.matrix(9));
		float[][] expected = {
				{W, H, W, 0, 0, 0, W, H},
				{W, H, W, H, 0, H, W, 0},
				{W, H, 0, H, W, H, 0, 0},
				{H, W, 0, 0, 0, W, H, 0},
				{H, W, H, 0, H, W, 0, 0},
				{H, W, H, W, H, 0, 0, W},
				{H, W, 0, W, 0, 0, H, W}};
		for (int o = 2; o <= 8; o++) {
			assertArrayEquals("orientation " + o, expected[o - 2], placed(o),
					0.001f);
		}
	}

	@Test
	public void aTurnedPhotoIsRedrawnUpright() {
		Bitmap upright = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
		assertSame(upright, ImageOrientation.upright(upright, 1));
		assertSame(upright, ImageOrientation.upright(upright, 0));
		Bitmap sideways = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888);
		Bitmap turned = ImageOrientation.upright(sideways, 6);
		assertEquals(30, turned.getWidth());
		assertEquals(40, turned.getHeight());
		assertTrue(sideways.isRecycled());
		Bitmap mirrored = ImageOrientation.upright(Bitmap.createBitmap(40, 30,
				Bitmap.Config.ARGB_8888), 2);
		assertEquals(40, mirrored.getWidth());
		assertEquals(30, mirrored.getHeight());
	}
}
