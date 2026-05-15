package com.professor.zerion.android.conversation;

import android.view.View;

import com.professor.zerion.android.attachment.AttachmentItem;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

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

	void onReactionClicked(ConversationItem item);

	void onSecretNoteOpened(org.briarproject.bramble.api.sync.MessageId messageId);

	void onSecretNoteRevealing(boolean revealing);

	List<AttachmentItem> loadAttachmentsForItem(ConversationMessageItem item);

}
