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

	int getCountdownSeconds() {
		String raw = getText();
		if (raw != null && raw.startsWith(PREFIX)) {
			String payload = raw.substring(PREFIX.length());
			int sep = payload.indexOf(':');
			if (sep > 0) {
				try {
					return Integer.parseInt(payload.substring(0, sep));
				} catch (NumberFormatException e) {
					return 10;
				}
			}
		}
		return 10;
	}

	@Nullable
	String getSecretContent() {
		String raw = getText();
		if (raw != null && raw.startsWith(PREFIX)) {
			String payload = raw.substring(PREFIX.length());
			int sep = payload.indexOf(':');
			if (sep >= 0) {
				return payload.substring(sep + 1);
			}
			return payload;
		}
		return raw;
	}

	static boolean isSecretNoteText(@Nullable String text) {
		return text != null && text.startsWith(PREFIX);
	}

	@UiThread
	void clearSecretContent() {
		setText(null);
	}

	static String wrapContent(String text, int countdownSeconds) {
		return PREFIX + countdownSeconds + ":" + text;
	}

}
