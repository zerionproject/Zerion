package org.briarproject.bramble.api.crypto.pcs;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Interface for storing and retrieving skipped message keys.
 * <p>
 * When messages arrive out of order, the receiver must advance the chain
 * past the received message number, storing intermediate keys for later
 * decryption of delayed messages. This store manages those skipped keys
 * with bounded storage and automatic expiration.
 * <p>
 * Implementations must:
 * <ul>
 *   <li>Bound the number of stored keys (MAX_SKIP per contact)</li>
 *   <li>Delete keys older than MAX_SKIP_AGE</li>
 *   <li>Delete keys immediately after retrieval (one-time use)</li>
 *   <li>Persist state across app restarts</li>
 * </ul>
 */
@NotNullByDefault
public interface SkippedKeyStore {

	/**
	 * Stores a skipped message key for later retrieval.
	 * <p>
	 * If the store is at capacity (MAX_SKIP keys), the oldest key
	 * is evicted to make room.
	 *
	 * @param chainId Identifier for the chain (e.g., contact ID + direction)
	 * @param messageNumber The message number this key corresponds to
	 * @param messageKey The message key to store
	 * @param timestamp When this key was derived (for expiration)
	 */
	void storeSkippedKey(byte[] chainId, int messageNumber,
			SecretKey messageKey, long timestamp);

	/**
	 * Retrieves and deletes a skipped message key.
	 * <p>
	 * The key is deleted after retrieval to ensure one-time use.
	 *
	 * @param chainId Identifier for the chain
	 * @param messageNumber The message number to look up
	 * @return The message key, or null if not found
	 */
	@Nullable
	SecretKey retrieveAndDeleteSkippedKey(byte[] chainId, int messageNumber);

	/**
	 * Returns the number of skipped keys currently stored for a chain.
	 *
	 * @param chainId Identifier for the chain
	 * @return The number of stored keys
	 */
	int getSkippedKeyCount(byte[] chainId);

	/**
	 * Removes all expired skipped keys (older than MAX_SKIP_AGE).
	 * <p>
	 * This should be called periodically (e.g., on app startup or
	 * during database maintenance).
	 *
	 * @param currentTime The current timestamp in milliseconds
	 * @return The number of keys removed
	 */
	int pruneExpiredKeys(long currentTime);

	/**
	 * Removes all skipped keys for a chain.
	 * <p>
	 * Called when a contact is deleted or conversation is reset.
	 *
	 * @param chainId Identifier for the chain
	 */
	void clearChain(byte[] chainId);
}
