package com.professor.zerion.android.conversation;

import com.professor.zerion.android.attachment.AttachmentItem;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.LayoutRes;
import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationMessageItem extends ConversationItem {
	private List<AttachmentItem> attachments;
	@Nullable
	private final PrivateMessageHeader header;
	private boolean attachmentsLoaded = false;

	ConversationMessageItem(@LayoutRes int layoutRes, PrivateMessageHeader h,
			LiveData<String> contactName, List<AttachmentItem> attachments) {
		super(layoutRes, h, contactName);
		this.attachments = attachments;
		this.header = h;
		this.attachmentsLoaded = !attachments.isEmpty() || h.getAttachmentHeaders().isEmpty();
	}

	
	ConversationMessageItem(@LayoutRes int layoutRes, PrivateMessageHeader h,
			LiveData<String> contactName) {
		super(layoutRes, h, contactName);
		this.attachments = new ArrayList<>();
		this.header = h;
		this.attachmentsLoaded = h.getAttachmentHeaders().isEmpty();
	}

	List<AttachmentItem> getAttachments() {
		return attachments;
	}

	
	boolean needsAttachmentLoading() {
		return !attachmentsLoaded && header != null && !header.getAttachmentHeaders().isEmpty();
	}

	
	@Nullable
	PrivateMessageHeader getHeader() {
		return header;
	}

	
	@UiThread
	void setAttachments(List<AttachmentItem> attachments) {
		this.attachments = attachments;
		this.attachmentsLoaded = true;
	}

	@UiThread
	boolean updateAttachments(AttachmentItem item) {
		int pos = attachments.indexOf(item);
		if (pos != -1 && attachments.get(pos).getState() != item.getState()) {
			attachments.set(pos, item);
			return true;
		}
		return false;
	}

}
