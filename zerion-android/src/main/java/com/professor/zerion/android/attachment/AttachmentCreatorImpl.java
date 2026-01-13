package com.professor.zerion.android.attachment;


import android.app.Application;
import android.net.Uri;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.media.ImageCompressor;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.messaging.MessagingManager;
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
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_IMAGE_SIZE;

@NotNullByDefault
class AttachmentCreatorImpl implements AttachmentCreator {


	private final Application app;
	@IoExecutor
	private final Executor ioExecutor;
	private final MessagingManager messagingManager;
	private final AttachmentRetriever retriever;
	private final ImageCompressor imageCompressor;

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
	}

	@Override
	@UiThread
	public LiveData<AttachmentResult> storeAttachments(
			LiveData<GroupId> groupId, Collection<Uri> newUris) {
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
					app.getContentResolver(), this, imageCompressor, id,
					uris, needsSize);
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
			if (item.getState() == ERROR) throw new IOException();
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
		String errorMsg;
		if (t instanceof UnsupportedMimeTypeException) {
			String mimeType = ((UnsupportedMimeTypeException) t).getMimeType();
			errorMsg = app.getString(
					R.string.image_attach_error_invalid_mime_type, mimeType);
		} else if (t instanceof FileTooBigException) {
			int mb = MAX_IMAGE_SIZE / 1024 / 1024;
			errorMsg = app.getString(R.string.image_attach_error_too_big, mb);
		} else if (t instanceof IOException) {
			int mb = MAX_IMAGE_SIZE / 1024 / 1024;
			errorMsg = app.getString(R.string.image_attach_error_too_big, mb);
		} else {
			errorMsg = null;
		}
		AttachmentItemResult itemResult =
				new AttachmentItemResult(uri, errorMsg);
		itemResults.add(itemResult);
		MutableLiveData<AttachmentResult> result = this.result;
		if (result != null) result.postValue(getResult(false));
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

}
