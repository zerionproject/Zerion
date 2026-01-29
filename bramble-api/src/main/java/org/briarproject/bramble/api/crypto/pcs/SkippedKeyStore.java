package org.briarproject.bramble.api.crypto.pcs;

import org.briarproject.bramble.api.crypto.SecretKey;
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
