package com.professor.zerion.android.conversation;

import android.view.View;

import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.selection.SelectionAction;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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

	void onMessageClick(ConversationItem item);

	void onReactionClicked(ConversationItem item);

	void onQuoteClicked(org.zerionproject.core.api.sync.MessageId quoted);

	void onSecretNoteOpened(org.zerionproject.core.api.sync.MessageId messageId);

	void onSecretNoteRevealed(org.zerionproject.core.api.sync.MessageId messageId);

	void onSecretNoteRevealing(boolean revealing);

	List<AttachmentItem> loadAttachmentsForItem(ConversationMessageItem item);

	@androidx.annotation.Nullable
	String getReassembledVoiceMessage(boolean local, String memoId);

	boolean isVoiceMemoFailed(boolean local, String memoId);

	default Set<SelectionAction> rowActions(ConversationItem item) {
		return EnumSet.noneOf(SelectionAction.class);
	}

	default boolean onRowAction(ConversationItem item,
			SelectionAction action) {
		return false;
	}

}
