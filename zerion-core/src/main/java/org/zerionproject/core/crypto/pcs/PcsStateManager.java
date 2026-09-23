package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.DhRatchetState;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.PqEpochState;
import org.zerionproject.core.api.crypto.pcs.PqRatchet;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.lifecycle.ServiceException;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_RECEIVE;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_SEND;

@ThreadSafe
@Singleton
@NotNullByDefault
public class PcsStateManager implements Service {
	private final DatabaseComponent db;
	private final CryptoComponent crypto;
	private final ConcurrentMap<Integer, ReentrantLock> contactLocks =
			new ConcurrentHashMap<>();

	@Inject
	public PcsStateManager(DatabaseComponent db, CryptoComponent crypto,
			LifecycleManager lifecycleManager) {
		this.db = db;
		this.crypto = crypto;
		lifecycleManager.registerService(this);
	}

	@Override
	public void startService() throws ServiceException {
	}

	@Override
	public void stopService() throws ServiceException {
		contactLocks.clear();
	}

	public Lock getContactLock(ContactId contactId) {
		return contactLocks.computeIfAbsent(contactId.getInt(),
				k -> new ReentrantLock());
	}

	public Lock getDirectionLock(ContactId contactId, int direction) {
		return getContactLock(contactId);
	}

	@Nullable
	public PcsSessionState loadSendState(ContactId contactId) {
		return loadState(contactId, PCS_DIRECTION_SEND);
	}

	/**
	 * Removes any Mode 3-Full ratchet blob still stored for
	 * {@code contactId}. Earlier releases persisted the in-memory ratchet
	 * state, including ML-KEM decapsulation keys, when a connection ended,
	 * although no connection ever resumed from it. Nothing writes the blob
	 * any more; this clears what an upgraded database still holds, zeroizing
	 * the loaded copy before the row is rewritten without it.
	 */
	public void stripPersistedMode3FullState(ContactId contactId)
			throws DbException {
		db.transaction(false, txn -> {
			stripPersistedMode3FullState(txn, contactId, PCS_DIRECTION_SEND);
			stripPersistedMode3FullState(txn, contactId,
					PCS_DIRECTION_RECEIVE);
		});
	}

	private void stripPersistedMode3FullState(Transaction txn,
			ContactId contactId, int direction) throws DbException {
		Object[] row = db.getPcsMode2SessionState(txn, contactId, direction);
		if (row == null) return;
		byte[] blob = (byte[]) row[8];
		if (blob == null) return;
		Arrays.fill(blob, (byte) 0);
		rewriteWithoutMode3FullState(txn, contactId, direction, row);
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

	public void mixPqSecretIntoReceiveRoot(ContactId contactId,
			SecretKey pqSecret, PqRatchet pqRatchet) {
		try {
			db.transaction(false, txn -> {
				PcsSessionState recv = loadState(txn, contactId,
						PCS_DIRECTION_RECEIVE);
				if (recv == null || recv.getRootKey() == null) return;
				SecretKey newRoot = pqRatchet.mixPqSecretIntoRootKey(
						recv.getRootKey(), pqSecret);
				saveState(txn, contactId, PCS_DIRECTION_RECEIVE,
						recv.afterPqRatchet(newRoot,
								recv.getPqEpoch() + 1));
			});
		} catch (DbException e) {
			throw new PcsPersistenceException(e);
		}
	}

	public void mixPqSecretIntoSendRoot(ContactId contactId,
			SecretKey pqSecret, PqRatchet pqRatchet) {
		try {
			db.transaction(false, txn -> {
				PcsSessionState send = loadState(txn, contactId,
						PCS_DIRECTION_SEND);
				if (send == null || send.getRootKey() == null) return;
				SecretKey newRoot = pqRatchet.mixPqSecretIntoRootKey(
						send.getRootKey(), pqSecret);
				saveState(txn, contactId, PCS_DIRECTION_SEND,
						send.afterPqRatchet(newRoot,
								send.getPqEpoch() + 1));
			});
		} catch (DbException e) {
			throw new PcsPersistenceException(e);
		}
	}

	public void initializeMode2State(ContactId contactId,
			PcsSessionState sendState, PcsSessionState receiveState) {
		try {
			db.transaction(false, txn -> {
				initializeMode2State(txn, contactId, sendState, receiveState);
			});
		} catch (DbException e) {
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
		}
		contactLocks.remove(contactId.getInt());
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
			return null;
		}
	}

	@Nullable
	private PcsSessionState loadState(Transaction txn, ContactId contactId,
			int direction) throws DbException {
		Object[] result = db.getPcsMode2SessionState(txn, contactId, direction);
		if (result == null) return null;
		return parseMode2State(result);
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

		if (!mode2Enabled || rootKeyBytes == null) return null;
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
				return null;
			}
		}

		return new PcsSessionState(chainKey, messageNumber, previousChainLength,
				rootKey, dhState, false, 0, null);
	}

	private void saveState(ContactId contactId, int direction,
			PcsSessionState state) {
		try {
			db.transaction(false, txn ->
					saveState(txn, contactId, direction, state));
		} catch (DbException e) {
			throw new PcsPersistenceException(e);
		}
	}

	private void saveState(Transaction txn, ContactId contactId, int direction,
			PcsSessionState state) throws DbException {
		saveMode2State(txn, contactId, direction, state);
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
				dhPrivateKey, dhPublicKey, dhRemotePublicKey, state.isMode2(),
				null);
	}

	private void rewriteWithoutMode3FullState(Transaction txn,
			ContactId contactId, int direction, Object[] row)
			throws DbException {
		SecretKey chainKey = new SecretKey((byte[]) row[0]);
		int messageNumber = (Integer) row[1];
		int previousChainLength = (Integer) row[2];
		byte[] rootKeyBytes = (byte[]) row[3];
		byte[] dhPrivateKeyBytes = (byte[]) row[4];
		byte[] dhPublicKeyBytes = (byte[]) row[5];
		byte[] dhRemotePublicKeyBytes = (byte[]) row[6];
		boolean mode2Enabled = (Boolean) row[7];
		SecretKey rootKey = rootKeyBytes == null ? null
				: new SecretKey(rootKeyBytes);
		PrivateKey dhPrivateKey = null;
		PublicKey dhPublicKey = null;
		PublicKey dhRemotePublicKey = null;
		if (dhPrivateKeyBytes != null && dhPublicKeyBytes != null) {
			try {
				KeyParser keyParser = crypto.getAgreementKeyParser();
				dhPrivateKey = keyParser.parsePrivateKey(dhPrivateKeyBytes);
				dhPublicKey = keyParser.parsePublicKey(dhPublicKeyBytes);
				if (dhRemotePublicKeyBytes != null) {
					dhRemotePublicKey =
							keyParser.parsePublicKey(dhRemotePublicKeyBytes);
				}
			} catch (GeneralSecurityException e) {
				throw new DbException(e);
			}
		}
		db.setPcsMode2SessionState(txn, contactId, direction, chainKey,
				messageNumber, previousChainLength, rootKey, dhPrivateKey,
				dhPublicKey, dhRemotePublicKey, mode2Enabled, null);
	}

	@Nullable
	public PqRatchetState loadPqState(ContactId contactId) {
		try {
			return db.transactionWithNullableResult(true, txn ->
					loadPqState(txn, contactId));
		} catch (DbException e) {
			return null;
		}
	}

	@Nullable
	public PqRatchetState loadPqState(Transaction txn, ContactId contactId)
			throws DbException {
		Object[] result = db.getPqRatchetState(txn, contactId);
		if (result == null) return null;
		return parsePqState(result);
	}

	public void savePqState(ContactId contactId, PqRatchetState state) {
		try {
			db.transaction(false, txn -> savePqState(txn, contactId, state));
		} catch (DbException e) {
			throw new PcsPersistenceException(e);
		}
	}

	public void savePqState(Transaction txn, ContactId contactId,
			PqRatchetState state) throws DbException {
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
		try {
			return db.transactionWithResult(true, txn ->
					db.containsPqRatchetState(txn, contactId));
		} catch (DbException e) {
			return false;
		}
	}

	public void removePqState(ContactId contactId) {
		try {
			db.transaction(false, txn ->
					db.removePqRatchetState(txn, contactId));
		} catch (DbException e) {
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
		if (state != PqEpochState.PQ_INACTIVE
				&& state != PqEpochState.PQ_READY
				&& state != PqEpochState.PQ_COMPLETE) {
			return PqRatchetState.createReady(System.currentTimeMillis());
		}
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
