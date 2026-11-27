package org.zerionproject.core.contact;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
final class HandshakeKeys {

	private final SecretKey confirmationKey;
	private final SecretKey exchangeKey;
	private final SecretKey rootKey;
	private final boolean separated;

	private HandshakeKeys(SecretKey confirmationKey, SecretKey exchangeKey,
			SecretKey rootKey, boolean separated) {
		this.confirmationKey = confirmationKey;
		this.exchangeKey = exchangeKey;
		this.rootKey = rootKey;
		this.separated = separated;
	}

	static HandshakeKeys legacy(SecretKey masterKey) {
		return new HandshakeKeys(masterKey, masterKey, masterKey, false);
	}

	static HandshakeKeys separated(SecretKey confirmationKey,
			SecretKey exchangeKey, SecretKey rootKey) {
		return new HandshakeKeys(confirmationKey, exchangeKey, rootKey, true);
	}

	SecretKey getConfirmationKey() {
		return confirmationKey;
	}

	SecretKey getExchangeKey() {
		return exchangeKey;
	}

	SecretKey getRootKey() {
		return rootKey;
	}

	boolean isSeparated() {
		return separated;
	}

	void clear() {
		confirmationKey.clear();
		exchangeKey.clear();
		rootKey.clear();
	}
}
