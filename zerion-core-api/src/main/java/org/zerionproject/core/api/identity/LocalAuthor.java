package org.zerionproject.core.api.identity;

import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;

@Immutable
@NotNullByDefault
public class LocalAuthor extends Author {

	private final PrivateKey privateKey;

	public LocalAuthor(AuthorId id, int formatVersion, String name,
			PublicKey publicKey, PrivateKey privateKey) {
		super(id, formatVersion, name, publicKey);
		if (!privateKey.getKeyType().equals(KEY_TYPE_SIGNATURE))
			throw new IllegalArgumentException();
		this.privateKey = privateKey;
	}

	public PrivateKey getPrivateKey() {
		return privateKey;
	}
}
