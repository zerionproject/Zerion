package com.professor.zerion.android.attachment;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.net.Uri;

import com.bumptech.glide.util.MarkEnforcingInputStream;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.annotation.Nullable;

import androidx.exifinterface.media.ExifInterface;

import static androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE;
import static androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION;

@NotNullByDefault
public final class ImageOrientation {

	public static final int READ_LIMIT = 8 * 1024 * 1024;

	private ImageOrientation() {
	}

	public static int of(byte[] image) {
		return of(new ByteArrayInputStream(image));
	}

	public static int of(InputStream in) {
		InputStream bounded = new MarkEnforcingInputStream(in);
		bounded.mark(READ_LIMIT);
		int orientation;
		try {
			orientation = new ExifInterface(bounded).getAttributeInt(
					TAG_ORIENTATION, ORIENTATION_NORMAL);
		} catch (IOException | RuntimeException e) {
			return ORIENTATION_NORMAL;
		}
		return orientation < ORIENTATION_NORMAL
				|| orientation > ORIENTATION_ROTATE_270 ? ORIENTATION_NORMAL
				: orientation;
	}

	@Nullable
	public static Bitmap decodeUpright(ContentResolver resolver, Uri uri,
			BitmapFactory.Options options) throws IOException {
		int orientation;
		try (InputStream in = resolver.openInputStream(uri)) {
			if (in == null) throw new IOException("could not open image");
			orientation = of(in);
		}
		options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
		Bitmap bitmap;
		try (InputStream in = resolver.openInputStream(uri)) {
			if (in == null) throw new IOException("could not open image");
			bitmap = BitmapFactory.decodeStream(in, null, options);
		}
		return bitmap == null ? null : upright(bitmap, orientation);
	}

	public static Bitmap upright(Bitmap bitmap, int orientation) {
		Matrix matrix = matrix(orientation);
		if (matrix == null) return bitmap;
		Bitmap turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
				bitmap.getHeight(), matrix, true);
		if (turned != bitmap) bitmap.recycle();
		return turned;
	}

	@Nullable
	static Matrix matrix(int orientation) {
		Matrix m = new Matrix();
		switch (orientation) {
			case ORIENTATION_FLIP_HORIZONTAL:
				m.setScale(-1, 1);
				return m;
			case ORIENTATION_ROTATE_180:
				m.setRotate(180);
				return m;
			case ORIENTATION_FLIP_VERTICAL:
				m.setScale(1, -1);
				return m;
			case ORIENTATION_TRANSPOSE:
				m.setRotate(90);
				m.postScale(-1, 1);
				return m;
			case ORIENTATION_ROTATE_90:
				m.setRotate(90);
				return m;
			case ORIENTATION_TRANSVERSE:
				m.setRotate(-90);
				m.postScale(-1, 1);
				return m;
			case ORIENTATION_ROTATE_270:
				m.setRotate(-90);
				return m;
			default:
				return null;
		}
	}
}
