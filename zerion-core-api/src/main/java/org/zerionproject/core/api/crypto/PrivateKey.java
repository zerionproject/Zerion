package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface PrivateKey {

	String getKeyType();

	byte[] getEncoded();
}
