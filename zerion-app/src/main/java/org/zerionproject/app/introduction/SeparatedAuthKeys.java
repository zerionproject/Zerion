package org.zerionproject.app.introduction;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
final class SeparatedAuthKeys {

	private final SecretKey macKey;
	private final SecretKey nonceKey;

	SeparatedAuthKeys(SecretKey macKey, SecretKey nonceKey) {
		this.macKey = macKey;
		this.nonceKey = nonceKey;
	}

	SecretKey getMacKey() {
		return macKey;
	}

	SecretKey getNonceKey() {
		return nonceKey;
	}

	void clear() {
		macKey.clear();
		nonceKey.clear();
	}
}
