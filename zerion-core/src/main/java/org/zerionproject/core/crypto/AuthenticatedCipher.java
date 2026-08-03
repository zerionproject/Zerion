package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

@NotNullByDefault
public interface AuthenticatedCipher {

	void init(boolean encrypt, SecretKey key, byte[] iv)
			throws GeneralSecurityException;

	int process(byte[] input, int inputOff, int len, byte[] output,
			int outputOff) throws GeneralSecurityException;

	int getMacBytes();
}
