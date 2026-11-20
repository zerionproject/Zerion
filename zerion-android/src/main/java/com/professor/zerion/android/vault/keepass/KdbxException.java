package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class KdbxException extends Exception {

	public enum Reason {
		UNSUPPORTED,
		WRONG_KEY,
		CORRUPTED,
		TOO_LARGE
	}

	public final Reason reason;

	public KdbxException(Reason reason, String detail) {
		super(detail);
		this.reason = reason;
	}

	static KdbxException unsupported(String detail) {
		return new KdbxException(Reason.UNSUPPORTED, detail);
	}

	static KdbxException wrongKey(String detail) {
		return new KdbxException(Reason.WRONG_KEY, detail);
	}

	static KdbxException corrupted(String detail) {
		return new KdbxException(Reason.CORRUPTED, detail);
	}

	static KdbxException tooLarge(String detail) {
		return new KdbxException(Reason.TOO_LARGE, detail);
	}
}
