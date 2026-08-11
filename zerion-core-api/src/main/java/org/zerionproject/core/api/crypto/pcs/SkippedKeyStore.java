package org.zerionproject.core.api.crypto.pcs;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface SkippedKeyStore {

	void storeSkippedKey(byte[] chainId, int messageNumber,
			SecretKey messageKey, long timestamp);

	@Nullable
	SecretKey retrieveAndDeleteSkippedKey(byte[] chainId, int messageNumber);

	int getSkippedKeyCount(byte[] chainId);

	int pruneExpiredKeys(long currentTime);

	void clearChain(byte[] chainId);
}
