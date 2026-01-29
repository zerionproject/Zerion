package org.briarproject.bramble.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;


@NotNullByDefault
public interface PublicKey {

	
	String getKeyType();

	
	byte[] getEncoded();
}
