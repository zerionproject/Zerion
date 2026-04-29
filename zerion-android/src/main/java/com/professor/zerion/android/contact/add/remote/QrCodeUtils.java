package com.professor.zerion.android.contact.add.remote;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * QR code encode + decode via ZXing core. No Google Play Services, no
 * ML Kit, no Firebase, no clearnet phone-home — pure on-device pixel
 * crunching against a self-contained Java library.
 *
 * <p>Encoding (existing): {@link #generateQrCode(String)} / {@link
 * #generateQrCode(String, int)} produce a black-on-white {@link Bitmap}
 * suitable for display.
 *
 * <p>Decoding (new): {@link #decodeQrFromYuv(byte[], int, int, int, int,
 * int)} reads a QR payload out of a single CameraX YUV frame's
 * luminance plane. Designed to be called repeatedly from a
 * {@code CameraX ImageAnalysis} analyzer until it returns non-null.
 */
public class QrCodeUtils {

	private static final int QR_SIZE = 512;

	// QRCodeReader is stateless under the hood (just a thin façade over
	// PURE_BARCODE / TRY_HARDER decode paths) but the docs note that
	// reusing instances is fine. One per analyzer thread is plenty.
	private static final ThreadLocal<QRCodeReader> READER =
			ThreadLocal.withInitial(QRCodeReader::new);

	private static final Map<DecodeHintType, Object> DECODE_HINTS;

	static {
		DECODE_HINTS = new EnumMap<>(DecodeHintType.class);
		// PURE_BARCODE skips orientation/quiet-zone search heuristics —
		// faster on screen-captured QRs but flakier on camera frames.
		// Leave it OFF and use TRY_HARDER instead so we tolerate motion
		// blur, partial occlusion, and angled scans the way ML Kit did.
		DECODE_HINTS.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
	}

	@Nullable
	public static Bitmap generateQrCode(String link) {
		return generateQrCode(link, QR_SIZE);
	}

	@Nullable
	public static Bitmap generateQrCode(String link, int size) {
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

	/**
	 * Decode a QR payload from a single YUV_420_888 frame.
	 *
	 * <p>CameraX delivers an {@code ImageProxy} whose {@code Image} has
	 * three planes — Y, U, V. Only the Y (luminance) plane matters for
	 * grayscale barcode decoding, which is all we need.
	 *
	 * @param yPlane         raw Y-plane bytes copied out of {@code
	 *                       imageProxy.getImage().getPlanes()[0]
	 *                       .getBuffer()}, with row stride already
	 *                       collapsed to {@code width} (no padding).
	 * @param width          frame width in pixels.
	 * @param height         frame height in pixels.
	 * @param left           crop window left, 0 for full frame.
	 * @param top            crop window top, 0 for full frame.
	 * @param rotationDegrees CameraX-supplied rotation in degrees
	 *                       (typically 0, 90, 180, 270). Applied via
	 *                       {@link LuminanceSource#rotateCounterClockwise()}
	 *                       so the QR appears upright to the decoder.
	 * @return the decoded text, or {@code null} if the frame contains
	 *         no recognisable QR. Either is normal — the caller should
	 *         keep feeding frames until it gets a hit.
	 */
	@Nullable
	public static String decodeQrFromYuv(byte[] yPlane, int width, int height,
			int left, int top, int rotationDegrees) {
		if (yPlane == null || width <= 0 || height <= 0) return null;

		// PlanarYUVLuminanceSource does NOT override rotateCounterClockwise
		// (ZXing's base impl throws UnsupportedOperationException for it),
		// so we rotate the Y plane bytes ourselves before constructing
		// the source. CameraX rotationDegrees is the clockwise rotation
		// needed to make the frame upright.
		int normalised = ((rotationDegrees % 360) + 360) % 360;
		byte[] rotated;
		int sourceWidth;
		int sourceHeight;
		if (normalised == 0) {
			rotated = yPlane;
			sourceWidth = width;
			sourceHeight = height;
		} else if (normalised == 180) {
			rotated = rotate180(yPlane, width, height);
			sourceWidth = width;
			sourceHeight = height;
		} else if (normalised == 90) {
			rotated = rotate90Cw(yPlane, width, height);
			sourceWidth = height;
			sourceHeight = width;
		} else if (normalised == 270) {
			rotated = rotate270Cw(yPlane, width, height);
			sourceWidth = height;
			sourceHeight = width;
		} else {
			// Non-orthogonal rotation (rare, e.g. 45° from a tilted
			// device). Skip — QR codes need axis-aligned scanning.
			return null;
		}

		try {
			LuminanceSource source = new PlanarYUVLuminanceSource(
					rotated, sourceWidth, sourceHeight,
					left, top,
					Math.min(sourceWidth - left, sourceWidth),
					Math.min(sourceHeight - top, sourceHeight),
					false);

			BinaryBitmap bitmap =
					new BinaryBitmap(new HybridBinarizer(source));
			QRCodeReader reader = READER.get();
			if (reader == null) reader = new QRCodeReader();
			Result result = reader.decode(bitmap, DECODE_HINTS);
			return result.getText();
		} catch (NotFoundException
				| ChecksumException
				| FormatException e) {
			return null;
		} catch (Throwable t) {
			return null;
		} finally {
			QRCodeReader r = READER.get();
			if (r != null) r.reset();
		}
	}

	private static byte[] rotate90Cw(byte[] src, int w, int h) {
		byte[] dst = new byte[w * h];
		for (int y = 0; y < h; y++) {
			int srcRow = y * w;
			int newCol = h - 1 - y;
			for (int x = 0; x < w; x++) {
				dst[x * h + newCol] = src[srcRow + x];
			}
		}
		return dst;
	}

	private static byte[] rotate180(byte[] src, int w, int h) {
		int len = w * h;
		byte[] dst = new byte[len];
		for (int i = 0; i < len; i++) {
			dst[len - 1 - i] = src[i];
		}
		return dst;
	}

	private static byte[] rotate270Cw(byte[] src, int w, int h) {
		byte[] dst = new byte[w * h];
		for (int y = 0; y < h; y++) {
			int srcRow = y * w;
			for (int x = 0; x < w; x++) {
				int newCol = y;
				int newRow = w - 1 - x;
				dst[newRow * h + newCol] = src[srcRow + x];
			}
		}
		return dst;
	}
}
