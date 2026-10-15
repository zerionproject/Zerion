package com.professor.zerion.android.attachment.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Rect;

import com.professor.zerion.android.attachment.ImageScrubber;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.attachment.ImageOrientationTest.jpegWithOrientation;
import static com.professor.zerion.android.attachment.ImageScrubberTest.DHT;
import static com.professor.zerion.android.attachment.ImageScrubberTest.DQT;
import static com.professor.zerion.android.attachment.ImageScrubberTest.EOI;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SCAN;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOF;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOI;
import static com.professor.zerion.android.attachment.ImageScrubberTest.SOS;
import static com.professor.zerion.android.attachment.ImageScrubberTest.concat;
import static com.professor.zerion.android.attachment.ImageScrubberTest.skiaStyleJpeg;
import static com.professor.zerion.android.vault.utils.MetadataStripperImageTest.encodingAs;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ImageCompressorCleanOutputTest {

	private static byte[] readAll(InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		int b;
		while ((b = in.read()) != -1) out.write(b);
		return out.toByteArray();
	}

	private static ImageCompressorImpl compressor(int width, int height) {
		ImageSizeCalculator sizes = mock(ImageSizeCalculator.class);
		when(sizes.getSize(any(), anyString())).thenReturn(new Size(width,
				height, "image/jpeg"));
		return new ImageCompressorImpl(sizes);
	}

	@Test
	public void aSentImageOrAvatarCarriesNothingButTheImage()
			throws Exception {
		InputStream sent = compressor(40, 30).compressImage(
				encodingAs(skiaStyleJpeg()));
		assertArrayEquals(concat(SOI, DQT, SOF, DHT, SOS, SCAN, EOI),
				readAll(sent));
	}

	@Test
	public void anImageThatCannotBeCleanedIsNotSent() {
		byte[] broken = java.util.Arrays.copyOf(skiaStyleJpeg(), 300);
		try {
			compressor(40, 30).compressImage(encodingAs(broken));
			fail("uncleaned image sent");
		} catch (IOException expected) {
		}
	}

	@Test
	public void aSidewaysPhotoIsDecodedInStandardColoursAndSentUpright()
			throws Exception {
		List<BitmapFactory.Options> decodes = new ArrayList<>();
		byte[] sent;
		try (MockedStatic<BitmapFactory> decoder =
				mockStatic(BitmapFactory.class)) {
			decoder.when(() -> BitmapFactory.decodeStream(any(InputStream.class),
					isNull(Rect.class), any(BitmapFactory.Options.class)))
					.thenAnswer(inv -> {
						decodes.add(inv.getArgument(2));
						return Bitmap.createBitmap(40, 30,
								Bitmap.Config.ARGB_8888);
					});
			sent = readAll(compressor(30, 40).compressImage(
					new ByteArrayInputStream(jpegWithOrientation(6)),
					"image/jpeg"));
		}
		assertEquals(1, decodes.size());
		assertEquals(ColorSpace.get(ColorSpace.Named.SRGB),
				decodes.get(0).inPreferredColorSpace);
		assertArrayEquals(sent, ImageScrubber.jpeg(sent));
		int frame = 2;
		while ((sent[frame + 1] & 0xFF) != 0xC0) {
			frame += 2 + (((sent[frame + 2] & 0xFF) << 8)
					| (sent[frame + 3] & 0xFF));
		}
		assertEquals("height", 40, ((sent[frame + 5] & 0xFF) << 8)
				| (sent[frame + 6] & 0xFF));
		assertEquals("width", 30, ((sent[frame + 7] & 0xFF) << 8)
				| (sent[frame + 8] & 0xFF));
	}
}
