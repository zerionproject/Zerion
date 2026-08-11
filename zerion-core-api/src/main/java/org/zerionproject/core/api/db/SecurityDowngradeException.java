package org.zerionproject.core.api.db;

import org.zerionproject.core.api.identity.AuthorId;

public class SecurityDowngradeException extends DbException {

	private final AuthorId remoteAuthorId;
	private final boolean existingWasPostQuantum;
	private final boolean newIsPostQuantum;

	public SecurityDowngradeException(AuthorId remoteAuthorId,
			boolean existingWasPostQuantum, boolean newIsPostQuantum) {
		this.remoteAuthorId = remoteAuthorId;
		this.existingWasPostQuantum = existingWasPostQuantum;
		this.newIsPostQuantum = newIsPostQuantum;
	}

	public AuthorId getRemoteAuthorId() {
		return remoteAuthorId;
	}

	public boolean wasExistingPostQuantum() {
		return existingWasPostQuantum;
	}

	public boolean isNewPostQuantum() {
		return newIsPostQuantum;
	}

	@Override
	public String getMessage() {
		return "Security downgrade attack detected: existing contact used " +
				(existingWasPostQuantum ? "post-quantum" : "classical") +
				" security, new handshake uses " +
				(newIsPostQuantum ? "post-quantum" : "classical");
	}
}
