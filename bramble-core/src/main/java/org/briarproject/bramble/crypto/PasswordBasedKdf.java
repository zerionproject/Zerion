package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.SecretKey;

interface PasswordBasedKdf {

	int chooseCostParameter();

	SecretKey deriveKey(char[] password, byte[] salt, int cost);
}
