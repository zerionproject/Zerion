package com.professor.zerion.android.conversation;

import android.content.Context;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;

@NotNullByDefault
public final class SecretNotes {

	static final String PREFIX = "SECRET:";

	private SecretNotes() {
	}

	public static boolean isSecret(@Nullable String text) {
		return text != null && text.startsWith(PREFIX);
	}

	public static String quotable(Context ctx, String text) {
		return isSecret(text) ? ctx.getString(R.string.secret_note_label)
				: text;
	}
}
