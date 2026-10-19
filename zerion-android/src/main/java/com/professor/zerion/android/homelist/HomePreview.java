package com.professor.zerion.android.homelist;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class HomePreview {

	public static final int MAX_CHARS = 80;
	private static final String ELLIPSIS = "…";

	private HomePreview() {
	}

	public static String oneLine(@Nullable String text) {
		if (text == null) return "";
		String flat = text.replace('\n', ' ').replace('\r', ' ').trim();
		if (flat.length() <= MAX_CHARS) return flat;
		int end = MAX_CHARS;
		if (Character.isHighSurrogate(flat.charAt(end - 1))) end--;
		return flat.substring(0, end) + ELLIPSIS;
	}
}
