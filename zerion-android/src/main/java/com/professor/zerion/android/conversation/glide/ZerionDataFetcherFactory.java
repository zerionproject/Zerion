package com.professor.zerion.android.conversation.glide;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

@NotNullByDefault
public class ZerionDataFetcherFactory {

	private final AttachmentReader attachmentReader;
	@DatabaseExecutor
	private final Executor dbExecutor;

	@Inject
	public ZerionDataFetcherFactory(AttachmentReader attachmentReader,
			@DatabaseExecutor Executor dbExecutor) {
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
	}

	ZerionDataFetcher createZerionDataFetcher(AttachmentHeader model) {
		return new ZerionDataFetcher(attachmentReader, dbExecutor, model);
	}

}
