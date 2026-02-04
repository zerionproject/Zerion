package org.briarproject.briar.api.privategroup.senderkeys;

import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

/**
 * Handles encryption and decryption of group messages using Sender Keys.
 */
@NotNullByDefault
public interface GroupMessageCrypto {

	/**
	 * Label for per-message key derivation.
	 */
	String MESSAGE_KEY_LABEL = "org.zerion/MESSAGE_KEY";

	/**
	 * Label for chain key derivation.
	 */
	String CHAIN_KEY_LABEL = "org.zerion/CHAIN_KEY";

	/**
	 * Label for message signature.
	 */
	String MESSAGE_SIGNATURE_LABEL = "org.zerion/GROUP_MSG_SIG";

	/**
	 * Encrypts a group message using the local user's SenderKey.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param plaintext The message plaintext
	 * @param timestamp The message timestamp
	 * @param privateKey The local user's signing key
	 * @return The encrypted message with metadata
	 */
	EncryptedGroupMessage encryptGroupMessage(
			Transaction txn,
			GroupId groupId,
			byte[] plaintext,
			long timestamp,
			PrivateKey privateKey
	) throws DbException, GeneralSecurityException;

	/**
	 * Decrypts a received group message.
	 *
	 * @param txn The database transaction
	 * @param groupId The private group ID
	 * @param senderId The sender's author ID
	 * @param senderPublicKey The sender's public key for signature verification
	 * @param encrypted The encrypted message data
	 * @return The decrypted plaintext
	 */
	byte[] decryptGroupMessage(
			Transaction txn,
			GroupId groupId,
			AuthorId senderId,
			PublicKey senderPublicKey,
			EncryptedGroupMessage encrypted
	) throws DbException, GeneralSecurityException;

	/**
	 * Derives a message key from a SenderKey at a specific index.
	 * Used for out-of-order decryption.
	 *
	 * @param senderKey The sender's key
	 * @param targetIndex The message index to derive key for
	 * @return The derived message key bytes
	 */
	byte[] deriveMessageKeyAt(SenderKey senderKey, int targetIndex)
			throws GeneralSecurityException;

	/**
	 * Encrypted group message data structure.
	 */
	interface EncryptedGroupMessage {
		byte[] getCiphertext();
		byte[] getNonce();
		int getEpoch();
		int getMessageIndex();
		byte[] getSignature();
		long getTimestamp();
	}
}
