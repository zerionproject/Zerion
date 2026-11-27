package com.professor.zerion.android.conversation.glide;

import android.graphics.Bitmap;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.data.DataFetcher;
import com.professor.zerion.android.attachment.ImageOrientation;
import com.professor.zerion.android.util.ReceivedImageGate;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import static com.bumptech.glide.load.DataSource.LOCAL;
import static org.zerionproject.core.util.IoUtils.tryToClose;

@NotNullByDefault
class ZerionDataFetcher implements DataFetcher<Bitmap> {

	static final int DEFAULT_SIDE = 2560;
	static final int MIN_SIDE = 64;
	static final int MAX_SIDE = 4096;

	private static final Executor DECODERS = Executors.newFixedThreadPool(2);

	private final AttachmentReader attachmentReader;
	@DatabaseExecutor
	private final Executor dbExecutor;
	private final Executor decodeExecutor;
	private final AttachmentHeader attachmentHeader;
	private final int targetWidth;
	private final int targetHeight;
	private final boolean fitInside;

	private volatile boolean cancel = false;

	@Inject
	ZerionDataFetcher(AttachmentReader attachmentReader,
			@DatabaseExecutor Executor dbExecutor,
			AttachmentHeader attachmentHeader, int targetWidth,
			int targetHeight, boolean fitInside) {
		this(attachmentReader, dbExecutor, DECODERS, attachmentHeader,
				targetWidth, targetHeight, fitInside);
	}

	ZerionDataFetcher(AttachmentReader attachmentReader, Executor dbExecutor,
			Executor decodeExecutor, AttachmentHeader attachmentHeader,
			int targetWidth, int targetHeight, boolean fitInside) {
		this.attachmentReader = attachmentReader;
		this.dbExecutor = dbExecutor;
		this.decodeExecutor = decodeExecutor;
		this.attachmentHeader = attachmentHeader;
		this.targetWidth = targetWidth;
		this.targetHeight = targetHeight;
		this.fitInside = fitInside;
	}

	static byte[] checkedImage(AttachmentReader reader,
			AttachmentHeader header) throws DbException, IOException {
		Attachment a = reader.getAttachment(header);
		InputStream raw = a.getStream();
		try {
			return ReceivedImageGate.checked(raw);
		} finally {
			tryToClose(raw);
		}
	}

	static int sideFor(int targetWidth, int targetHeight, int imageWidth,
			int imageHeight, boolean fitInside) {
		int longest = Math.max(imageWidth, imageHeight);
		if (targetWidth <= 0 || targetHeight <= 0) {
			return within(Math.min(longest, DEFAULT_SIDE));
		}
		double across = targetWidth / (double) imageWidth;
		double down = targetHeight / (double) imageHeight;
		double scale = fitInside ? Math.min(across, down)
				: Math.max(across, down);
		int sample = 1;
		while (sample < MAX_SIDE && scale * sample * 2 <= 1) sample *= 2;
		while ((longest + sample - 1) / sample > MAX_SIDE) sample *= 2;
		return within((longest + sample - 1) / sample);
	}

	private static int within(long side) {
		return (int) Math.max(MIN_SIDE, Math.min(side, MAX_SIDE));
	}

	@Override
	public void loadData(Priority priority,
			DataCallback<? super Bitmap> callback) {
		dbExecutor.execute(() -> {
			if (cancel) return;
			byte[] image;
			try {
				image = checkedImage(attachmentReader, attachmentHeader);
			} catch (DbException | IOException e) {
				callback.onLoadFailed(e);
				return;
			} catch (OutOfMemoryError e) {
				callback.onLoadFailed(new IOException("image too large"));
				return;
			}
			decodeExecutor.execute(() -> decode(image, callback));
		});
	}

	private void decode(byte[] image, DataCallback<? super Bitmap> callback) {
		if (cancel) return;
		long size = ReceivedImageGate.dimensions(image);
		if (size == -1) {
			callback.onLoadFailed(new IOException("image not decoded"));
			return;
		}
		int orientation = ImageOrientation.of(image);
		boolean turned = ImageOrientation.swapsSides(orientation);
		int width = (int) (size >> 32);
		int height = (int) size;
		Bitmap bitmap = ReceivedImageGate.decodeChecked(image,
				sideFor(targetWidth, targetHeight, turned ? height : width,
						turned ? width : height, fitInside));
		if (bitmap == null) {
			callback.onLoadFailed(new IOException("image not decoded"));
			return;
		}
		try {
			bitmap = ImageOrientation.upright(bitmap, orientation);
		} catch (RuntimeException | OutOfMemoryError e) {
			callback.onLoadFailed(new IOException("image not decoded"));
			return;
		}
		callback.onDataReady(bitmap);
	}

	@Override
	public void cleanup() {
	}

	@Override
	public void cancel() {
		cancel = true;
	}

	@Override
	public Class<Bitmap> getDataClass() {
		return Bitmap.class;
	}

	@Override
	public DataSource getDataSource() {
		return LOCAL;
	}
}
