package com.professor.zerion.android.attachment.media;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;

import com.professor.zerion.android.attachment.ImageOrientation;
import com.professor.zerion.android.attachment.JpegFit;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;

import static android.graphics.BitmapFactory.decodeStream;
import static org.zerionproject.core.util.IoUtils.tryToClose;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_DELIVERABLE_IMAGE_SIZE;

class ImageCompressorImpl implements ImageCompressor {

	private static final int MAX_ATTACHMENT_DIMENSION = 1600;
	private static final int JPEG_QUALITY = 75;

	private final ImageSizeCalculator imageSizeCalculator;

	@Inject
	ImageCompressorImpl(ImageSizeCalculator imageSizeCalculator) {
		this.imageSizeCalculator = imageSizeCalculator;
	}

	@Override
	public InputStream compressImage(InputStream is, String contentType)
			throws IOException {
		Bitmap bitmap = null;
		try {
			bitmap = createBitmap(is, contentType, MAX_ATTACHMENT_DIMENSION);
			return compressImageInternal(bitmap);
		} finally {
			if (bitmap != null && !bitmap.isRecycled()) {
				bitmap.recycle();
			}
			tryToClose(is);
		}
	}

	@Override
	public InputStream compressImage(Bitmap bitmap) throws IOException {
		try {
			return compressImageInternal(bitmap);
		} finally {
			if (bitmap != null && !bitmap.isRecycled()) {
				bitmap.recycle();
			}
		}
	}

	private InputStream compressImageInternal(Bitmap bitmap) throws IOException {
		byte[] imageData = JpegFit.fitBitmap(bitmap, JPEG_QUALITY,
				MAX_DELIVERABLE_IMAGE_SIZE);
		if (imageData == null) throw new IOException();
		return new ByteArrayInputStream(imageData);
	}

	private Bitmap createBitmap(InputStream is, String contentType, int maxSize)
			throws IOException {
		is = new BufferedInputStream(is);
		is.mark(16);
		byte[] head = com.professor.zerion.android.util
				.SafeImageDecoder.sniffHead(is);
		if (head == null
				|| !com.professor.zerion.android.util
						.SafeImageDecoder.hasAllowedMagic(head)) {
			throw new IOException("unsupported image format");
		}
		is.reset();
		Size size = imageSizeCalculator.getSize(is, contentType);
		if (size.hasError()) throw new IOException();
		if (size.getWidth() > com.professor.zerion.android.util
				.SafeImageDecoder.MAX_DIMENSION
				|| size.getHeight() > com.professor.zerion.android.util
						.SafeImageDecoder.MAX_DIMENSION) {
			throw new IOException("image dimensions too large");
		}
		int dimension = Math.max(size.getWidth(), size.getHeight());
		int inSampleSize = 1;
		while (dimension > maxSize) {
			inSampleSize *= 2;
			dimension /= 2;
		}
		is.mark(ImageOrientation.READ_LIMIT);
		int orientation = ImageOrientation.of(is);
		is.reset();
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = inSampleSize;
		options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
		if (contentType.equals("image/png"))
			options.inPreferredConfig = Bitmap.Config.RGB_565;
		Bitmap bitmap = decodeStream(is, null, options);
		if (bitmap == null) throw new IOException();
		return ImageOrientation.upright(bitmap, orientation);
	}

}
