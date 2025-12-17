package org.briarproject.bramble.api.db;

import org.briarproject.bramble.api.identity.AuthorId;

/**
 * Thrown when attempting to establish a contact with lower security than
 * a previous contact with the same remote author. This prevents downgrade
 * attacks where an attacker tries to force a classical (non-PQ) handshake
 * with a contact that was previously established with post-quantum security.
 */
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
