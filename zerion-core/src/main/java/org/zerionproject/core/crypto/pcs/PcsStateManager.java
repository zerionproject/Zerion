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
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
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

	@Nullable
	public Mode3FullState loadSharedMode3FullState(ContactId contactId) {
		try {
			return db.transactionWithNullableResult(true, txn -> {
				Object[] row = db.getPcsMode2SessionState(txn, contactId,
						PCS_DIRECTION_SEND);
				if (row == null) return null;
				byte[] blob = (byte[]) row[8];
				if (blob == null) return null;
				return Mode3FullStateCodec.decode(blob);
			});
		} catch (DbException e) {
			return null;
		}
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
		byte[] mode3FullStateBlob = (byte[]) result[8];

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

		Mode3FullState mode3FullState = null;
		if (mode3FullStateBlob != null) {
			mode3FullState = Mode3FullStateCodec.decode(mode3FullStateBlob);
		}

		return new PcsSessionState(chainKey, messageNumber, previousChainLength,
				rootKey, dhState, mode3FullState != null, 0, mode3FullState);
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

		Mode3FullState mode3FullState = state.getMode3FullState();
		Mode3FullState merged = mode3FullState;
		if (mode3FullState != null) {
			Object[] currentRow = db.getPcsMode2SessionState(txn, contactId,
					direction);
			Mode3FullState currentBlob = null;
			if (currentRow != null) {
				byte[] curBytes = (byte[]) currentRow[8];
				if (curBytes != null) {
					currentBlob = Mode3FullStateCodec.decode(curBytes);
				}
			}
			if (currentBlob != null) {
				if (direction == PCS_DIRECTION_SEND) {
					merged = new Mode3FullState(
							currentBlob.getTheirActivePqPk(),
							mode3FullState.getOurActiveKeyPair(),
							mode3FullState.getRecentKeyPairs(),
							mode3FullState.getMessageCounter());
				} else {
					merged = new Mode3FullState(
							mode3FullState.getTheirActivePqPk(),
							currentBlob.getOurActiveKeyPair(),
							currentBlob.getRecentKeyPairs(),
							mode3FullState.getMessageCounter());
				}
			}
		}
		byte[] mode3FullStateBlob = merged != null
				? Mode3FullStateCodec.encode(merged)
				: null;

		db.setPcsMode2SessionState(txn, contactId, direction,
				state.getChainKey(), state.getMessageNumber(),
				state.getPreviousChainLength(), state.getRootKey(),
				dhPrivateKey, dhPublicKey, dhRemotePublicKey, state.isMode2(),
				mode3FullStateBlob);

		if (merged != null) {
			propagateSharedMode3FullFields(txn, contactId, direction, merged);
		}
	}

	private void propagateSharedMode3FullFields(Transaction txn,
			ContactId contactId, int direction, Mode3FullState source)
			throws DbException {
		int otherDirection = direction == PCS_DIRECTION_SEND
				? PCS_DIRECTION_RECEIVE : PCS_DIRECTION_SEND;
		Object[] otherRow = db.getPcsMode2SessionState(txn, contactId,
				otherDirection);
		if (otherRow == null) return;
		byte[] otherBlob = (byte[]) otherRow[8];
		if (otherBlob == null) return;
		Mode3FullState otherState = Mode3FullStateCodec.decode(otherBlob);
		if (otherState == null) return;
		Mode3FullState merged;
		if (direction == PCS_DIRECTION_SEND) {
			merged = new Mode3FullState(
					otherState.getTheirActivePqPk(),
					source.getOurActiveKeyPair(),
					source.getRecentKeyPairs(),
					otherState.getMessageCounter());
		} else {
			merged = new Mode3FullState(
					source.getTheirActivePqPk(),
					otherState.getOurActiveKeyPair(),
					otherState.getRecentKeyPairs(),
					otherState.getMessageCounter());
		}
		byte[] mergedBlob = Mode3FullStateCodec.encode(merged);
		byte[] chainKeyBytes = (byte[]) otherRow[0];
		int otherMsgNum = (Integer) otherRow[1];
		int otherPrev = (Integer) otherRow[2];
		byte[] rootKeyBytes = (byte[]) otherRow[3];
		byte[] otherDhPriv = (byte[]) otherRow[4];
		byte[] otherDhPub = (byte[]) otherRow[5];
		byte[] otherDhRemote = (byte[]) otherRow[6];
		boolean otherMode2 = (Boolean) otherRow[7];
		SecretKey otherChainKey = new SecretKey(chainKeyBytes);
		SecretKey otherRootKey = rootKeyBytes != null
				? new SecretKey(rootKeyBytes) : null;
		PrivateKey otherDhPrivKey = null;
		PublicKey otherDhPubKey = null;
		PublicKey otherDhRemoteKey = null;
		if (otherDhPriv != null && otherDhPub != null) {
			try {
				KeyParser kp = crypto.getAgreementKeyParser();
				otherDhPrivKey = kp.parsePrivateKey(otherDhPriv);
				otherDhPubKey = kp.parsePublicKey(otherDhPub);
				if (otherDhRemote != null) {
					otherDhRemoteKey = kp.parsePublicKey(otherDhRemote);
				}
			} catch (GeneralSecurityException e) {
				return;
			}
		}
		db.setPcsMode2SessionState(txn, contactId, otherDirection,
				otherChainKey, otherMsgNum, otherPrev, otherRootKey,
				otherDhPrivKey, otherDhPubKey, otherDhRemoteKey,
				otherMode2, mergedBlob);
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
