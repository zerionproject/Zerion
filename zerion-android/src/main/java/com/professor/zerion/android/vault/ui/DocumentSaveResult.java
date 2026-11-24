package com.professor.zerion.android.vault.ui;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class DocumentSaveResult {

	public enum Outcome {
		SAVED,
		SAVED_EARLIER_VERSION_KEPT,
		PASSWORD_REQUIRED,
		WRONG_PASSWORD,
		FAILED
	}

	public final Outcome outcome;
	public final String message;
	@Nullable
	public final String itemId;

	DocumentSaveResult(Outcome outcome, String message,
			@Nullable String itemId) {
		this.outcome = outcome;
		this.message = message;
		this.itemId = itemId;
	}

	public boolean isStored() {
		return outcome == Outcome.SAVED
				|| outcome == Outcome.SAVED_EARLIER_VERSION_KEPT;
	}
}
