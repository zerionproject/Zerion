package com.professor.zerion.android.conversation.glide;

import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.signature.ObjectKey;

import com.professor.zerion.android.ZerionApplication;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.InputStream;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public final class ZerionModelLoader
		implements ModelLoader<AttachmentHeader, InputStream> {

	@Inject
	ZerionDataFetcherFactory dataFetcherFactory;

	ZerionModelLoader(ZerionApplication app) {
		app.getApplicationComponent().inject(this);
	}

	@Override
	public LoadData<InputStream> buildLoadData(AttachmentHeader model,
			int width, int height, Options options) {
		ObjectKey key = new ObjectKey(model.getMessageId());
		ZerionDataFetcher dataFetcher =
				dataFetcherFactory.createZerionDataFetcher(model);
		return new LoadData<>(key, dataFetcher);
	}

	@Override
	public boolean handles(AttachmentHeader model) {
		return true;
	}
}
