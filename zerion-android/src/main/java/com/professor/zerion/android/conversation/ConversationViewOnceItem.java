package com.professor.zerion.android.conversation;

import com.professor.zerion.R;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.app.api.messaging.ViewOnceMedia;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationViewOnceItem extends ConversationItem {

	private final boolean video;
	private boolean opened;

	ConversationViewOnceItem(PrivateMessageHeader h,
			LiveData<String> contactName) {
		super(h.isLocal()
						? R.layout.list_item_conversation_view_once_out
						: R.layout.list_item_conversation_view_once_in,
				h, contactName);
		List<AttachmentHeader> headers = h.getAttachmentHeaders();
		video = !headers.isEmpty() && ViewOnceMedia.isVideoType(
				headers.get(0).getContentType());
		opened = h.isViewOnceOpened();
	}

	boolean isVideo() {
		return video;
	}

	boolean isOpened() {
		return opened;
	}

	boolean canOpen() {
		return isIncoming() && !opened;
	}

	@UiThread
	void markOpened() {
		opened = true;
	}

	@Override
	void setText(String text) {
	}

	@Override
	int updateFrom(ConversationItem fresh) {
		int change = super.updateFrom(fresh);
		ConversationViewOnceItem v = (ConversationViewOnceItem) fresh;
		if (v.opened && !opened) {
			opened = true;
			change = CONTENT_CHANGED;
		}
		return change;
	}

	@Override
	boolean showsTheSameAs(ConversationItem o) {
		return super.showsTheSameAs(o)
				&& opened == ((ConversationViewOnceItem) o).opened;
	}
}
