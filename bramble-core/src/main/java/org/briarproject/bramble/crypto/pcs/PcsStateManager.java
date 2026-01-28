package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.DhRatchetState;
import org.briarproject.bramble.api.crypto.pcs.MlKemKeyPair;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.PqEpochState;
import org.briarproject.bramble.api.crypto.pcs.PqRatchetState;
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
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_ENABLED;
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
				initializeState(txn, contactId, initial);
			});
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to initialize PCS state", e);
		}
	}

	/**
	 * Initializes PCS Mode 1 state within an existing transaction.
	 */
	public void initializeState(Transaction txn, ContactId contactId,
			SecretKey rootKey) throws DbException {
		PcsSessionState initial = PcsSessionState.createInitial(rootKey);
		initializeState(txn, contactId, initial);
	}

	private void initializeState(Transaction txn, ContactId contactId,
			PcsSessionState initial) throws DbException {
		db.setPcsSessionState(txn, contactId, PCS_DIRECTION_SEND,
				initial.getChainKey(), initial.getMessageNumber(),
				initial.getPreviousChainLength());
		db.setPcsSessionState(txn, contactId, PCS_DIRECTION_RECEIVE,
				initial.getChainKey(), initial.getMessageNumber(),
				initial.getPreviousChainLength());
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
				initializeMode2State(txn, contactId, sendState, receiveState);
			});
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to initialize Mode 2 PCS state", e);
		}
	}

	/**
	 * Initializes PCS Mode 2 state within an existing transaction.
	 */
	public void initializeMode2State(Transaction txn, ContactId contactId,
			PcsSessionState sendState, PcsSessionState receiveState)
			throws DbException {
		saveMode2State(txn, contactId, PCS_DIRECTION_SEND, sendState);
		saveMode2State(txn, contactId, PCS_DIRECTION_RECEIVE, receiveState);
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
	 * Checks if PCS state has been initialized for a contact within an
	 * existing transaction.
	 */
	public boolean hasState(Transaction txn, ContactId contactId)
			throws DbException {
		return db.containsPcsSessionState(txn, contactId);
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

	// ==================== Mode 3 (PQ Ratchet) Methods ====================

	@Nullable
	public PqRatchetState loadPqState(ContactId contactId) {
		if (!MODE3_ENABLED) return null;
		try {
			return db.transactionWithNullableResult(true, txn ->
					loadPqState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to load PQ state", e);
			return null;
		}
	}

	@Nullable
	public PqRatchetState loadPqState(Transaction txn, ContactId contactId)
			throws DbException {
		if (!MODE3_ENABLED) return null;
		Object[] result = db.getPqRatchetState(txn, contactId);
		if (result == null) return null;
		return parsePqState(result);
	}

	public void savePqState(ContactId contactId, PqRatchetState state) {
		if (!MODE3_ENABLED) return;
		try {
			db.transaction(false, txn -> savePqState(txn, contactId, state));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to save PQ state", e);
		}
	}

	public void savePqState(Transaction txn, ContactId contactId,
			PqRatchetState state) throws DbException {
		if (!MODE3_ENABLED) return;

		MlKemKeyPair ourKeyPair = state.getOurKeyPair();
		byte[] ourEkSeed = ourKeyPair != null ? ourKeyPair.getEkSeed() : null;
		byte[] ourEkVector = ourKeyPair != null ? ourKeyPair.getEkVector() : null;
		byte[] ourDecapsKey = ourKeyPair != null ?
				ourKeyPair.getDecapsulationKey() : null;

		db.setPqRatchetState(txn, contactId,
				state.getCurrentEpoch(),
				state.getEpochStartTime(),
				state.getMessagesSinceEpoch(),
				state.getState().getValue(),
				state.isInitiator(),
				state.getChunksSent(),
				state.getChunksReceived(),
				ourEkSeed, ourEkVector, ourDecapsKey,
				state.getTheirEkSeed(),
				state.getTheirEkHash(),
				state.getTheirEkVector(),
				state.getCiphertext(),
				state.getPendingChunks());
	}

	public boolean hasPqState(ContactId contactId) {
		if (!MODE3_ENABLED) return false;
		try {
			return db.transactionWithResult(true, txn ->
					db.containsPqRatchetState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to check PQ state", e);
			return false;
		}
	}

	public void removePqState(ContactId contactId) {
		if (!MODE3_ENABLED) return;
		try {
			db.transaction(false, txn ->
					db.removePqRatchetState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to remove PQ state", e);
		}
	}

	@Nullable
	private PqRatchetState parsePqState(Object[] result) {
		long currentEpoch = (Long) result[0];
		long epochStartTime = (Long) result[1];
		int messagesSinceEpoch = (Integer) result[2];
		int stateValue = (Integer) result[3];
		boolean isInitiator = (Boolean) result[4];
		int chunksSent = (Integer) result[5];
		int chunksReceived = (Integer) result[6];
		byte[] ourEkSeed = (byte[]) result[7];
		byte[] ourEkVector = (byte[]) result[8];
		byte[] ourDecapsKey = (byte[]) result[9];
		byte[] theirEkSeed = (byte[]) result[10];
		byte[] theirEkHash = (byte[]) result[11];
		byte[] theirEkVector = (byte[]) result[12];
		byte[] ciphertext = (byte[]) result[13];
		byte[] pendingChunks = (byte[]) result[14];

		PqEpochState state = PqEpochState.fromValue(stateValue);
		MlKemKeyPair ourKeyPair = null;
		if (ourEkSeed != null && ourEkVector != null && ourDecapsKey != null) {
			ourKeyPair = MlKemKeyPair.fromComponents(ourEkSeed, ourEkVector, ourDecapsKey);
		}

		return PqRatchetState.fromDatabase(
				currentEpoch, epochStartTime, messagesSinceEpoch, state,
				isInitiator, chunksSent, chunksReceived, ourKeyPair,
				theirEkSeed, theirEkHash, theirEkVector, ciphertext, pendingChunks);
	}
}
