package com.professor.zerion.android.contact.request;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.identity.ReservedNames;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;

import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.util.StringUtils.utf8IsTooLong;

@NotNullByDefault
public final class ContactNames {

	private ContactNames() {
	}

	public static String clean(@Nullable CharSequence input) {
		if (input == null) return "";
		String s = input.toString().trim();
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			int type = Character.getType(c);
			if (type == Character.CONTROL || type == Character.FORMAT
					|| type == Character.SURROGATE
					|| type == Character.PRIVATE_USE) {
				continue;
			}
			sb.append(c);
		}
		return sb.toString().trim();
	}

	@StringRes
	public static int problem(String cleaned, @StringRes int missing) {
		if (cleaned.isEmpty()) return missing;
		if (utf8IsTooLong(cleaned, MAX_AUTHOR_NAME_LENGTH)) {
			return R.string.name_too_long;
		}
		if (ReservedNames.isReserved(cleaned)) return R.string.name_reserved;
		return 0;
	}
}
