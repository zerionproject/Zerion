package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Typed, fail-closed error model for the XMR wallet layer. Distinct causes are
 * kept distinct so a corrupted item or a native failure is never reported as a
 * "wrong password". No failed operation may navigate into the wallet.
 */
@NotNullByDefault
public enum XmrError {
	WRONG_PASSWORD,
	EMPTY_PASSWORD,
	CANCELLED,
	MALFORMED_SEED,
	CORRUPTED_ITEM,
	NATIVE_UNAVAILABLE,
	NATIVE_CREATE_FAILED,
	NATIVE_OPEN_FAILED,
	STORAGE_COMMIT_FAILED,
	SESSION_INVALIDATED,
	BUSY,
	SEND_SNAPSHOT_INVALID,
	AUTHORIZATION_INVALID,
	TRANSACTION_MUTATED,
	SPEND_QUARANTINED,
	JOURNAL_CORRUPTED,
	WALLET_NEEDS_PASSWORD,
	NODE_UNREACHABLE,
	UNKNOWN;

	public static final class XmrException extends Exception {
		public final XmrError error;

		public XmrException(XmrError error) {
			super(error.name());
			this.error = error;
		}

		public XmrException(XmrError error, Throwable cause) {
			super(error.name(), cause);
			this.error = error;
		}
	}
}
