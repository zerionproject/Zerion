package com.professor.zerion.android.conversation;

import android.view.View;

import com.professor.zerion.android.attachment.AttachmentItem;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.UiThread;

@UiThread
@NotNullByDefault
interface ConversationListener {

	void respondToRequest(ConversationRequestItem item, boolean accept);

	void openRequestedShareable(ConversationRequestItem item);

	void onAttachmentClicked(View view, ConversationMessageItem messageItem,
			AttachmentItem attachmentItem);

	void onAutoDeleteTimerNoticeClicked();

	void onLinkClick(String url);

	void onMessageLongClick(ConversationItem item);

	void onSwipeToReply(ConversationItem item);

}
