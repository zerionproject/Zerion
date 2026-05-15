package org.briarproject.bramble.api.identity;

import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;

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
