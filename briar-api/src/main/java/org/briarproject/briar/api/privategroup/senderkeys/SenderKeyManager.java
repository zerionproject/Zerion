package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Manages Sender Keys for group encryption.
 * <p>
 * Each member maintains their own outbound SenderKey for encryption,
 * and caches all other members' SenderKeys for decryption.
 */
@NotNullByDefault
public interface SenderKeyManager {

	/**
	 * Label for initial SenderKey generation via HKDF.
	 */
	String SENDER_KEY_INIT_LABEL = "org.zerion/SENDER_KEY_INIT";

	/**
	 * Label for per-message key derivation.
	 */
	String MESSAGE_KEY_LABEL = "org.zerion/MESSAGE_KEY";

	/**
	 * Label for chain key derivation (symmetric ratchet).
	 */
	String CHAIN_KEY_LABEL = "org.zerion/CHAIN_KEY";

	/**
	 * Label for epoch rotation (PQ refresh).
	 */
	String SENDER_KEY_EPOCH_LABEL = "org.zerion/SENDER_KEY_EPOCH";

	/**
	 * Maximum number of skipped message keys to cache for out-of-order decryption.
	 */
	int MAX_SKIP = 1000;

	/**
	 * Default epoch rotation threshold (messages).
	 */
	int EPOCH_MESSAGE_THRESHOLD = 100;

	/**
	 * Default epoch rotation threshold (milliseconds).
	 */
	long EPOCH_TIME_THRESHOLD_MS = 24L * 60 * 60 * 1000;

	/**
	 * Grace period for retaining revoked keys (milliseconds).
	 */
	long REVOKED_KEY_GRACE_PERIOD_MS = 7L * 24 * 60 * 60 * 1000;

	// --- SenderKey Operations ---

	/**
	 * Generates a new SenderKey for the local user in the specified group.
	 *
	 * @param txn The database transaction
	 * @param groupId The group to generate a key for
	 * @return The generated SenderKey
	 */
	SenderKey generateSenderKey(Transaction txn, GroupId groupId)
			throws DbException;

	/**
	 * Stores a received SenderKey from another group member.
	 *
	 * @param txn The database transaction
	 * @param senderKey The SenderKey to store
	 */
	void storeSenderKey(Transaction txn, SenderKey senderKey)
			throws DbException;

	/**
	 * Retrieves the local user's SenderKey for the specified group.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @return The local SenderKey, or null if not initialized
	 */
	@Nullable
	SenderKey getLocalSenderKey(Transaction txn, GroupId groupId)
			throws DbException;

	/**
	 * Retrieves a specific member's SenderKey for decryption.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @param authorId The sender's author ID
	 * @return The SenderKey, or null if not found
	 */
	@Nullable
	SenderKey getSenderKey(Transaction txn, GroupId groupId, AuthorId authorId)
			throws DbException;

	/**
	 * Retrieves all SenderKeys for a group.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @return Map of AuthorId to SenderKey
	 */
	Map<AuthorId, SenderKey> getAllSenderKeys(Transaction txn, GroupId groupId)
			throws DbException;

	/**
	 * Updates a SenderKey after encryption/decryption (advances chain).
	 *
	 * @param txn The database transaction
	 * @param senderKey The updated SenderKey
	 */
	void updateSenderKey(Transaction txn, SenderKey senderKey)
			throws DbException;

	/**
	 * Revokes a SenderKey (marks as REVOKED, will be deleted after grace period).
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @param authorId The author whose key to revoke
	 */
	void revokeSenderKey(Transaction txn, GroupId groupId, AuthorId authorId)
			throws DbException;

	// --- Key History (for out-of-order decryption) ---

	/**
	 * Caches a derived message key for out-of-order decryption.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @param authorId The sender
	 * @param epoch The key epoch
	 * @param messageIndex The message index
	 * @param messageKey The derived message key
	 */
	void cacheMessageKey(
			Transaction txn,
			GroupId groupId,
			AuthorId authorId,
			int epoch,
			int messageIndex,
			byte[] messageKey
	) throws DbException;

	/**
	 * Retrieves a cached message key for out-of-order decryption.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @param authorId The sender
	 * @param epoch The key epoch
	 * @param messageIndex The message index
	 * @return The cached message key, or null if not found
	 */
	@Nullable
	byte[] getCachedMessageKey(
			Transaction txn,
			GroupId groupId,
			AuthorId authorId,
			int epoch,
			int messageIndex
	) throws DbException;

	/**
	 * Removes a cached message key after successful decryption.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @param authorId The sender
	 * @param epoch The key epoch
	 * @param messageIndex The message index
	 */
	void removeCachedMessageKey(
			Transaction txn,
			GroupId groupId,
			AuthorId authorId,
			int epoch,
			int messageIndex
	) throws DbException;

	/**
	 * Cleans up expired cached message keys.
	 *
	 * @param txn The database transaction
	 * @param now Current timestamp
	 */
	void cleanupExpiredMessageKeys(Transaction txn, long now)
			throws DbException;

	// --- Group Crypto State ---

	/**
	 * Gets the cryptographic state for a group.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @return The crypto state, or null if not initialized
	 */
	@Nullable
	GroupCryptoState getGroupCryptoState(Transaction txn, GroupId groupId)
			throws DbException;

	/**
	 * Initializes the cryptographic state for a new group.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @param mode The initial crypto mode
	 * @param capability The local user's capability
	 */
	void initializeGroupCryptoState(
			Transaction txn,
			GroupId groupId,
			GroupCryptoMode mode,
			int capability
	) throws DbException;

	/**
	 * Updates the cryptographic state for a group.
	 *
	 * @param txn The database transaction
	 * @param state The new state
	 */
	void updateGroupCryptoState(Transaction txn, GroupCryptoState state)
			throws DbException;

	// --- Rekey Operations ---

	/**
	 * Triggers a rekey operation for the group.
	 * Generates new SenderKey and marks old key as ROTATING.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @param reason The reason for rekeying
	 * @return The new SenderKey to distribute
	 */
	SenderKey rekeyGroup(
			Transaction txn,
			GroupId groupId,
			GroupCryptoState.RekeyReason reason
	) throws DbException;

	/**
	 * Checks if epoch rotation is needed based on message count or time.
	 *
	 * @param senderKey The current SenderKey
	 * @param now Current timestamp
	 * @return true if rotation is needed
	 */
	boolean shouldRotateEpoch(SenderKey senderKey, long now);

	/**
	 * Gets all members who need to receive a SenderKey distribution.
	 *
	 * @param txn The database transaction
	 * @param groupId The group
	 * @return Collection of member AuthorIds (excluding local user)
	 */
	Collection<AuthorId> getMembersForDistribution(Transaction txn, GroupId groupId)
			throws DbException;
}
