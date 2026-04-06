package com.professor.zerion.android.conversation;

import com.professor.zerion.R;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationSecretNoteItem extends ConversationItem {

	private static final String PREFIX = "SECRET:";

	private boolean revealed = false;

	ConversationSecretNoteItem(PrivateMessageHeader h, LiveData<String> contactName) {
		super(h.isLocal()
						? R.layout.list_item_conversation_secret_out
						: R.layout.list_item_conversation_secret_in,
				h, contactName);
	}

	boolean isRevealed() {
		return revealed;
	}

	@UiThread
	void markRevealed() {
		revealed = true;
	}

	@Nullable
	String getSecretContent() {
		String raw = getText();
		if (raw != null && raw.startsWith(PREFIX)) {
			return raw.substring(PREFIX.length());
		}
		return raw;
	}

	static boolean isSecretNoteText(@Nullable String text) {
		return text != null && text.startsWith(PREFIX);
	}

	static String wrapContent(String text) {
		return PREFIX + text;
	}

}
