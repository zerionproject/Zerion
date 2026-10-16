package com.professor.zerion.android.util;

import android.text.InputFilter;
import android.text.Spanned;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

@NotNullByDefault
public final class Utf8ByteLengthFilter implements InputFilter {

	private final int maxBytes;

	public Utf8ByteLengthFilter(int maxBytes) {
		this.maxBytes = maxBytes;
	}

	@Nullable
	@Override
	public CharSequence filter(CharSequence source, int start, int end,
			Spanned dest, int dstart, int dend) {
		int room = maxBytes - bytes(dest.subSequence(0, dstart))
				- bytes(dest.subSequence(dend, dest.length()));
		CharSequence added = source.subSequence(start, end);
		if (bytes(added) <= room) return null;
		int used = 0;
		int i = 0;
		while (i < added.length()) {
			int n = Character.charCount(Character.codePointAt(added, i));
			int b = bytes(added.subSequence(i, i + n));
			if (used + b > room) break;
			used += b;
			i += n;
		}
		return added.subSequence(0, i);
	}

	static int bytes(CharSequence s) {
		return s.toString().getBytes(StandardCharsets.UTF_8).length;
	}
}
