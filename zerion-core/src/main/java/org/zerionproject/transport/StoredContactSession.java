package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The stored inputs needed to resume an ongoing contact's connection without
 * re-running the handshake: the handshake root key and our role, both fixed at
 * pairing, plus the Mode 3-Full ratchet state persisted after the previous
 * connection ended.
 */
@NotNullByDefault
public class StoredContactSession {

	private final SecretKey rootKey;
	private final boolean alice;
	private final Mode3FullState mode3FullState;

	public StoredContactSession(SecretKey rootKey, boolean alice,
			Mode3FullState mode3FullState) {
		this.rootKey = rootKey;
		this.alice = alice;
		this.mode3FullState = mode3FullState;
	}

	public SecretKey getRootKey() {
		return rootKey;
	}

	public boolean isAlice() {
		return alice;
	}

	public Mode3FullState getMode3FullState() {
		return mode3FullState;
	}
}
