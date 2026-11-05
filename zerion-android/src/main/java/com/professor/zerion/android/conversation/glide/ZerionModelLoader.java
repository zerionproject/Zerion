package com.professor.zerion.android.conversation.glide;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;
import com.bumptech.glide.signature.ObjectKey;

import com.professor.zerion.android.ZerionApplication;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import android.graphics.Bitmap;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public final class ZerionModelLoader
		implements ModelLoader<AttachmentHeader, Bitmap> {

	@Inject
	ZerionDataFetcherFactory dataFetcherFactory;

	ZerionModelLoader(ZerionApplication app) {
		app.getApplicationComponent().inject(this);
	}

	@Override
	public LoadData<Bitmap> buildLoadData(AttachmentHeader model,
			int width, int height, Options options) {
		ObjectKey key = new ObjectKey(model.getMessageId());
		ZerionDataFetcher dataFetcher =
				dataFetcherFactory.createZerionDataFetcher(model, width,
						height, fitsInside(options));
		return new LoadData<>(key, dataFetcher);
	}

	static boolean fitsInside(Options options) {
		DownsampleStrategy strategy = options.get(DownsampleStrategy.OPTION);
		return strategy == DownsampleStrategy.FIT_CENTER
				|| strategy == DownsampleStrategy.CENTER_INSIDE;
	}

	@Override
	public boolean handles(AttachmentHeader model) {
		return true;
	}
}
