package com.professor.zerion.android.attachment;

import android.app.Application;
import android.net.Uri;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.media.ImageCompressor;
import com.professor.zerion.android.vault.utils.MetadataStripper;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessageFormat;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static com.professor.zerion.android.attachment.AttachmentItem.State.ERROR;
import static com.professor.zerion.android.util.UiUtils.observeForeverOnce;

@NotNullByDefault
class AttachmentCreatorImpl implements AttachmentCreator {
	private final Application app;
	@IoExecutor
	private final Executor ioExecutor;
	private final MessagingManager messagingManager;
	private final AttachmentRetriever retriever;
	private final ImageCompressor imageCompressor;
	private final MetadataStripper metadataStripper;

	private final CopyOnWriteArrayList<Uri> uris = new CopyOnWriteArrayList<>();
	private final CopyOnWriteArrayList<AttachmentItemResult> itemResults =
			new CopyOnWriteArrayList<>();

	@Nullable
	private AttachmentCreationTask task;

	@Nullable
	private volatile MutableLiveData<AttachmentResult> result;

	@Inject
	AttachmentCreatorImpl(Application app, @IoExecutor Executor ioExecutor,
			MessagingManager messagingManager, AttachmentRetriever retriever,
			ImageCompressor imageCompressor) {
		this.app = app;
		this.ioExecutor = ioExecutor;
		this.messagingManager = messagingManager;
		this.retriever = retriever;
		this.imageCompressor = imageCompressor;
		this.metadataStripper = new MetadataStripper(app);
	}

	@Override
	@UiThread
	public LiveData<AttachmentResult> storeAttachments(
			LiveData<GroupId> groupId, Collection<Uri> newUris,
			PrivateMessageFormat messageFormat) {
		if (task != null || result != null || !uris.isEmpty()) {
			throw new IllegalStateException();
		}
		MutableLiveData<AttachmentResult> result = new MutableLiveData<>();
		this.result = result;
		uris.addAll(newUris);
		observeForeverOnce(groupId, id -> {
			if (id == null) throw new IllegalStateException();
			boolean needsSize = uris.size() == 1;
			task = new AttachmentCreationTask(messagingManager,
					app.getContentResolver(), this, imageCompressor,
					metadataStripper, id, uris, needsSize,
					messageFormat);
			ioExecutor.execute(() -> task.storeAttachments());
		});
		return result;
	}

	@Override
	@UiThread
	public LiveData<AttachmentResult> getLiveAttachments() {
		MutableLiveData<AttachmentResult> result = this.result;
		if (task == null || result == null || uris.isEmpty()) {
			throw new IllegalStateException();
		}
		return result;
	}

	@Override
	@IoExecutor
	public void onAttachmentHeaderReceived(Uri uri, AttachmentHeader h,
			boolean needsSize) {
		try {
			Attachment a = retriever.getMessageAttachment(h);
			AttachmentItem item = retriever.createAttachmentItem(a, needsSize);
			if (item.getState() == ERROR) {
				throw new IOException("AttachmentItem state is ERROR for: " +
						h.getContentType());
			}
			AttachmentItemResult itemResult =
					new AttachmentItemResult(uri, item);
			itemResults.add(itemResult);
			MutableLiveData<AttachmentResult> result = this.result;
			if (result != null) result.postValue(getResult(false));
		} catch (IOException | DbException e) {
			onAttachmentError(uri, e);
		}
	}

	@Override
	@IoExecutor
	public void onAttachmentError(Uri uri, Throwable t) {
		String errorMsg = getErrorMessage(uri, t);
		AttachmentItemResult itemResult =
				new AttachmentItemResult(uri, errorMsg);
		itemResults.add(itemResult);
		MutableLiveData<AttachmentResult> result = this.result;
		if (result != null) result.postValue(getResult(false));
	}

	private String getErrorMessage(Uri uri, Throwable t) {
		String mimeType = app.getContentResolver().getType(uri);
		boolean isVideo = mimeType != null && mimeType.startsWith("video/");
		boolean isAudio = mimeType != null && mimeType.startsWith("audio/");

		if (t instanceof ChunkedAttachmentsNotSupportedException) {
			ChunkedAttachmentsNotSupportedException e =
					(ChunkedAttachmentsNotSupportedException) t;
			if (e.isVideo()) {
				return app.getString(R.string.video_not_supported_by_contact);
			} else if (e.isAudio()) {
				return app.getString(R.string.audio_not_supported_by_contact);
			}
			return app.getString(R.string.video_not_supported_by_contact);
		} else if (t instanceof UnsupportedMimeTypeException) {
			String type = ((UnsupportedMimeTypeException) t).getMimeType();
			return app.getString(R.string.image_attach_error_invalid_mime_type, type);
		} else if (t instanceof FileTooBigException) {
			int mb = org.briarproject.briar.api.attachment.MediaConstants.MAX_ATTACHMENT_SIZE / 1024 / 1024;
			if (isVideo) {
				return app.getString(R.string.video_attach_error_too_big, mb);
			} else if (isAudio) {
				return app.getString(R.string.audio_attach_error_too_big, mb);
			} else {
				return app.getString(R.string.image_attach_error_too_big, mb);
			}
		} else if (t instanceof IOException) {
			String msg = t.getMessage();
			if (msg != null && msg.contains("file size")) {
				return app.getString(R.string.media_attach_error_unknown_size);
			}
			if (isVideo) {
				return app.getString(R.string.video_attach_error);
			} else if (isAudio) {
				return app.getString(R.string.audio_attach_error);
			} else {
				return app.getString(R.string.image_attach_error);
			}
		} else if (t instanceof org.briarproject.bramble.api.db.DbException) {
			if (isVideo) {
				return app.getString(R.string.video_attach_error);
			} else if (isAudio) {
				return app.getString(R.string.audio_attach_error);
			} else {
				return app.getString(R.string.image_attach_error);
			}
		}
		return null;
	}

	@Override
	@IoExecutor
	public void onAttachmentCreationFinished() {
		MutableLiveData<AttachmentResult> result = this.result;
		if (result != null) result.postValue(getResult(true));
	}

	@Override
	@UiThread
	public List<AttachmentHeader> getAttachmentHeadersForSending() {
		List<AttachmentHeader> headers = new ArrayList<>(itemResults.size());
		for (AttachmentItemResult itemResult : itemResults) {
			if (itemResult.getItem() == null) throw new IllegalStateException();
			headers.add(itemResult.getItem().getHeader());
		}
		return headers;
	}

	@Override
	@UiThread
	public boolean hasValidAttachments() {
		if (itemResults.isEmpty()) return false;
		for (AttachmentItemResult itemResult : itemResults) {
			if (itemResult.hasError() || itemResult.getItem() == null) {
				return false;
			}
		}
		return true;
	}

	@Override
	@UiThread
	public void onAttachmentsSent(MessageId id) {
		resetState();
	}

	@Override
	@UiThread
	public void cancel() {
		if (task != null) task.cancel();
		deleteUnsentAttachments();
		resetState();
	}

	@UiThread
	private void resetState() {
		task = null;
		uris.clear();
		itemResults.clear();
		MutableLiveData<AttachmentResult> result = this.result;
		if (result != null) {
			result.setValue(null);
			this.result = null;
		}
	}

	@Override
	@UiThread
	public void deleteUnsentAttachments() {
		List<AttachmentHeader> headers = new ArrayList<>(itemResults.size());
		for (AttachmentItemResult itemResult : itemResults) {
			if (itemResult.getItem() != null)
				headers.add(itemResult.getItem().getHeader());
		}
		ioExecutor.execute(() -> {
			for (AttachmentHeader header : headers) {
				try {
					messagingManager.removeAttachment(header);
				} catch (DbException e) {
				}
			}
		});
	}

	private AttachmentResult getResult(boolean finished) {
		Collection<AttachmentItemResult> items = new ArrayList<>(itemResults);
		return new AttachmentResult(items, finished);
	}

	@Override
	@IoExecutor
	public void onAttachmentProgress(Uri uri, float progress) {
		MutableLiveData<AttachmentResult> result = this.result;
		if (result != null) {
			result.postValue(getResultWithProgress(progress));
		}
	}

	private AttachmentResult getResultWithProgress(float progress) {
		Collection<AttachmentItemResult> items = new ArrayList<>(itemResults);
		return new AttachmentResult(items, false, progress);
	}
}
