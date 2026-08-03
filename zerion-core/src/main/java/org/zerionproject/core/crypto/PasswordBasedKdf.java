package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.SecretKey;

interface PasswordBasedKdf {

	int chooseCostParameter();

	SecretKey deriveKey(char[] password, byte[] salt, int cost);
}
