package com.professor.zerion.android.attachment.media;

import android.graphics.Bitmap;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

public interface ImageCompressor {

	String MIME_TYPE = "image/jpeg";

	InputStream compressImage(InputStream is, String contentType)
			throws IOException;

	InputStream compressImage(Bitmap bitmap) throws IOException;

}
