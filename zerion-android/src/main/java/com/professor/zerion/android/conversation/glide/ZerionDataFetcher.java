package com.professor.zerion.android.conversation.glide;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;

import static com.bumptech.glide.load.DataSource.LOCAL;
import static org.zerionproject.core.util.IoUtils.tryToClose;

@NotNullByDefault
class ZerionDataFetcher implements DataFetcher<InputStream> {

	private final AttachmentReader attachmentReader;
	@DatabaseExecutor
	private final Executor dbExecutor;
	private final AttachmentHeader attachmentHeader;

	@Nullable
	private volatile InputStream inputStream;
	private volatile boolean cancel = false;

	@Inject
	ZerionDataFetcher(AttachmentReader attachmentReader,
			@DatabaseExecutor Executor dbExecutor,
			AttachmentHeader attachmentHeader) {
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
		this.attachmentHeader = attachmentHeader;
	}

	@Override
	public void loadData(Priority priority,
			DataCallback<? super InputStream> callback) {
		dbExecutor.execute(() -> {
			if (cancel) return;
			try {
				Attachment a = attachmentReader.getAttachment(attachmentHeader);
				inputStream = a.getStream();
				callback.onDataReady(inputStream);
			} catch (DbException e) {
				callback.onLoadFailed(e);
			}
		});
	}

	@Override
	public void cleanup() {
		tryToClose(inputStream);
	}

	@Override
	public void cancel() {
		cancel = true;
	}

	@Override
	public Class<InputStream> getDataClass() {
		return InputStream.class;
	}

	@Override
	public DataSource getDataSource() {
		return LOCAL;
	}

}
