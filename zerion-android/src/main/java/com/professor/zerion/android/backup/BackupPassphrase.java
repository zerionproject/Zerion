package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The backup file seals the database key and the whole database behind the
 * passphrase alone, and its verification tag is a fast offline oracle by
 * design, so a passphrase shorter than a profile password is refused.
 */
@NotNullByDefault
public final class BackupPassphrase {

	public static final int MIN_LENGTH = 8;

	private BackupPassphrase() {
	}

	public static boolean longEnough(char[] passphrase) {
		return passphrase.length >= MIN_LENGTH;
	}
}
