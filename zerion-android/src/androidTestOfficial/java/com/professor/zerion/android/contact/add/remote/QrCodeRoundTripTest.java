package com.professor.zerion.android.contact.add.remote;

import android.graphics.Bitmap;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
public class QrCodeRoundTripTest {

	private static final String LINK =
			"zerion://WXFP2DT4ABCDEFGHIJKLMNOPQRSTUVWXYZ234567ABCDEFG";

	@Test
	public void encodeThenDecode() {
		Bitmap bmp = QrCodeUtils.generateQrCode(LINK);
		assertNotNull("encode produced no bitmap", bmp);
		byte[] luma = toLuma(bmp);
		String decoded = QrCodeUtils.decodeQrFromYuv(luma, bmp.getWidth(),
				bmp.getHeight(), 0, 0, 0);
		assertEquals(LINK, decoded);
	}

	@Test
	public void decodeSurvivesRowStridePadding() {
		Bitmap bmp = QrCodeUtils.generateQrCode(LINK);
		assertNotNull(bmp);
		int w = bmp.getWidth();
		int h = bmp.getHeight();
		byte[] luma = toLuma(bmp);

		int rowStride = w + 16;
		byte[] padded = new byte[rowStride * (h - 1) + w];
		for (int r = 0; r < h; r++) {
			System.arraycopy(luma, r * w, padded, r * rowStride, w);
		}
		byte[] destrided = deStride(padded, w, h, rowStride);
		String decoded = QrCodeUtils.decodeQrFromYuv(destrided, w, h, 0, 0, 0);
		assertEquals(LINK, decoded);
	}

	private static byte[] deStride(byte[] src, int width, int height,
			int rowStride) {
		byte[] out = new byte[width * height];
		byte[] row = new byte[rowStride];
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(src);
		for (int r = 0; r < height; r++) {
			int toRead = Math.min(rowStride, buf.remaining());
			if (toRead <= 0) break;
			buf.get(row, 0, toRead);
			System.arraycopy(row, 0, out, r * width, Math.min(width, toRead));
		}
		return out;
	}

	private static byte[] toLuma(Bitmap bmp) {
		int w = bmp.getWidth();
		int h = bmp.getHeight();
		int[] pixels = new int[w * h];
		bmp.getPixels(pixels, 0, w, 0, 0, w, h);
		byte[] luma = new byte[w * h];
		for (int i = 0; i < pixels.length; i++) {
			int p = pixels[i];
			int r = (p >> 16) & 0xFF;
			int g = (p >> 8) & 0xFF;
			int b = p & 0xFF;
			luma[i] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
		}
		return luma;
	}
}
