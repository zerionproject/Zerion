package com.professor.zerion.android.sticker;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes a user-picked image, normalises it for use as a sticker, and
 * persists it via {@link StickerStorage}.
 *
 * Pipeline (mirrors iOS StickerImporter.swift):
 *   1. Probe dimensions with inJustDecodeBounds — reject pre-decode if
 *      either axis exceeds 8000 px (ZIP-bomb / decoder OOM defence).
 *   2. inSampleSize-aware decode with a memory cap.
 *   3. Resize to ≤512 px on the longest edge (sticker render is 160 dp,
 *      512 px gives 3× density head-room and still fits the 256 KB cap).
 *   4. PNG re-encode at quality 100. PNG-on-PNG kills EXIF/ICC/LSB
 *      stego — sender-side metadata stripping before bytes leave the
 *      device.
 *   5. If the encoded PNG is over the per-file cap, downscale to 75 %
 *      and retry once.
 */
@NotNullByDefault
public final class StickerImporter {

	public static final int MAX_INPUT_DIM = 8000;
	public static final int TARGET_LONG_EDGE = 512;

	private final StickerStorage storage;

	public StickerImporter(StickerStorage storage) {
		this.storage = storage;
	}

	public String importFromUri(ContentResolver cr, Uri uri) throws IOException {
		// Step 1 — bounds probe
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		try (InputStream is = cr.openInputStream(uri)) {
			if (is == null) throw new IOException("could not open uri");
			BitmapFactory.decodeStream(is, null, bounds);
		}
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
			throw new IOException("could not decode bounds");
		}
		if (bounds.outWidth > MAX_INPUT_DIM || bounds.outHeight > MAX_INPUT_DIM) {
			throw new IOException("input image too large: "
					+ bounds.outWidth + "x" + bounds.outHeight);
		}

		// Step 2 — inSampleSize-aware decode
		BitmapFactory.Options decode = new BitmapFactory.Options();
		decode.inSampleSize = computeInSampleSize(
				bounds.outWidth, bounds.outHeight, TARGET_LONG_EDGE);
		decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
		Bitmap raw;
		try (InputStream is = cr.openInputStream(uri)) {
			if (is == null) throw new IOException("could not open uri (decode)");
			raw = BitmapFactory.decodeStream(is, null, decode);
		}
		if (raw == null) throw new IOException("decode returned null");

		try {
			// Step 3 — resize
			Bitmap resized = resizeLongEdge(raw, TARGET_LONG_EDGE);
			try {
				// Steps 4–5 — PNG encode (with single 75 % retry on cap miss)
				byte[] png = encodePng(resized);
				if (png.length > StickerStorage.MAX_FILE_BYTES) {
					Bitmap smaller = resizeLongEdge(resized,
							(int) (TARGET_LONG_EDGE * 0.75f));
					try {
						png = encodePng(smaller);
					} finally {
						if (smaller != resized) smaller.recycle();
					}
				}
				if (png.length > StickerStorage.MAX_FILE_BYTES) {
					throw new IOException("sticker exceeds cap after retry");
				}
				return storage.save(png);
			} finally {
				if (resized != raw) resized.recycle();
			}
		} finally {
			raw.recycle();
		}
	}

	private static int computeInSampleSize(int w, int h, int target) {
		int sample = 1;
		int longest = Math.max(w, h);
		while (longest / sample > target * 2) {
			sample *= 2;
		}
		return sample;
	}

	private static Bitmap resizeLongEdge(Bitmap src, int targetLongEdge) {
		int w = src.getWidth();
		int h = src.getHeight();
		int longest = Math.max(w, h);
		if (longest <= targetLongEdge) return src;
		float scale = (float) targetLongEdge / longest;
		int newW = Math.max(1, Math.round(w * scale));
		int newH = Math.max(1, Math.round(h * scale));
		return Bitmap.createScaledBitmap(src, newW, newH, true);
	}

	private static byte[] encodePng(Bitmap bmp) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) {
			throw new IOException("PNG compress failed");
		}
		return out.toByteArray();
	}
}
