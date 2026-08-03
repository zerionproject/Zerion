package org.zerionproject.core.api.crypto.pcs;

import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class DhRatchetState {

	private final KeyPair dhKeyPair;

	@Nullable
	private final PublicKey dhRemotePublicKey;

	public DhRatchetState(KeyPair dhKeyPair,
			@Nullable PublicKey dhRemotePublicKey) {
		this.dhKeyPair = dhKeyPair;
		this.dhRemotePublicKey = dhRemotePublicKey;
	}

	public KeyPair getDhKeyPair() {
		return dhKeyPair;
	}

	public PublicKey getDhPublicKey() {
		return dhKeyPair.getPublic();
	}

	@Nullable
	public PublicKey getDhRemotePublicKey() {
		return dhRemotePublicKey;
	}

	public boolean hasRemotePublicKey() {
		return dhRemotePublicKey != null;
	}

	public DhRatchetState withRemotePublicKey(PublicKey newRemotePublicKey) {
		return new DhRatchetState(dhKeyPair, newRemotePublicKey);
	}

	public DhRatchetState withNewKeyPair(KeyPair newKeyPair) {
		return new DhRatchetState(newKeyPair, dhRemotePublicKey);
	}

	public DhRatchetState withNewKeyPairAndRemote(KeyPair newKeyPair,
			PublicKey newRemotePublicKey) {
		return new DhRatchetState(newKeyPair, newRemotePublicKey);
	}
}
