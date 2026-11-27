package com.professor.zerion.android.conversation.glide;

import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;

import com.professor.zerion.android.ZerionApplication;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import android.graphics.Bitmap;

@NotNullByDefault
class ZerionModelLoaderFactory
		implements ModelLoaderFactory<AttachmentHeader, Bitmap> {

	private final ZerionApplication app;

	ZerionModelLoaderFactory(ZerionApplication app) {
		this.app = app;
	}

	@Override
	public ModelLoader<AttachmentHeader, Bitmap> build(
			MultiModelLoaderFactory multiFactory) {
		return new ZerionModelLoader(app);
	}

	@Override
	public void teardown() {
	}

}
