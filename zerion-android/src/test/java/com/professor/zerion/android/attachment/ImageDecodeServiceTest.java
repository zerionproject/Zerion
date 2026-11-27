package com.professor.zerion.android.attachment;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.professor.zerion.android.attachment.media.Size;
import com.professor.zerion.android.util.ReceivedImageGate;
import com.professor.zerion.android.util.SafeImageDecoder;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ImageDecodeServiceTest {

	@After
	public void restoreTheDecoder() {
		ReceivedImageGate.useDecoder(SafeImageDecoder::decode);
	}

	private static byte[] jpeg(int width, int height, int orientation) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(0xFF);
		out.write(0xD8);
		if (orientation != 0) {
			byte[] exif = {(byte) 0xFF, (byte) 0xE1, 0, 0x22, 'E', 'x', 'i',
					'f', 0, 0, 'I', 'I', 0x2A, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01,
					3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0, 0, 0, 0, 0};
			out.write(exif, 0, exif.length);
		}
		byte[] frame = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xC0, 0,
				0x11, 8, (byte) (height >> 8), (byte) height,
				(byte) (width >> 8), (byte) width, 3}, 19);
		out.write(frame, 0, frame.length);
		byte[] scan = Arrays.copyOf(new byte[] {(byte) 0xFF, (byte) 0xDA, 0,
				0x0C, 3}, 30);
		out.write(scan, 0, scan.length);
		return out.toByteArray();
	}

	private static byte[] jpeg(int width, int height) {
		return jpeg(width, height, 0);
	}

	@Test
	public void theSampleSizeKeepsTheLongestSideWithinTheRequest() {
		assertEquals(Arrays.asList(1, 1, 2, 4, 4, 8, 16), Arrays.asList(
				ImageDecodeService.sampleSize(800, 600, 1024),
				ImageDecodeService.sampleSize(1024, 1024, 1024),
				ImageDecodeService.sampleSize(1025, 10, 1024),
				ImageDecodeService.sampleSize(1025, 10, 512),
				ImageDecodeService.sampleSize(4000, 3000, 1024),
				ImageDecodeService.sampleSize(4000, 3000, 512),
				ImageDecodeService.sampleSize(16384, 16384, 1024)));
		for (int width : new int[] {1025, 1441, 2049, 2561, 4097, 5123,
				8193, 16384}) {
			for (int side : new int[] {64, 360, 512, 720, 1024, 1280, 4096}) {
				int sample = ImageDecodeService.sampleSize(width, width, side);
				assertTrue(width + " in " + side,
						(width + sample - 1) / sample <= side);
				int strip = ImageDecodeService.sampleSize(width, 3, side);
				assertTrue(width + " strip in " + side, strip <= 2
						|| (width + strip / 2 - 1) / (strip / 2) > 4096);
				assertTrue(width + " strip in " + side,
						(width + strip - 1) / strip <= 4096);
			}
		}
		assertEquals(Arrays.asList(1, 4096, 4096), Arrays.asList(
				ImageDecodeService.side(-5), ImageDecodeService.side(4096),
				ImageDecodeService.side(Integer.MAX_VALUE)));
	}

	@Test
	public void anAnswerMustHaveTheSizeTheRequestLeadsTo() {
		assertTrue(ImageDecodeService.matches(500, 375, 4000, 3000, 8));
		assertTrue(ImageDecodeService.matches(513, 350, 1025, 700, 2));
		assertTrue(ImageDecodeService.matches(512, 350, 1025, 700, 2));
		assertTrue(ImageDecodeService.matches(1, 1, 3, 1, 4));
		assertFalse(ImageDecodeService.matches(514, 350, 1025, 700, 2));
		assertFalse(ImageDecodeService.matches(511, 350, 1025, 700, 2));
		assertFalse(ImageDecodeService.matches(4000, 3000, 4000, 3000, 8));
		assertFalse(ImageDecodeService.matches(4096, 4096, 64, 64, 1));
		assertFalse(ImageDecodeService.matches(375, 500, 4000, 3000, 8));
		assertFalse(ImageDecodeService.matches(0, 0, 4000, 3000, 8));
	}

	@Test
	public void theServiceChecksFormatAndSizeAgainBeforeItDecodes() {
		byte[] heic = Arrays.copyOf(new byte[] {0, 0, 0, 0x18, 'f', 't', 'y',
				'p', 'h', 'e', 'i', 'c'}, 64);
		try (MockedStatic<BitmapFactory> bf = mockStatic(BitmapFactory.class)) {
			assertNull(ImageDecodeService.pixels(heic, 512));
			assertNull(ImageDecodeService.pixels(jpeg(8000, 6000), 512));
			assertNull(ImageDecodeService.pixels(jpeg(0, 600), 512));
			assertNull(ImageDecodeService.pixels(jpeg(20000, 10), 512));
			bf.verify(() -> BitmapFactory.decodeByteArray(any(byte[].class),
					anyInt(), anyInt(), any(BitmapFactory.Options.class)),
					never());
		}
	}

	@Test
	public void theServiceDecodesAtTheRequestedSizeAndAnswersNothingElse() {
		Bitmap small = Bitmap.createBitmap(500, 375, Bitmap.Config.ARGB_8888);
		Bitmap large = Bitmap.createBitmap(2000, 1500,
				Bitmap.Config.ARGB_8888);
		int[] sample = new int[1];
		Bitmap[] answer = {small};
		try (MockedStatic<BitmapFactory> bf = mockStatic(BitmapFactory.class)) {
			bf.when(() -> BitmapFactory.decodeByteArray(any(byte[].class),
					anyInt(), anyInt(), any(BitmapFactory.Options.class)))
					.thenAnswer(inv -> {
						BitmapFactory.Options o = inv.getArgument(3);
						assertFalse(o.inJustDecodeBounds);
						sample[0] = o.inSampleSize;
						return answer[0];
					});
			assertSame(small,
					ImageDecodeService.pixels(jpeg(4000, 3000), 512));
			assertEquals(8, sample[0]);
			answer[0] = large;
			assertNull(ImageDecodeService.pixels(jpeg(4000, 3000), 512));
			assertSame(large,
					ImageDecodeService.pixels(jpeg(2000, 1500), 100_000));
			assertEquals(1, sample[0]);
			answer[0] = null;
			assertNull(ImageDecodeService.pixels(jpeg(2000, 1500), 4096));
		}
	}

	@Test
	public void theClientRefusesToBlockTheMainThread() {
		IsolatedImageDecoder decoder = new IsolatedImageDecoder(
				ApplicationProvider.getApplicationContext());
		assertNull(decoder.decode(jpeg(800, 600), 512));
		assertNull(decoder.decodeAlone(jpeg(800, 600), 512));
		decoder.release();
	}

	private static String measured(byte[] image) throws IOException {
		Size size = AttachmentRetrieverImpl.measured(
				new ByteArrayInputStream(image));
		return size.getWidth() + "x" + size.getHeight() + " "
				+ size.getMimeType();
	}

	@Test
	public void aBubbleIsMeasuredFromTheHeaderAndTurnedTheWayItIsShown()
			throws IOException {
		assertEquals("800x600 image/jpeg", measured(jpeg(800, 600)));
		assertEquals("800x600 image/jpeg", measured(jpeg(800, 600, 1)));
		assertEquals("800x600 image/jpeg", measured(jpeg(800, 600, 3)));
		for (int turned : new int[] {5, 6, 7, 8}) {
			assertEquals("600x800 image/jpeg",
					measured(jpeg(800, 600, turned)));
		}
		byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 3, 0, 2, 0, 0, 0, 0, 0x2C,
				0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3B};
		assertEquals("3x2 image/gif", measured(gif));
		try {
			measured(jpeg(9000, 9000));
			fail("an image over the pixel limit was measured");
		} catch (IOException expected) {
			assertEquals("image dimensions not accepted",
					expected.getMessage());
		}
	}
}
