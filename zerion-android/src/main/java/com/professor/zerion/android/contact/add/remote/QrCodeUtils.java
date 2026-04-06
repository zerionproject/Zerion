package com.professor.zerion.android.contact.add.remote;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * QR code generation utility for Zerion handshake links.
 * Uses ZXing core for encoding — no camera dependency.
 */
class QrCodeUtils {

	private static final int QR_SIZE = 512;

	/**
	 * Generate a QR code bitmap from a Zerion handshake link.
	 * Uses high error correction (L is sufficient for short links).
	 *
	 * @param link The zerion:// handshake link (53 chars base32 + prefix)
	 * @return Bitmap of the QR code, or null if generation fails
	 */
	@Nullable
	static Bitmap generateQrCode(String link) {
		return generateQrCode(link, QR_SIZE);
	}

	@Nullable
	static Bitmap generateQrCode(String link, int size) {
		try {
			Map<EncodeHintType, Object> hints = new HashMap<>();
			hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
			hints.put(EncodeHintType.MARGIN, 1);

			QRCodeWriter writer = new QRCodeWriter();
			BitMatrix matrix = writer.encode(link, BarcodeFormat.QR_CODE,
					size, size, hints);

			int width = matrix.getWidth();
			int height = matrix.getHeight();
			int[] pixels = new int[width * height];

			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					pixels[y * width + x] = matrix.get(x, y)
							? Color.BLACK : Color.WHITE;
				}
			}

			Bitmap bitmap = Bitmap.createBitmap(width, height,
					Bitmap.Config.ARGB_8888);
			bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
			return bitmap;
		} catch (WriterException e) {
			return null;
		}
	}
}
