package org.briarproject.briar.privategroup.senderkeys;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.privategroup.senderkeys.EpochRotationManager;
import org.briarproject.briar.api.privategroup.senderkeys.GroupCryptoState;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKey;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
public class EpochRotationManagerImpl implements EpochRotationManager {

	private final CryptoComponent crypto;
	private final Clock clock;
	private final SenderKeyManager senderKeyManager;

	@Inject
	public EpochRotationManagerImpl(
			CryptoComponent crypto,
			Clock clock,
			SenderKeyManager senderKeyManager
	) {
		this.crypto = crypto;
		this.clock = clock;
		this.senderKeyManager = senderKeyManager;
	}

	@Override
	public boolean checkRotationNeeded(Transaction txn, GroupId groupId, long now)
			throws DbException {
		SenderKey localKey = senderKeyManager.getLocalSenderKey(txn, groupId);
		if (localKey == null) {
			return false;
		}
		return senderKeyManager.shouldRotateEpoch(localKey, now);
	}

	@Override
	public SenderKey rotateEpoch(
			Transaction txn,
			GroupId groupId,
			@Nullable byte[] pqSharedSecret
	) throws DbException {
		SenderKey currentKey = senderKeyManager.getLocalSenderKey(txn, groupId);
		if (currentKey == null) {
			throw new DbException();
		}

		int newEpoch = currentKey.getEpoch() + 1;
		long rotationTime = clock.currentTimeMillis();

		byte[] newChainKeyBytes = deriveEpochChainKey(
				currentKey.getChainKey().getBytes(),
				pqSharedSecret,
				groupId,
				newEpoch
		);

		SenderKey revokedKey = currentKey.withState(SenderKeyState.REVOKED);
		senderKeyManager.updateSenderKey(txn, revokedKey);

		SenderKey newKey = currentKey.withNewChainKey(
				new SecretKey(newChainKeyBytes),
				newEpoch,
				rotationTime
		);
		senderKeyManager.storeSenderKey(txn, newKey);

		GroupCryptoState state = senderKeyManager.getGroupCryptoState(txn, groupId);
		if (state != null) {
			senderKeyManager.updateGroupCryptoState(txn,
					state.withRekey(rotationTime, GroupCryptoState.RekeyReason.EPOCH));
		}

		return newKey;
	}

	@Override
	public void handleIncomingEpochRotation(
			Transaction txn,
			GroupId groupId,
			SenderKey newSenderKey
	) throws DbException {
		SenderKey existingKey = senderKeyManager.getSenderKey(
				txn, groupId, newSenderKey.getAuthorId());

		if (existingKey != null) {
			if (newSenderKey.getEpoch() <= existingKey.getEpoch()) {
				return;
			}

			SenderKey revokedKey = existingKey.withState(SenderKeyState.REVOKED);
			senderKeyManager.updateSenderKey(txn, revokedKey);
		}

		senderKeyManager.storeSenderKey(txn, newSenderKey);
	}

	@Override
	public byte[] deriveEpochChainKey(
			byte[] currentChainKey,
			@Nullable byte[] pqSharedSecret,
			GroupId groupId,
			int newEpoch
	) {
		byte[] ikm;
		if (pqSharedSecret != null) {
			ikm = new byte[currentChainKey.length + pqSharedSecret.length];
			System.arraycopy(currentChainKey, 0, ikm, 0, currentChainKey.length);
			System.arraycopy(pqSharedSecret, 0, ikm, currentChainKey.length, pqSharedSecret.length);
		} else {
			ikm = currentChainKey;
		}

		byte[] groupIdBytes = groupId.getBytes();
		byte[] epochBytes = ByteBuffer.allocate(4).putInt(newEpoch).array();
		byte[] salt = new byte[groupIdBytes.length + epochBytes.length];
		System.arraycopy(groupIdBytes, 0, salt, 0, groupIdBytes.length);
		System.arraycopy(epochBytes, 0, salt, groupIdBytes.length, epochBytes.length);

		SecretKey derived = crypto.deriveKey(SENDER_KEY_EPOCH_LABEL,
				new SecretKey(ikm), salt);

		return derived.getBytes();
	}
}
