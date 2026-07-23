package org.zerionproject.core.api.identity;

import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface AuthorFactory {

	Author createAuthor(String name, PublicKey publicKey);

	Author createAuthor(int formatVersion, String name, PublicKey publicKey);

	LocalAuthor createLocalAuthor(String name);
}
