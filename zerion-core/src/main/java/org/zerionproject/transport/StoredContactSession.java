package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class StoredContactSession {

	private final ContactRootKeys rootKeys;
	private final boolean alice;
	private final long generation;

	public StoredContactSession(SecretKey rootKey, boolean alice) {
		this(rootKey, alice, 0);
	}

	public StoredContactSession(SecretKey rootKey, boolean alice,
			long generation) {
		this(ContactRootKeys.atPairing(rootKey), alice, generation);
	}

	public StoredContactSession(ContactRootKeys rootKeys, boolean alice,
			long generation) {
		this.rootKeys = rootKeys;
		this.alice = alice;
		this.generation = generation;
	}

	public SecretKey getRootKey() {
		SecretKey k = rootKeys.getKey(rootKeys.getSendEpoch());
		return k == null ? rootKeys.getCurrent() : k;
	}

	public ContactRootKeys getRootKeys() {
		return rootKeys;
	}

	public boolean isAlice() {
		return alice;
	}

	public long getGeneration() {
		return generation;
	}
}
