package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.DhRatchetState;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.db.DatabaseComponent.PCS_DIRECTION_RECEIVE;
import static org.briarproject.bramble.api.db.DatabaseComponent.PCS_DIRECTION_SEND;

/**
 * Manages persistence of PCS session state in the database.
 * <p>
 * This class provides methods to load, save, and initialize PCS session
 * state for contacts. It handles both send and receive chains independently,
 * and supports both Mode 1 (symmetric ratchet) and Mode 2 (DH ratchet).
 * <p>
 * Thread-safe through database transaction isolation.
 */
@ThreadSafe
@NotNullByDefault
public class PcsStateManager {

	private static final Logger LOG =
			getLogger(PcsStateManager.class.getName());

	private final DatabaseComponent db;
	private final CryptoComponent crypto;

	@Inject
	public PcsStateManager(DatabaseComponent db, CryptoComponent crypto) {
		this.db = db;
		this.crypto = crypto;
	}

	/**
	 * Loads the send chain state for a contact.
	 *
	 * @param contactId The contact ID
	 * @return The session state, or null if not initialized
	 */
	@Nullable
	public PcsSessionState loadSendState(ContactId contactId) {
		return loadState(contactId, PCS_DIRECTION_SEND);
	}

	/**
	 * Loads the receive chain state for a contact.
	 *
	 * @param contactId The contact ID
	 * @return The session state, or null if not initialized
	 */
	@Nullable
	public PcsSessionState loadReceiveState(ContactId contactId) {
		return loadState(contactId, PCS_DIRECTION_RECEIVE);
	}

	/**
	 * Saves the send chain state for a contact.
	 *
	 * @param contactId The contact ID
	 * @param state The session state to save
	 */
	public void saveSendState(ContactId contactId, PcsSessionState state) {
		saveState(contactId, PCS_DIRECTION_SEND, state);
	}

	/**
	 * Saves the receive chain state for a contact.
	 *
	 * @param contactId The contact ID
	 * @param state The session state to save
	 */
	public void saveReceiveState(ContactId contactId, PcsSessionState state) {
		saveState(contactId, PCS_DIRECTION_RECEIVE, state);
	}

	/**
	 * Initializes PCS Mode 1 state for a contact with the given root key.
	 * <p>
	 * This should be called when PCS is first enabled for a contact,
	 * typically during handshake when both peers agree to use PCS.
	 *
	 * @param contactId The contact ID
	 * @param rootKey The shared root key derived from the handshake
	 */
	public void initializeState(ContactId contactId, SecretKey rootKey) {
		PcsSessionState initial = PcsSessionState.createInitial(rootKey);
		try {
			db.transaction(false, txn -> {
				db.setPcsSessionState(txn, contactId, PCS_DIRECTION_SEND,
						initial.getChainKey(), initial.getMessageNumber(),
						initial.getPreviousChainLength());
				db.setPcsSessionState(txn, contactId, PCS_DIRECTION_RECEIVE,
						initial.getChainKey(), initial.getMessageNumber(),
						initial.getPreviousChainLength());
			});
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to initialize PCS state", e);
		}
	}

	/**
	 * Initializes PCS Mode 2 state for a contact with DH ratchet.
	 * <p>
	 * This should be called when both peers have negotiated Mode 2 support.
	 *
	 * @param contactId The contact ID
	 * @param sendState The send chain state (with our DH key pair)
	 * @param receiveState The receive chain state (with their DH public key)
	 */
	public void initializeMode2State(ContactId contactId,
			PcsSessionState sendState, PcsSessionState receiveState) {
		try {
			db.transaction(false, txn -> {
				saveMode2State(txn, contactId, PCS_DIRECTION_SEND, sendState);
				saveMode2State(txn, contactId, PCS_DIRECTION_RECEIVE, receiveState);
			});
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to initialize Mode 2 PCS state", e);
		}
	}

	/**
	 * Checks if PCS state has been initialized for a contact.
	 *
	 * @param contactId The contact ID
	 * @return True if PCS state exists for this contact
	 */
	public boolean hasState(ContactId contactId) {
		try {
			return db.transactionWithResult(true, txn ->
					db.containsPcsSessionState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to check PCS state", e);
			return false;
		}
	}

	/**
	 * Removes all PCS state for a contact.
	 * <p>
	 * This should be called when PCS is disabled or the contact is removed.
	 *
	 * @param contactId The contact ID
	 */
	public void removeState(ContactId contactId) {
		try {
			db.transaction(false, txn ->
					db.removePcsState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to remove PCS state", e);
		}
	}

	/**
	 * Loads and saves state within an existing transaction.
	 * <p>
	 * Use these methods when you need to update state as part of a
	 * larger transaction (e.g., when processing a message).
	 */
	@Nullable
	public PcsSessionState loadSendState(Transaction txn, ContactId contactId)
			throws DbException {
		return loadState(txn, contactId, PCS_DIRECTION_SEND);
	}

	@Nullable
	public PcsSessionState loadReceiveState(Transaction txn, ContactId contactId)
			throws DbException {
		return loadState(txn, contactId, PCS_DIRECTION_RECEIVE);
	}

	public void saveSendState(Transaction txn, ContactId contactId,
			PcsSessionState state) throws DbException {
		saveState(txn, contactId, PCS_DIRECTION_SEND, state);
	}

	public void saveReceiveState(Transaction txn, ContactId contactId,
			PcsSessionState state) throws DbException {
		saveState(txn, contactId, PCS_DIRECTION_RECEIVE, state);
	}

	// ==================== Private Methods ====================

	@Nullable
	private PcsSessionState loadState(ContactId contactId, int direction) {
		try {
			return db.transactionWithNullableResult(true, txn ->
					loadState(txn, contactId, direction));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to load PCS state", e);
			return null;
		}
	}

	@Nullable
	private PcsSessionState loadState(Transaction txn, ContactId contactId,
			int direction) throws DbException {
		// Try Mode 2 first
		Object[] result = db.getPcsMode2SessionState(txn, contactId, direction);
		if (result != null) {
			return parseMode2State(result);
		}
		// Fallback to Mode 1
		result = db.getPcsSessionState(txn, contactId, direction);
		if (result == null) return null;

		byte[] chainKeyBytes = (byte[]) result[0];
		int messageNumber = (Integer) result[1];
		int previousChainLength = (Integer) result[2];

		SecretKey chainKey = new SecretKey(chainKeyBytes);
		return new PcsSessionState(chainKey, messageNumber, previousChainLength);
	}

	@Nullable
	private PcsSessionState parseMode2State(Object[] result) {
		byte[] chainKeyBytes = (byte[]) result[0];
		int messageNumber = (Integer) result[1];
		int previousChainLength = (Integer) result[2];
		byte[] rootKeyBytes = (byte[]) result[3];
		byte[] dhPrivateKeyBytes = (byte[]) result[4];
		byte[] dhPublicKeyBytes = (byte[]) result[5];
		byte[] dhRemotePublicKeyBytes = (byte[]) result[6];
		boolean mode2Enabled = (Boolean) result[7];

		SecretKey chainKey = new SecretKey(chainKeyBytes);

		if (!mode2Enabled || rootKeyBytes == null) {
			// Mode 1 state stored in Mode 2 table
			return new PcsSessionState(chainKey, messageNumber, previousChainLength);
		}

		// Mode 2 state
		SecretKey rootKey = new SecretKey(rootKeyBytes);
		DhRatchetState dhState = null;

		if (dhPrivateKeyBytes != null && dhPublicKeyBytes != null) {
			try {
				KeyParser keyParser = crypto.getAgreementKeyParser();
				PrivateKey dhPrivateKey = keyParser.parsePrivateKey(dhPrivateKeyBytes);
				PublicKey dhPublicKey = keyParser.parsePublicKey(dhPublicKeyBytes);
				KeyPair dhKeyPair = new KeyPair(dhPublicKey, dhPrivateKey);

				PublicKey dhRemotePublicKey = null;
				if (dhRemotePublicKeyBytes != null) {
					dhRemotePublicKey = keyParser.parsePublicKey(dhRemotePublicKeyBytes);
				}

				dhState = new DhRatchetState(dhKeyPair, dhRemotePublicKey);
			} catch (GeneralSecurityException e) {
				LOG.log(WARNING, "Failed to parse DH keys from database", e);
				// Fall back to Mode 1
				return new PcsSessionState(chainKey, messageNumber, previousChainLength);
			}
		}

		return new PcsSessionState(chainKey, messageNumber, previousChainLength,
				rootKey, dhState);
	}

	private void saveState(ContactId contactId, int direction,
			PcsSessionState state) {
		try {
			db.transaction(false, txn ->
					saveState(txn, contactId, direction, state));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to save PCS state", e);
		}
	}

	private void saveState(Transaction txn, ContactId contactId, int direction,
			PcsSessionState state) throws DbException {
		if (state.isMode2()) {
			saveMode2State(txn, contactId, direction, state);
		} else {
			db.setPcsSessionState(txn, contactId, direction,
					state.getChainKey(), state.getMessageNumber(),
					state.getPreviousChainLength());
		}
	}

	private void saveMode2State(Transaction txn, ContactId contactId,
			int direction, PcsSessionState state) throws DbException {
		DhRatchetState dhState = state.getDhState();
		PrivateKey dhPrivateKey = null;
		PublicKey dhPublicKey = null;
		PublicKey dhRemotePublicKey = null;

		if (dhState != null) {
			dhPrivateKey = dhState.getDhKeyPair().getPrivate();
			dhPublicKey = dhState.getDhPublicKey();
			dhRemotePublicKey = dhState.getDhRemotePublicKey();
		}

		db.setPcsMode2SessionState(txn, contactId, direction,
				state.getChainKey(), state.getMessageNumber(),
				state.getPreviousChainLength(), state.getRootKey(),
				dhPrivateKey, dhPublicKey, dhRemotePublicKey, state.isMode2());
	}
}
