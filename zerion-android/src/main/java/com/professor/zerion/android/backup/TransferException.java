package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class TransferException extends Exception {

	public enum Reason {
		CONNECT_FAILED,
		CANCELLED,
		PROTOCOL,
		IMPORT_FAILED,
		IO_ERROR
	}

	public final Reason reason;

	public TransferException(Reason reason) {
		this.reason = reason;
	}
}
