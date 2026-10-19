package com.professor.zerion.android.conversation;

import com.professor.zerion.android.attachment.AttachmentItem;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
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
	private boolean edited;

	ConversationMessageItem(@LayoutRes int layoutRes, PrivateMessageHeader h,
			LiveData<String> contactName, List<AttachmentItem> attachments) {
		super(layoutRes, h, contactName);
		this.attachments = attachments;
		this.header = h;
		this.edited = h.isEdited();
		this.attachmentsLoaded = !attachments.isEmpty() || h.getAttachmentHeaders().isEmpty();
	}

	ConversationMessageItem(@LayoutRes int layoutRes, PrivateMessageHeader h,
			LiveData<String> contactName) {
		super(layoutRes, h, contactName);
		this.attachments = new ArrayList<>();
		this.header = h;
		this.edited = h.isEdited();
		this.attachmentsLoaded = h.getAttachmentHeaders().isEmpty();
	}

	List<AttachmentItem> getAttachments() {
		return attachments;
	}

	@Override
	int updateFrom(ConversationItem fresh) {
		int change = super.updateFrom(fresh);
		ConversationMessageItem m = (ConversationMessageItem) fresh;
		if (edited != m.edited) change = CONTENT_CHANGED;
		edited = m.edited;
		if (m.attachmentsLoaded) {
			if (!attachmentsLoaded
					|| !sameAttachments(attachments, m.attachments)) {
				change = CONTENT_CHANGED;
			}
			attachments = m.attachments;
			attachmentsLoaded = true;
		}
		return change;
	}

	@Override
	boolean showsTheSameAs(ConversationItem o) {
		if (!super.showsTheSameAs(o)) return false;
		ConversationMessageItem m = (ConversationMessageItem) o;
		return edited == m.edited && attachmentsLoaded == m.attachmentsLoaded
				&& sameAttachments(attachments, m.attachments)
				&& (header == null ? m.header == null : m.header != null
						&& header.isMesh() == m.header.isMesh());
	}

	private static boolean sameAttachments(List<AttachmentItem> a,
			List<AttachmentItem> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			AttachmentItem x = a.get(i);
			AttachmentItem y = b.get(i);
			if (!x.equals(y) || x.getState() != y.getState()) return false;
		}
		return true;
	}

	boolean needsAttachmentLoading() {
		return !attachmentsLoaded && header != null && !header.getAttachmentHeaders().isEmpty();
	}

	@Nullable
	PrivateMessageHeader getHeader() {
		return header;
	}

	boolean isEdited() {
		return edited;
	}

	@UiThread
	void markEdited() {
		edited = true;
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
