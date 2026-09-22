package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Recognises the text prefix of the in-band call signal that older releases
 * stored as private-message text, so such rows are hidden from the
 * conversation rather than shown as text. Nothing parses or acts on these
 * signals any more: call signalling travels only as authenticated
 * VOICE_SIGNAL records.
 */
@NotNullByDefault
public final class LegacyCallSignal {

	private static final String WIRE_PREFIX = "\u0000ZSIG\u0001\u0000";
	private static final String EVENT_PREFIX = "VOICE_CALL:";

	private LegacyCallSignal() {
	}

	public static boolean isSignal(@Nullable String text) {
		return text != null && text.startsWith(WIRE_PREFIX);
	}

	public static boolean isCallEventText(@Nullable String text) {
		return text != null && text.startsWith(EVENT_PREFIX);
	}
}
