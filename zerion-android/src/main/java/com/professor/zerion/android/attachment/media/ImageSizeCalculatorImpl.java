package com.professor.zerion.android.attachment.media;

import com.bumptech.glide.util.MarkEnforcingInputStream;

import com.professor.zerion.android.attachment.media.ImageHelper.DecodeResult;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

import androidx.exifinterface.media.ExifInterface;

import static androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE;
import static androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE;
import static androidx.exifinterface.media.ExifInterface.TAG_IMAGE_LENGTH;
import static androidx.exifinterface.media.ExifInterface.TAG_IMAGE_WIDTH;
import static androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION;

@NotNullByDefault
class ImageSizeCalculatorImpl implements ImageSizeCalculator {


	private static final int READ_LIMIT = 1024 * 8192;

	private final ImageHelper imageHelper;

	ImageSizeCalculatorImpl(ImageHelper imageHelper) {
		this.imageHelper = imageHelper;
	}

	@Override
	public Size getSize(InputStream is, String contentType) {
		Size size = new Size();
		is = new MarkEnforcingInputStream(is);
		is.mark(READ_LIMIT);
		if (contentType.equals("image/jpeg")) {
			try {
				size = getSizeFromExif(is);
				is.reset();
			} catch (IOException e) {
							}
		}
		if (size.hasError()) {
			is.mark(READ_LIMIT);
			try {
				size = getSizeFromBitmap(is);
				is.reset();
			} catch (IOException e) {
							}
		}
		return size;
	}

	private Size getSizeFromExif(InputStream is) throws IOException {
		ExifInterface exif = new ExifInterface(is);
		int width = exif.getAttributeInt(TAG_IMAGE_WIDTH, 0);
		int height = exif.getAttributeInt(TAG_IMAGE_LENGTH, 0);
		if (width == 0 || height == 0) return new Size();
		int orientation = exif.getAttributeInt(TAG_ORIENTATION, 0);
		if (orientation == ORIENTATION_ROTATE_90 ||
				orientation == ORIENTATION_ROTATE_270 ||
				orientation == ORIENTATION_TRANSVERSE ||
				orientation == ORIENTATION_TRANSPOSE) {
			return new Size(height, width, "image/jpeg");
		}
		return new Size(width, height, "image/jpeg");
	}

	private Size getSizeFromBitmap(InputStream is) {
		DecodeResult result = imageHelper.decodeStream(is);
		if (result.width < 1 || result.height < 1) return new Size();
		return new Size(result.width, result.height, result.mimeType);
	}
}
