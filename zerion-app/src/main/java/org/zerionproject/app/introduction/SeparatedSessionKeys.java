package org.zerionproject.app.introduction;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
final class SeparatedSessionKeys {

	private final SecretKey rootKey;
	private final SecretKey aliceActivateKey;
	private final SecretKey bobActivateKey;

	SeparatedSessionKeys(SecretKey rootKey, SecretKey aliceActivateKey,
			SecretKey bobActivateKey) {
		this.rootKey = rootKey;
		this.aliceActivateKey = aliceActivateKey;
		this.bobActivateKey = bobActivateKey;
	}

	SecretKey getRootKey() {
		return rootKey;
	}

	SecretKey getAliceActivateKey() {
		return aliceActivateKey;
	}

	SecretKey getBobActivateKey() {
		return bobActivateKey;
	}
}
