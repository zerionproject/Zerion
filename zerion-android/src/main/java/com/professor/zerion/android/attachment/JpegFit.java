package com.professor.zerion.android.attachment;

import android.graphics.Bitmap;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public final class JpegFit {

	public interface Encoder {
		@Nullable
		byte[] encode(int quality, int scalePercent) throws IOException;
	}

	static final int[] QUALITIES = {65, 50, 35};
	static final int[] SCALES = {100, 75, 50, 35, 25};

	private JpegFit() {
	}

	@Nullable
	public static byte[] fit(Encoder encoder, int firstQuality, int maxBytes)
			throws IOException {
		for (int scale : SCALES) {
			byte[] data = encoder.encode(firstQuality, scale);
			if (data == null) return null;
			if (data.length <= maxBytes) return data;
			for (int quality : QUALITIES) {
				if (quality >= firstQuality) continue;
				data = encoder.encode(quality, scale);
				if (data == null) return null;
				if (data.length <= maxBytes) return data;
			}
		}
		return null;
	}

	@Nullable
	public static byte[] fitBitmap(Bitmap bitmap, int firstQuality,
			int maxBytes) throws IOException {
		return fit((quality, scale) -> encode(bitmap, quality, scale),
				firstQuality, maxBytes);
	}

	@Nullable
	private static byte[] encode(Bitmap bitmap, int quality, int scale)
			throws IOException {
		Bitmap source = bitmap;
		if (scale < 100) {
			int w = Math.max(1, bitmap.getWidth() * scale / 100);
			int h = Math.max(1, bitmap.getHeight() * scale / 100);
			source = Bitmap.createScaledBitmap(bitmap, w, h, true);
		}
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			if (!source.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
				return null;
			}
			return ImageScrubber.scrub(out.toByteArray());
		} finally {
			if (source != bitmap) source.recycle();
		}
	}
}
