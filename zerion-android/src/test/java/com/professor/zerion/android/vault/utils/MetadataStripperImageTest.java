package com.professor.zerion.android.vault.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.professor.zerion.android.attachment.ImageScrubber;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.attachment.ImageOrientationTest.jpegWithOrientation;
import static com.professor.zerion.android.attachment.ImageScrubberTest.ALPH;
import static com.professor.zerion.android.attachment.ImageScrubberTest.DHT;
import static com.professor.zerion.android.attachment.ImageScrubberTest.DQT;
import static com.professor.zerion.android.attachment.ImageScrubberTest.EOI;
import static com.professor.zerion.android.attachment.ImageScrubberTest.MARKER;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SCAN;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOF;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOI;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOS;
import static com.professor.zerion.android.attachment.ImageScrubberTest.VP8;
import static com.professor.zerion.android.attachment.ImageScrubberTest.ascii;
import static com.professor.zerion.android.attachment.ImageScrubberTest.bytes;
import static com.professor.zerion.android.attachment.ImageScrubberTest.chunk;
import static com.professor.zerion.android.attachment.ImageScrubberTest.cleanPng;
import static com.professor.zerion.android.attachment.ImageScrubberTest.concat;
import static com.professor.zerion.android.attachment.ImageScrubberTest.pngWith;
import static com.professor.zerion.android.attachment.ImageScrubberTest.riffChunk;
import static com.professor.zerion.android.attachment.ImageScrubberTest.skiaStyleJpeg;
import static com.professor.zerion.android.attachment.ImageScrubberTest.vp8x;
import static com.professor.zerion.android.attachment.ImageScrubberTest.webp;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MetadataStripperImageTest {

	static {
		TestAndroidKeyStore.register();
	}

	public static Bitmap encodingAs(byte[] output) {
		Bitmap bitmap = mock(Bitmap.class);
		when(bitmap.compress(any(), anyInt(), any())).thenAnswer(inv -> {
			((OutputStream) inv.getArgument(2)).write(output);
			return true;
		});
		return bitmap;
	}

	private interface Strip {
		byte[] run(MetadataStripper stripper) throws IOException;
	}

	private static byte[] decodedAs(Bitmap bitmap, Strip strip,
			List<BitmapFactory.Options> decodes) throws IOException {
		try (MockedStatic<BitmapFactory> decoder =
				mockStatic(BitmapFactory.class)) {
			decoder.when(() -> BitmapFactory.decodeByteArray(
					any(byte[].class), anyInt(), anyInt(),
					any(BitmapFactory.Options.class))).thenAnswer(inv -> {
						BitmapFactory.Options o = inv.getArgument(3);
						if (o.inJustDecodeBounds) {
							o.outWidth = 40;
							o.outHeight = 30;
							return null;
						}
						decodes.add(o);
						return bitmap;
					});
			return strip.run(new MetadataStripper(
					ApplicationProvider.getApplicationContext()));
		}
	}

	private static byte[] decodedAs(Bitmap bitmap, Strip strip)
			throws IOException {
		return decodedAs(bitmap, strip, new ArrayList<>());
	}

	@Test
	public void whatTheEncoderWritesBesideTheImageIsNeverSent()
			throws Exception {
		byte[] photo = jpegWithOrientation(1);
		byte[] cleanJpeg = concat(SOI, DQT, SOF, DHT, SOS, SCAN, EOI);
		assertArrayEquals(cleanJpeg, decodedAs(encodingAs(skiaStyleJpeg()),
				s -> s.stripImageMetadataOrThrow(photo, "image/jpeg")));
		assertArrayEquals(cleanJpeg, decodedAs(encodingAs(skiaStyleJpeg()),
				s -> s.stripMetadata(photo, "image/jpeg")));
		byte[] png = pngWith(chunk("iCCP", concat(ascii("Skia\0\0"),
				MARKER)), chunk("sRGB", bytes(0)));
		assertArrayEquals(cleanPng(), decodedAs(encodingAs(png),
				s -> s.stripImageMetadataOrThrow(photo, "image/png")));
		byte[] webp = webp(vp8x(0x30, 0), riffChunk("ICCP", MARKER), ALPH,
				VP8);
		assertArrayEquals(webp(vp8x(0x10, 0), ALPH, VP8), decodedAs(
				encodingAs(webp), s -> s.stripImageMetadataOrThrow(photo,
						"image/webp")));
	}

	@Test
	public void encoderOutputThatCannotBeCleanedIsNeverSent()
			throws Exception {
		byte[] photo = jpegWithOrientation(1);
		byte[] broken = java.util.Arrays.copyOf(skiaStyleJpeg(), 300);
		try {
			decodedAs(encodingAs(broken),
					s -> s.stripImageMetadataOrThrow(photo, "image/jpeg"));
			fail("uncleaned image accepted");
		} catch (IOException expected) {
		}
		assertArrayEquals(photo, decodedAs(encodingAs(broken),
				s -> s.stripMetadata(photo.clone(), "image/jpeg")));
	}

	@Test
	public void imagesAreDecodedInStandardColours() throws Exception {
		List<BitmapFactory.Options> decodes = new ArrayList<>();
		decodedAs(encodingAs(skiaStyleJpeg()), s -> s.stripImageMetadataOrThrow(
				jpegWithOrientation(1), "image/jpeg"), decodes);
		assertEquals(1, decodes.size());
		assertEquals(android.graphics.ColorSpace.get(
				android.graphics.ColorSpace.Named.SRGB),
				decodes.get(0).inPreferredColorSpace);
	}

	@Test
	public void aSidewaysPhotoIsSentUprightAndBare() throws Exception {
		byte[] out = decodedAs(Bitmap.createBitmap(40, 30,
						Bitmap.Config.ARGB_8888),
				s -> s.stripImageMetadataOrThrow(jpegWithOrientation(6),
						"image/jpeg"));
		int[] size = frameSize(out);
		assertEquals("width", 30, size[0]);
		assertEquals("height", 40, size[1]);
		assertArrayEquals(out, ImageScrubber.jpeg(out));
	}

	private static int[] frameSize(byte[] jpeg) {
		int p = 2;
		while (p + 4 <= jpeg.length) {
			int marker = jpeg[p + 1] & 0xFF;
			int length = ((jpeg[p + 2] & 0xFF) << 8) | (jpeg[p + 3] & 0xFF);
			assertTrue("metadata segment " + Integer.toHexString(marker),
					marker < 0xE0 || (marker > 0xEF && marker != 0xFE));
			if (marker == 0xC0 || marker == 0xC2) {
				return new int[] {((jpeg[p + 7] & 0xFF) << 8)
						| (jpeg[p + 8] & 0xFF), ((jpeg[p + 5] & 0xFF) << 8)
						| (jpeg[p + 6] & 0xFF)};
			}
			p += 2 + length;
		}
		throw new AssertionError("no frame");
	}
}
