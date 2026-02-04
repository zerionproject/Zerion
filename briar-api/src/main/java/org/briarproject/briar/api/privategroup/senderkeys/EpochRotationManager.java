package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Manages epoch rotation for Sender Keys with post-quantum integration.
 */
@NotNullByDefault
public interface EpochRotationManager {

	/**
	 * Label for epoch rotation key derivation.
	 */
	String SENDER_KEY_EPOCH_LABEL = "org.zerion/SENDER_KEY_EPOCH";

	/**
	 * Checks if epoch rotation is needed for the local user's SenderKey.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param now Current timestamp
	 * @return true if rotation is needed
	 */
	boolean checkRotationNeeded(Transaction txn, GroupId groupId, long now)
			throws DbException;

	/**
	 * Rotates the local user's SenderKey to a new epoch.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param pqSharedSecret Post-quantum shared secret from pairwise channel, or null
	 * @return The new SenderKey to distribute
	 */
	SenderKey rotateEpoch(
			Transaction txn,
			GroupId groupId,
			@Nullable byte[] pqSharedSecret
	) throws DbException;

	/**
	 * Handles an incoming epoch rotation from another member.
	 * Caches skipped keys if needed.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param newSenderKey The rotated SenderKey from the member
	 */
	void handleIncomingEpochRotation(
			Transaction txn,
			GroupId groupId,
			SenderKey newSenderKey
	) throws DbException;

	/**
	 * Derives a new chain key with PQ material for epoch rotation.
	 *
	 * @param currentChainKey Current chain key bytes
	 * @param pqSharedSecret PQ shared secret, or null for classical-only
	 * @param groupId The group ID for salt
	 * @param newEpoch The new epoch number
	 * @return The derived chain key bytes
	 */
	byte[] deriveEpochChainKey(
			byte[] currentChainKey,
			@Nullable byte[] pqSharedSecret,
			GroupId groupId,
			int newEpoch
	);
}
