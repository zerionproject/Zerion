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
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_ENABLED;
import static org.briarproject.bramble.api.db.DatabaseComponent.PCS_DIRECTION_RECEIVE;
import static org.briarproject.bramble.api.db.DatabaseComponent.PCS_DIRECTION_SEND;


@ThreadSafe
@NotNullByDefault
public class PcsStateManager {

	private static final Logger LOG =
			Logger.getLogger(PcsStateManager.class.getName());

	private final DatabaseComponent db;
	private final CryptoComponent crypto;

	@Inject
	public PcsStateManager(DatabaseComponent db, CryptoComponent crypto) {
		this.db = db;
		this.crypto = crypto;
	}

	
	@Nullable
	public PcsSessionState loadSendState(ContactId contactId) {
		return loadState(contactId, PCS_DIRECTION_SEND);
	}

	
	@Nullable
	public PcsSessionState loadReceiveState(ContactId contactId) {
		return loadState(contactId, PCS_DIRECTION_RECEIVE);
	}

	
	public void saveSendState(ContactId contactId, PcsSessionState state) {
		saveState(contactId, PCS_DIRECTION_SEND, state);
	}

	
	public void saveReceiveState(ContactId contactId, PcsSessionState state) {
		saveState(contactId, PCS_DIRECTION_RECEIVE, state);
	}

	
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

	
	public void initializeMode2State(ContactId contactId,
			PcsSessionState sendState, PcsSessionState receiveState) {
		try {
			db.transaction(false, txn -> {
				initializeMode2State(txn, contactId, sendState, receiveState);
			});
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to initialize Mode 2 state", e);
		}
	}

	
	public void initializeMode2State(Transaction txn, ContactId contactId,
			PcsSessionState sendState, PcsSessionState receiveState)
			throws DbException {
		saveMode2State(txn, contactId, PCS_DIRECTION_SEND, sendState);
		saveMode2State(txn, contactId, PCS_DIRECTION_RECEIVE, receiveState);
	}

	
	public boolean hasState(ContactId contactId) {
		try {
			return db.transactionWithResult(true, txn ->
					db.containsPcsSessionState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to check PCS state existence", e);
			return false;
		}
	}

	
	public boolean hasState(Transaction txn, ContactId contactId)
			throws DbException {
		return db.containsPcsSessionState(txn, contactId);
	}

	
	public void removeState(ContactId contactId) {
		try {
			db.transaction(false, txn ->
					db.removePcsState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to remove PCS state", e);
		}
	}


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
		Object[] result = db.getPcsMode2SessionState(txn, contactId, direction);
		if (result != null) {
			return parseMode2State(result);
		}
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
			return new PcsSessionState(chainKey, messageNumber, previousChainLength);
		}
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
				LOG.log(WARNING,
						"Failed to parse DH keys, falling back to Mode 1", e);
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

	@Nullable
	public PqRatchetState loadPqState(ContactId contactId) {
		if (!MODE3_ENABLED) return null;
		try {
			return db.transactionWithNullableResult(true, txn ->
					loadPqState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to load PQ ratchet state", e);
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
			LOG.log(WARNING, "Failed to save PQ ratchet state", e);
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
			LOG.log(WARNING, "Failed to check PQ state existence", e);
			return false;
		}
	}

	public void removePqState(ContactId contactId) {
		if (!MODE3_ENABLED) return;
		try {
			db.transaction(false, txn ->
					db.removePqRatchetState(txn, contactId));
		} catch (DbException e) {
			LOG.log(WARNING, "Failed to remove PQ ratchet state", e);
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
