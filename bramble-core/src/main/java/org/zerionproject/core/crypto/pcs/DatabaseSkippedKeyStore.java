package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MAX_SKIP;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MAX_SKIP_AGE_MS;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.SKIP_CLOCK_REWIND_THRESHOLD_MS;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.SKIP_PRUNE_INTERVAL_MS;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_RECEIVE;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_SEND;

@ThreadSafe
@NotNullByDefault
public class DatabaseSkippedKeyStore implements SkippedKeyStore {

	private static final int CHAIN_ID_LENGTH = 5;

	private final DatabaseComponent db;
	private volatile long lastPruneMs = 0L;
	private volatile long highestSeenTimestamp = 0L;

	@Inject
	public DatabaseSkippedKeyStore(DatabaseComponent db) {
		this.db = db;
	}

	@Override
	public void storeSkippedKey(byte[] chainId, int messageNumber,
			SecretKey messageKey, long timestamp) {
		ContactId contactId = extractContactId(chainId);
		int direction = extractDirection(chainId);

		if (highestSeenTimestamp > 0
				&& highestSeenTimestamp - timestamp
						> SKIP_CLOCK_REWIND_THRESHOLD_MS) {
			wipeAllSkippedKeys();
			highestSeenTimestamp = timestamp;
			lastPruneMs = timestamp;
			return;
		}
		if (timestamp > highestSeenTimestamp) {
			highestSeenTimestamp = timestamp;
		}

		maybeOpportunisticPrune(timestamp);

		try {
			db.transaction(false, txn -> {
				int count = db.getPcsSkippedKeyCount(txn, contactId, direction);
				if (count >= MAX_SKIP) {
					db.prunePcsSkippedKeys(txn, MAX_SKIP_AGE_MS);
					count = db.getPcsSkippedKeyCount(txn, contactId, direction);
					if (count >= MAX_SKIP) {
						return;
					}
				}
				db.addPcsSkippedKey(txn, contactId, direction,
						messageNumber, messageKey, timestamp);
			});
		} catch (DbException e) {
		}
	}

	private void wipeAllSkippedKeys() {
		try {
			db.transaction(false, txn -> db.prunePcsSkippedKeys(txn, 0));
		} catch (DbException e) {
		}
	}

	private void maybeOpportunisticPrune(long now) {
		if (now - lastPruneMs <= SKIP_PRUNE_INTERVAL_MS) return;
		lastPruneMs = now;
		try {
			db.transaction(false, txn ->
					db.prunePcsSkippedKeys(txn, MAX_SKIP_AGE_MS));
		} catch (DbException e) {
		}
	}

	@Override
	@Nullable
	public SecretKey retrieveAndDeleteSkippedKey(byte[] chainId,
			int messageNumber) {
		ContactId contactId = extractContactId(chainId);
		int direction = extractDirection(chainId);

		try {
			return db.transactionWithNullableResult(false, txn ->
					db.getPcsSkippedKey(txn, contactId, direction, messageNumber));
		} catch (DbException e) {
			return null;
		}
	}

	@Override
	public int getSkippedKeyCount(byte[] chainId) {
		ContactId contactId = extractContactId(chainId);
		int direction = extractDirection(chainId);

		try {
			return db.transactionWithResult(true, txn ->
					db.getPcsSkippedKeyCount(txn, contactId, direction));
		} catch (DbException e) {
			return 0;
		}
	}

	@Override
	public int pruneExpiredKeys(long currentTime) {
		try {
			return db.transactionWithResult(false, txn ->
					db.prunePcsSkippedKeys(txn, MAX_SKIP_AGE_MS));
		} catch (DbException e) {
			return 0;
		}
	}

	@Override
	public void clearChain(byte[] chainId) {
		ContactId contactId = extractContactId(chainId);

		try {
			db.transaction(false, txn ->
					db.removePcsState(txn, contactId));
		} catch (DbException e) {
		}
	}

	public static byte[] createChainId(ContactId contactId, boolean send) {
		byte[] chainId = new byte[CHAIN_ID_LENGTH];
		ByteBuffer.wrap(chainId).putInt(contactId.getInt());
		chainId[4] = (byte) (send ? PCS_DIRECTION_SEND : PCS_DIRECTION_RECEIVE);
		return chainId;
	}

	private ContactId extractContactId(byte[] chainId) {
		if (chainId.length < 4) {
			throw new IllegalArgumentException("Invalid chain ID length");
		}
		int id = ByteBuffer.wrap(chainId, 0, 4).getInt();
		return new ContactId(id);
	}

	private int extractDirection(byte[] chainId) {
		if (chainId.length < CHAIN_ID_LENGTH) {
			throw new IllegalArgumentException("Invalid chain ID length");
		}
		return chainId[4] & 0xFF;
	}
}
