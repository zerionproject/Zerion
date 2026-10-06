package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

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
