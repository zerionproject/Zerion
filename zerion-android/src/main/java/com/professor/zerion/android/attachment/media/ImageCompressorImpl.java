package com.professor.zerion.android.attachment.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.professor.zerion.android.vault.utils.MetadataStripper;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;

import static android.graphics.Bitmap.CompressFormat.JPEG;
import static android.graphics.BitmapFactory.decodeStream;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_IMAGE_SIZE;

class ImageCompressorImpl implements ImageCompressor {

	private static final int MAX_ATTACHMENT_DIMENSION = 4096;

	private final ImageSizeCalculator imageSizeCalculator;
	private final MetadataStripper metadataStripper;

	@Inject
	ImageCompressorImpl(ImageSizeCalculator imageSizeCalculator,
			Context context) {
		this.imageSizeCalculator = imageSizeCalculator;
		this.metadataStripper = new MetadataStripper(context);
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
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!bitmap.compress(JPEG, 85, out))
			throw new IOException();
		if (out.size() <= MAX_IMAGE_SIZE) {
			byte[] imageData = out.toByteArray();
			byte[] strippedData = metadataStripper.stripMetadata(imageData, "image/jpeg");
			return new ByteArrayInputStream(strippedData);
		}
		throw new IOException();
	}

	private Bitmap createBitmap(InputStream is, String contentType, int maxSize)
			throws IOException {
		is = new BufferedInputStream(is);
		Size size = imageSizeCalculator.getSize(is, contentType);
		if (size.hasError()) throw new IOException();
		int dimension = Math.max(size.getWidth(), size.getHeight());
		int inSampleSize = 1;
		while (dimension > maxSize) {
			inSampleSize *= 2;
			dimension /= 2;
		}
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inSampleSize = inSampleSize;
		if (contentType.equals("image/png"))
			options.inPreferredConfig = Bitmap.Config.RGB_565;
		Bitmap bitmap = decodeStream(is, null, options);
		if (bitmap == null) throw new IOException();
		return bitmap;
	}

}
