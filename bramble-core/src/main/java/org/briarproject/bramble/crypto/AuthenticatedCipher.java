package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

@NotNullByDefault
interface AuthenticatedCipher {

	void init(boolean encrypt, SecretKey key, byte[] iv)
			throws GeneralSecurityException;

	int process(byte[] input, int inputOff, int len, byte[] output,
			int outputOff) throws GeneralSecurityException;

	int getMacBytes();
}
