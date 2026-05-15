package org.briarproject.bramble.api.identity;

import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface AuthorFactory {

	Author createAuthor(String name, PublicKey publicKey);

	Author createAuthor(int formatVersion, String name, PublicKey publicKey);

	LocalAuthor createLocalAuthor(String name);
}
