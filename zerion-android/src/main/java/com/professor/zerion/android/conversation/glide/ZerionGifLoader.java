package com.professor.zerion.android.conversation.glide;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.util.ReceivedImageGate;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.core.api.db.DbException;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Executor;

import static com.bumptech.glide.load.DataSource.LOCAL;

@NotNullByDefault
final class ZerionGifLoader implements ModelLoader<AttachmentHeader, CheckedGif> {

	static final String GIF = "image/gif";

	private final ZerionDataFetcherFactory fetchers;

	ZerionGifLoader(ZerionDataFetcherFactory fetchers) {
		this.fetchers = fetchers;
	}

	@Override
	public LoadData<CheckedGif> buildLoadData(AttachmentHeader model,
			int width, int height, Options options) {
		return new LoadData<>(new ObjectKey(model.getMessageId()),
				fetchers.createGifFetcher(model));
	}

	@Override
	public boolean handles(AttachmentHeader model) {
		return isDeclaredGif(model.getContentType());
	}

	static boolean isDeclaredGif(String contentType) {
		int semi = contentType.indexOf(';');
		String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
		return base.trim().toLowerCase(Locale.ROOT).equals(GIF);
	}

	static final class Fetcher implements DataFetcher<CheckedGif> {

		private final AttachmentReader attachmentReader;
		private final Executor dbExecutor;
		private final AttachmentHeader attachmentHeader;

		private volatile boolean cancel = false;

		Fetcher(AttachmentReader attachmentReader, Executor dbExecutor,
				AttachmentHeader attachmentHeader) {
			this.attachmentReader = attachmentReader;
			this.dbExecutor = dbExecutor;
			this.attachmentHeader = attachmentHeader;
		}

		@Override
		public void loadData(Priority priority,
				DataCallback<? super CheckedGif> callback) {
			dbExecutor.execute(() -> {
				if (cancel) return;
				byte[] image;
				try {
					image = ZerionDataFetcher.checkedImage(attachmentReader,
							attachmentHeader);
				} catch (DbException | IOException e) {
					callback.onLoadFailed(e);
					return;
				} catch (OutOfMemoryError e) {
					callback.onLoadFailed(new IOException("image too large"));
					return;
				}
				if (!GIF.equals(ReceivedImageGate.mimeType(image))) {
					callback.onLoadFailed(new IOException("not an animation"));
					return;
				}
				callback.onDataReady(new CheckedGif(image));
			});
		}

		@Override
		public void cleanup() {
		}

		@Override
		public void cancel() {
			cancel = true;
		}

		@Override
		public Class<CheckedGif> getDataClass() {
			return CheckedGif.class;
		}

		@Override
		public DataSource getDataSource() {
			return LOCAL;
		}
	}

	static final class Factory
			implements ModelLoaderFactory<AttachmentHeader, CheckedGif> {

		private final ZerionApplication app;

		Factory(ZerionApplication app) {
			this.app = app;
		}

		@Override
		public ModelLoader<AttachmentHeader, CheckedGif> build(
				MultiModelLoaderFactory multiFactory) {
			return new ZerionGifLoader(
					new ZerionModelLoader(app).dataFetcherFactory);
		}

		@Override
		public void teardown() {
		}
	}
}
