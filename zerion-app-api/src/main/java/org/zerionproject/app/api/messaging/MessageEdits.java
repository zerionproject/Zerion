package org.zerionproject.app.api.messaging;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import static org.zerionproject.app.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
import static org.zerionproject.app.api.messaging.MessagingManager.EDIT_WINDOW_MS;

@NotNullByDefault
public final class MessageEdits {

	private static final String[] SPECIAL_PREFIXES = {
			"SECRET:",
			"VOICE_CALL:",
			"[VOICE:",
			"[VMP:",
			"\u0000ZSIG\u0001\u0000"
	};

	private MessageEdits() {
	}

	public static boolean isEditableText(@Nullable String text) {
		if (text == null || text.trim().isEmpty()) return false;
		if (text.length() > MAX_PRIVATE_MESSAGE_TEXT_LENGTH) return false;
		for (String prefix : SPECIAL_PREFIXES) {
			if (text.startsWith(prefix)) return false;
		}
		return true;
	}

	public static boolean withinEditWindow(long sentAt, long editedAt) {
		return editedAt > sentAt && editedAt - sentAt <= EDIT_WINDOW_MS;
	}
}
