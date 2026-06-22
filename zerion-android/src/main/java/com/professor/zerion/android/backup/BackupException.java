package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class BackupException extends Exception {

	public enum Reason {
		NOT_A_BACKUP,
		UNSUPPORTED_VERSION,
		WRONG_PASSPHRASE,
		CORRUPT,
		NOT_SIGNED_IN,
		IO_ERROR,
		IMPORT_FAILED
	}

	public final Reason reason;

	public BackupException(Reason reason) {
		this.reason = reason;
	}
}
