package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.account.PasswordNormalizer;

@NotNullByDefault
public final class BackupPassphrase {

	public static final int MIN_LENGTH = 8;

	private BackupPassphrase() {
	}

	public static boolean longEnough(char[] passphrase) {
		char[] normal = PasswordNormalizer.normalize(passphrase);
		try {
			return normal.length >= MIN_LENGTH;
		} finally {
			java.util.Arrays.fill(normal, '\0');
		}
	}
}
