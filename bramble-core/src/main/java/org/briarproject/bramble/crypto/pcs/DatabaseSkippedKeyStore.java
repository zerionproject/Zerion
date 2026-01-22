package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MAX_SKIP_AGE_MS;
import static org.briarproject.bramble.api.db.DatabaseComponent.PCS_DIRECTION_RECEIVE;
import static org.briarproject.bramble.api.db.DatabaseComponent.PCS_DIRECTION_SEND;

/**
 * Database-backed implementation of SkippedKeyStore.
 * <p>
 * This implementation persists skipped message keys to the database,
 * ensuring they survive app restarts and providing durable storage
 * for out-of-order message handling.
 * <p>
 * Thread-safe through database transaction isolation.
 */
@ThreadSafe
@NotNullByDefault
public class DatabaseSkippedKeyStore implements SkippedKeyStore {

	private static final Logger LOG =
			getLogger(DatabaseSkippedKeyStore.class.getName());

	/**
	 * Chain ID format: 4 bytes contactId + 1 byte direction
	 */
	private static final int CHAIN_ID_LENGTH = 5;

	private final DatabaseComponent db;

	@Inject
	public DatabaseSkippedKeyStore(DatabaseComponent db) {
		this.db = db;
	}

	@Override
	public void storeSkippedKey(byte[] chainId, int messageNumber,
			SecretKey messageKey, long timestamp) {
		ContactId contactId = extractContactId(chainId);
		int direction = extractDirection(chainId);

		try {
			db.transaction(false, txn -> {
				// Check count before inserting (enforce MAX_SKIP in caller)
				// The database will handle duplicate key constraint
				db.addPcsSkippedKey(txn, contactId, direction,
						messageNumber, messageKey, timestamp);
			});
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to store skipped key", e);
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
			LOG.log(WARNING, "Failed to retrieve skipped key", e);
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
			LOG.log(WARNING, "Failed to get skipped key count", e);
			return 0;
		}
	}

	@Override
	public int pruneExpiredKeys(long currentTime) {
		try {
			return db.transactionWithResult(false, txn ->
					db.prunePcsSkippedKeys(txn, MAX_SKIP_AGE_MS));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to prune expired keys", e);
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
			LOG.log(WARNING, "Failed to clear chain", e);
		}
	}

	// ==================== Chain ID Encoding/Decoding ====================

	/**
	 * Creates a chain ID from contact ID and direction.
	 * <p>
	 * Format: 4 bytes contactId (big-endian) + 1 byte direction
	 *
	 * @param contactId The contact ID
	 * @param send True for send direction, false for receive
	 * @return The chain ID bytes
	 */
	public static byte[] createChainId(ContactId contactId, boolean send) {
		byte[] chainId = new byte[CHAIN_ID_LENGTH];
		ByteBuffer.wrap(chainId).putInt(contactId.getInt());
		chainId[4] = (byte) (send ? PCS_DIRECTION_SEND : PCS_DIRECTION_RECEIVE);
		return chainId;
	}

	/**
	 * Extracts the contact ID from a chain ID.
	 */
	private ContactId extractContactId(byte[] chainId) {
		if (chainId.length < 4) {
			throw new IllegalArgumentException("Invalid chain ID length");
		}
		int id = ByteBuffer.wrap(chainId, 0, 4).getInt();
		return new ContactId(id);
	}

	/**
	 * Extracts the direction from a chain ID.
	 */
	private int extractDirection(byte[] chainId) {
		if (chainId.length < CHAIN_ID_LENGTH) {
			throw new IllegalArgumentException("Invalid chain ID length");
		}
		return chainId[4] & 0xFF;
	}
}
