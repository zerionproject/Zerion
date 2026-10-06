package org.zerionproject.core.account;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.Nullable;

@NotNullByDefault
final class PasswordForms {

	final char[] normal;
	@Nullable
	final char[] legacy;

	private PasswordForms(char[] normal, @Nullable char[] legacy) {
		this.normal = normal;
		this.legacy = legacy;
	}

	static PasswordForms of(char[] typed) {
		char[] normal = PasswordNormalizer.normalize(typed);
		return new PasswordForms(normal,
				PasswordNormalizer.legacyForm(typed, normal));
	}

	int count() {
		return legacy == null ? 1 : 2;
	}

	void clear() {
		Arrays.fill(normal, '\0');
		if (legacy != null) Arrays.fill(legacy, '\0');
	}
}
