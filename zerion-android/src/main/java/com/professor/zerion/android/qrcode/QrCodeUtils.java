package com.professor.zerion.android.qrcode;

import android.graphics.Bitmap;
import android.util.DisplayMetrics;

import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.briarproject.nullsafety.NotNullByDefault;


import javax.annotation.Nullable;

import static android.graphics.Bitmap.Config.ARGB_8888;
import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;
import static com.google.zxing.BarcodeFormat.QR_CODE;

@NotNullByDefault
public class QrCodeUtils {
	public static final double HOTSPOT_QRCODE_FACTOR = 0.35;


	@Nullable
	public static Bitmap createQrCode(DisplayMetrics dm, String input) {
		return createQrCode(Math.min(dm.widthPixels, dm.heightPixels), input);
	}

	@Nullable
	public static Bitmap createQrCode(int edgeLen, String input) {
		try {
			BitMatrix encoded = new QRCodeWriter().encode(input, QR_CODE,
					edgeLen, edgeLen);
			return renderQrCode(encoded);
		} catch (WriterException e) {
			return null;
		}
	}

	private static Bitmap renderQrCode(BitMatrix matrix) {
		int width = matrix.getWidth();
		int height = matrix.getHeight();
		int[] pixels = new int[width * height];
		for (int x = 0; x < width; x++) {
			for (int y = 0; y < height; y++) {
				pixels[y * width + x] = matrix.get(x, y) ? BLACK : WHITE;
			}
		}
		Bitmap qr = Bitmap.createBitmap(width, height, ARGB_8888);
		qr.setPixels(pixels, 0, width, 0, 0, width, height);
		return qr;
	}
}
