package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MAX_SKIP;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MAX_SKIP_AGE_MS;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MAX_TOTAL_SKIP;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.SKIP_CLOCK_REWIND_THRESHOLD_MS;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.SKIP_PRUNE_INTERVAL_MS;

@ThreadSafe
@NotNullByDefault
public class InMemorySkippedKeyStore implements SkippedKeyStore {

	private static class SkippedKeyEntry {
		final SecretKey messageKey;
		final long timestamp;

		SkippedKeyEntry(SecretKey messageKey, long timestamp) {
			this.messageKey = messageKey;
			this.timestamp = timestamp;
		}
	}

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

	@GuardedBy("this")
	private long lastPruneMs = 0L;

	@GuardedBy("this")
	private long highestSeenTimestamp = 0L;

	public InMemorySkippedKeyStore() {
		this.skippedKeys = new LinkedHashMap<>(16, 0.75f, true);
		this.keysPerChain = new HashMap<>();
	}

	@Override
	public synchronized void storeSkippedKey(byte[] chainId, int messageNumber,
			SecretKey messageKey, long timestamp) {
		if (highestSeenTimestamp > 0
				&& highestSeenTimestamp - timestamp
						> SKIP_CLOCK_REWIND_THRESHOLD_MS) {
			skippedKeys.clear();
			keysPerChain.clear();
			highestSeenTimestamp = timestamp;
			lastPruneMs = timestamp;
			return;
		}
		if (timestamp > highestSeenTimestamp) {
			highestSeenTimestamp = timestamp;
		}
		if (timestamp - lastPruneMs > SKIP_PRUNE_INTERVAL_MS) {
			pruneExpiredLocked(timestamp);
			lastPruneMs = timestamp;
		}
		if (skippedKeys.size() >= MAX_TOTAL_SKIP) {
			evictGlobalOldest();
			if (skippedKeys.size() >= MAX_TOTAL_SKIP) {
				return;
			}
		}
		Bytes chainIdBytes = new Bytes(chainId);
		SkippedKeyId keyId = new SkippedKeyId(chainId, messageNumber);
		int chainCount = keysPerChain.getOrDefault(chainIdBytes, 0);
		if (chainCount >= MAX_SKIP) {
			evictOldestKey(chainIdBytes);
			chainCount = keysPerChain.getOrDefault(chainIdBytes, 0);
		}
		SkippedKeyEntry entry = new SkippedKeyEntry(messageKey, timestamp);
		SkippedKeyEntry previous = skippedKeys.put(keyId, entry);
		if (previous == null) {
			keysPerChain.put(chainIdBytes, chainCount + 1);
		}
	}

	@GuardedBy("this")
	private void pruneExpiredLocked(long currentTime) {
		long expirationThreshold = currentTime - MAX_SKIP_AGE_MS;
		Iterator<Map.Entry<SkippedKeyId, SkippedKeyEntry>> it =
				skippedKeys.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<SkippedKeyId, SkippedKeyEntry> entry = it.next();
			if (entry.getValue().timestamp < expirationThreshold) {
				it.remove();
				decrementChainCount(entry.getKey().chainId);
			}
		}
	}

	@GuardedBy("this")
	private void evictGlobalOldest() {
		Iterator<Map.Entry<SkippedKeyId, SkippedKeyEntry>> it =
				skippedKeys.entrySet().iterator();
		if (it.hasNext()) {
			Map.Entry<SkippedKeyId, SkippedKeyEntry> entry = it.next();
			it.remove();
			decrementChainCount(entry.getKey().chainId);
		}
	}

	@GuardedBy("this")
	private void decrementChainCount(Bytes chainIdBytes) {
		int count = keysPerChain.getOrDefault(chainIdBytes, 1);
		if (count <= 1) {
			keysPerChain.remove(chainIdBytes);
		} else {
			keysPerChain.put(chainIdBytes, count - 1);
		}
	}

	@Override
	@Nullable
	public synchronized SecretKey retrieveAndDeleteSkippedKey(byte[] chainId,
			int messageNumber) {
		SkippedKeyId keyId = new SkippedKeyId(chainId, messageNumber);
		SkippedKeyEntry entry = skippedKeys.remove(keyId);

		if (entry != null) {
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
		skippedKeys.entrySet().removeIf(
				entry -> entry.getKey().chainId.equals(chainIdBytes));

		keysPerChain.remove(chainIdBytes);
	}

	@GuardedBy("this")
	private void evictOldestKey(Bytes chainIdBytes) {
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

	public synchronized int getTotalSkippedKeyCount() {
		return skippedKeys.size();
	}
}
