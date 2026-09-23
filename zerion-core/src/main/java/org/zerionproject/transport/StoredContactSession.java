package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The stored inputs needed to resume an ongoing contact's connection without
 * re-running the handshake: the handshake root key and our role, both fixed at
 * pairing. Nothing else carries across connections; the post-quantum ratchet
 * starts fresh on each one.
 */
@NotNullByDefault
public class StoredContactSession {

	private final SecretKey rootKey;
	private final boolean alice;

	public StoredContactSession(SecretKey rootKey, boolean alice) {
		this.rootKey = rootKey;
		this.alice = alice;
	}

	public SecretKey getRootKey() {
		return rootKey;
	}

	public boolean isAlice() {
		return alice;
	}
}
