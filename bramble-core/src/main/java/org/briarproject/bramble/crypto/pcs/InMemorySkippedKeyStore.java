package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.Bytes;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MAX_SKIP;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MAX_SKIP_AGE_MS;

/**
 * In-memory implementation of SkippedKeyStore for testing and development.
 * <p>
 * This implementation stores skipped keys in memory with bounded storage
 * and automatic expiration. For production use, this should be replaced
 * with a persistent database-backed implementation.
 * <p>
 * Thread-safe through synchronized access.
 */
@ThreadSafe
@NotNullByDefault
public class InMemorySkippedKeyStore implements SkippedKeyStore {

	/**
	 * Entry containing a skipped message key and its timestamp.
	 */
	private static class SkippedKeyEntry {
		final SecretKey messageKey;
		final long timestamp;

		SkippedKeyEntry(SecretKey messageKey, long timestamp) {
			this.messageKey = messageKey;
			this.timestamp = timestamp;
		}
	}

	/**
	 * Key for looking up skipped keys: (chainId, messageNumber).
	 */
	private static class SkippedKeyId {
		final Bytes chainId;
		final int messageNumber;

		SkippedKeyId(byte[] chainId, int messageNumber) {
			this.chainId = new Bytes(chainId);
			this.messageNumber = messageNumber;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof SkippedKeyId)) return false;
			SkippedKeyId that = (SkippedKeyId) o;
			return messageNumber == that.messageNumber &&
					chainId.equals(that.chainId);
		}

		@Override
		public int hashCode() {
			return 31 * chainId.hashCode() + messageNumber;
		}
	}

	@GuardedBy("this")
	private final Map<SkippedKeyId, SkippedKeyEntry> skippedKeys;

	@GuardedBy("this")
	private final Map<Bytes, Integer> keysPerChain;

	public InMemorySkippedKeyStore() {
		// Use LinkedHashMap to maintain insertion order for LRU eviction
		this.skippedKeys = new LinkedHashMap<>(16, 0.75f, true);
		this.keysPerChain = new HashMap<>();
	}

	@Override
	public synchronized void storeSkippedKey(byte[] chainId, int messageNumber,
			SecretKey messageKey, long timestamp) {
		Bytes chainIdBytes = new Bytes(chainId);
		SkippedKeyId keyId = new SkippedKeyId(chainId, messageNumber);

		// Check if we need to evict oldest keys
		int chainCount = keysPerChain.getOrDefault(chainIdBytes, 0);
		if (chainCount >= MAX_SKIP) {
			evictOldestKey(chainIdBytes);
		}

		// Store the new key
		SkippedKeyEntry entry = new SkippedKeyEntry(messageKey, timestamp);
		SkippedKeyEntry previous = skippedKeys.put(keyId, entry);

		// Update count if this is a new key (not replacement)
		if (previous == null) {
			keysPerChain.put(chainIdBytes, chainCount + 1);
		}
	}

	@Override
	@Nullable
	public synchronized SecretKey retrieveAndDeleteSkippedKey(byte[] chainId,
			int messageNumber) {
		SkippedKeyId keyId = new SkippedKeyId(chainId, messageNumber);
		SkippedKeyEntry entry = skippedKeys.remove(keyId);

		if (entry != null) {
			// Decrement the count for this chain
			Bytes chainIdBytes = new Bytes(chainId);
			int count = keysPerChain.getOrDefault(chainIdBytes, 1);
			if (count <= 1) {
				keysPerChain.remove(chainIdBytes);
			} else {
				keysPerChain.put(chainIdBytes, count - 1);
			}
			return entry.messageKey;
		}

		return null;
	}

	@Override
	public synchronized int getSkippedKeyCount(byte[] chainId) {
		return keysPerChain.getOrDefault(new Bytes(chainId), 0);
	}

	@Override
	public synchronized int pruneExpiredKeys(long currentTime) {
		int removed = 0;
		long expirationThreshold = currentTime - MAX_SKIP_AGE_MS;

		Iterator<Map.Entry<SkippedKeyId, SkippedKeyEntry>> iterator =
				skippedKeys.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<SkippedKeyId, SkippedKeyEntry> entry = iterator.next();
			if (entry.getValue().timestamp < expirationThreshold) {
				iterator.remove();

				// Update chain count
				Bytes chainIdBytes = entry.getKey().chainId;
				int count = keysPerChain.getOrDefault(chainIdBytes, 1);
				if (count <= 1) {
					keysPerChain.remove(chainIdBytes);
				} else {
					keysPerChain.put(chainIdBytes, count - 1);
				}

				removed++;
			}
		}

		return removed;
	}

	@Override
	public synchronized void clearChain(byte[] chainId) {
		Bytes chainIdBytes = new Bytes(chainId);

		// Remove all keys for this chain
		skippedKeys.entrySet().removeIf(
				entry -> entry.getKey().chainId.equals(chainIdBytes));

		keysPerChain.remove(chainIdBytes);
	}

	/**
	 * Evicts the oldest key for the given chain to make room for a new one.
	 * Must be called while synchronized.
	 */
	@GuardedBy("this")
	private void evictOldestKey(Bytes chainIdBytes) {
		// Find the oldest entry for this chain (lowest message number)
		SkippedKeyId oldestId = null;
		long oldestTimestamp = Long.MAX_VALUE;

		for (Map.Entry<SkippedKeyId, SkippedKeyEntry> entry :
				skippedKeys.entrySet()) {
			if (entry.getKey().chainId.equals(chainIdBytes) &&
					entry.getValue().timestamp < oldestTimestamp) {
				oldestId = entry.getKey();
				oldestTimestamp = entry.getValue().timestamp;
			}
		}

		if (oldestId != null) {
			skippedKeys.remove(oldestId);
			int count = keysPerChain.getOrDefault(chainIdBytes, 1);
			keysPerChain.put(chainIdBytes, count - 1);
		}
	}

	/**
	 * Returns the total number of skipped keys stored across all chains.
	 * For testing and monitoring purposes.
	 */
	public synchronized int getTotalSkippedKeyCount() {
		return skippedKeys.size();
	}
}
