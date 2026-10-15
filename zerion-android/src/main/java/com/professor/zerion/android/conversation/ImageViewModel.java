package com.professor.zerion.android.conversation;

import android.app.Application;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.AndroidExecutor;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.SaveCleanCopy;
import com.professor.zerion.android.viewmodel.DbViewModel;
import com.professor.zerion.android.viewmodel.LiveEvent;
import com.professor.zerion.android.viewmodel.MutableLiveEvent;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.app.api.messaging.event.AttachmentReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.annotation.UiThread;

import static java.util.Objects.requireNonNull;

@NotNullByDefault
public class ImageViewModel extends DbViewModel implements EventListener {

	private final AttachmentReader attachmentReader;
	private final EventBus eventBus;
	@IoExecutor
	private final Executor ioExecutor;

	private boolean receivedAttachmentsInitialized = false;
	private final HashMap<MessageId, MutableLiveEvent<Boolean>>
			receivedAttachments = new HashMap<>();

	private final MutableLiveEvent<Boolean> savableImage =
			new MutableLiveEvent<>();
	@Nullable
	private volatile SavableImage readImage;
	private final MutableLiveEvent<Boolean> imageClicked =
			new MutableLiveEvent<>();
	private int toolbarTop, toolbarBottom;

	@Inject
	ImageViewModel(Application application, AttachmentReader attachmentReader,
			EventBus eventBus, @DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager,
			TransactionManager db,
			AndroidExecutor androidExecutor,
			@IoExecutor Executor ioExecutor) {
		super(application, dbExecutor, lifecycleManager, db, androidExecutor);
		this.attachmentReader = attachmentReader;
		this.eventBus = eventBus;
		this.ioExecutor = ioExecutor;

		eventBus.addListener(this);
	}

	@Override
	protected void onCleared() {
		super.onCleared();
		eventBus.removeListener(this);
	}

	@UiThread
	@Override
	public void eventOccurred(Event e) {
		if (e instanceof AttachmentReceivedEvent) {
			MessageId id = ((AttachmentReceivedEvent) e).getMessageId();
			MutableLiveEvent<Boolean> oldEvent;
			if (receivedAttachmentsInitialized) {
				oldEvent = receivedAttachments.get(id);
				if (oldEvent != null) oldEvent.postEvent(true);
			} else {
				receivedAttachments.put(id, new MutableLiveEvent<>(true));
			}
		}
	}

	@UiThread
	void expectAttachments(List<AttachmentItem> attachments) {
		for (AttachmentItem item : attachments) {
			if (item.getState().isFinal()) continue;
			MessageId id = item.getMessageId();
			if (!receivedAttachments.containsKey(id)) {
				receivedAttachments.put(id, new MutableLiveEvent<>());
			}
		}
		receivedAttachmentsInitialized = true;
	}

	@UiThread
	LiveEvent<Boolean> getOnAttachmentReceived(MessageId messageId) {
		return requireNonNull(receivedAttachments.get(messageId));
	}

	void clickImage() {
		imageClicked.setEvent(true);
	}

	LiveEvent<Boolean> getOnImageClicked() {
		return imageClicked;
	}

	void setToolbarPosition(int top, int bottom) {
		toolbarTop = top;
		toolbarBottom = bottom;
	}

	boolean isOverlappingToolbar(View screenView, Drawable drawable) {
		int width = drawable.getIntrinsicWidth();
		int height = drawable.getIntrinsicHeight();
		float widthPercentage = screenView.getWidth() / (float) width;
		float heightPercentage = screenView.getHeight() / (float) height;
		float scaleFactor = Math.min(widthPercentage, heightPercentage);
		int realWidth = (int) (width * scaleFactor);
		int realHeight = (int) (height * scaleFactor);
		if (realWidth < screenView.getWidth()) return false;
		int drawableTop = (screenView.getHeight() - realHeight) / 2;
		return drawableTop < toolbarBottom && drawableTop != toolbarTop;
	}

	static final class SavableImage {

		@Nullable
		private final byte[] data;
		private final String mimeType;

		SavableImage(@Nullable byte[] data, String mimeType) {
			this.data = data;
			this.mimeType = mimeType;
		}

		@Nullable
		byte[] getData() {
			return data;
		}

		String getMimeType() {
			return mimeType;
		}
	}

	LiveEvent<Boolean> getSavableImage() {
		return savableImage;
	}

	@Nullable
	SavableImage takeSavableImage() {
		SavableImage image = readImage;
		readImage = null;
		return image;
	}

	private void offer(SavableImage image) {
		readImage = image;
		savableImage.postEvent(true);
	}

	void readForSaving(AttachmentItem attachment) {
		String mimeType = attachment.getMimeType();
		runOnDbThread(() -> {
			Attachment a;
			try {
				a = attachmentReader.getAttachment(attachment.getHeader());
			} catch (DbException e) {
				offer(new SavableImage(null, mimeType));
				return;
			}
			ioExecutor.execute(() -> {
				try {
					offer(new SavableImage(
							SaveCleanCopy.readAll(a.getStream()), mimeType));
				} catch (IOException e) {
					offer(new SavableImage(null, mimeType));
				}
			});
		});
	}

}
