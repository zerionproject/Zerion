package com.professor.zerion.android.vault.ui;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class NoteSaveResult {

	public enum Outcome {
		SAVED,
		SAVED_EARLIER_VERSION_KEPT,
		WRONG_PASSWORD,
		FAILED
	}

	public final long request;
	public final Outcome outcome;
	public final String message;

	NoteSaveResult(long request, Outcome outcome, String message) {
		this.request = request;
		this.outcome = outcome;
		this.message = message;
	}

	public boolean isStored() {
		return outcome == Outcome.SAVED
				|| outcome == Outcome.SAVED_EARLIER_VERSION_KEPT;
	}
}
