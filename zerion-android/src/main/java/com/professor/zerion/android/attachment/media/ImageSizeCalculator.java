package com.professor.zerion.android.attachment.media;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

@NotNullByDefault
public interface ImageSizeCalculator {

	Size getSize(InputStream is, String contentType);

}
