package com.professor.zerion.android.conversation;

import com.professor.zerion.R;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

@NotThreadSafe
@NotNullByDefault
class ConversationSecretNoteItem extends ConversationItem {

	private static final String PREFIX = "SECRET:";
	private static final int MIN_COUNTDOWN_SECONDS = 1;
	private static final int MAX_COUNTDOWN_SECONDS = 43200;
	private static final int DEFAULT_COUNTDOWN_SECONDS = 10;

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
					int seconds = Integer.parseInt(payload.substring(0, sep));
					return Math.max(MIN_COUNTDOWN_SECONDS,
							Math.min(MAX_COUNTDOWN_SECONDS, seconds));
				} catch (NumberFormatException e) {
					return DEFAULT_COUNTDOWN_SECONDS;
				}
			}
		}
		return DEFAULT_COUNTDOWN_SECONDS;
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
