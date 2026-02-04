package org.briarproject.briar.privategroup.senderkeys;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.privategroup.senderkeys.GroupMessageCrypto;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKey;
import org.briarproject.briar.api.privategroup.senderkeys.SenderKeyManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
public class GroupMessageCryptoImpl implements GroupMessageCrypto {

	private static final int NONCE_SIZE = 12;
	private static final int TAG_SIZE_BITS = 128;
	private static final String AES_GCM = "AES/GCM/NoPadding";

	private final CryptoComponent crypto;
	private final SenderKeyManager senderKeyManager;

	@Inject
	public GroupMessageCryptoImpl(
			CryptoComponent crypto,
			SenderKeyManager senderKeyManager
	) {
		this.crypto = crypto;
		this.senderKeyManager = senderKeyManager;
	}

	@Override
	public EncryptedGroupMessage encryptGroupMessage(
			Transaction txn,
			GroupId groupId,
			byte[] plaintext,
			long timestamp,
			PrivateKey privateKey
	) throws DbException, GeneralSecurityException {
		SenderKey senderKey = senderKeyManager.getLocalSenderKey(txn, groupId);
		if (senderKey == null) {
			throw new GeneralSecurityException("No local SenderKey for group");
		}

		int epoch = senderKey.getEpoch();
		int messageIndex = senderKey.getMessageIndex();

		byte[] messageKey = deriveMessageKey(senderKey.getChainKey(), messageIndex);

		byte[] nonce = buildNonce(epoch, messageIndex);

		byte[] aad = buildAad(groupId, senderKey.getAuthorId(), epoch, messageIndex, timestamp);

		byte[] ciphertext = aesGcmEncrypt(messageKey, nonce, plaintext, aad);

		byte[] signatureInput = buildSignatureInput(ciphertext, nonce, groupId);
		byte[] signature = crypto.sign(MESSAGE_SIGNATURE_LABEL, signatureInput, privateKey);

		SecretKey newChainKey = advanceChainKey(senderKey.getChainKey());
		SenderKey advancedKey = senderKey.withAdvancedChain(newChainKey, messageIndex + 1);
		senderKeyManager.updateSenderKey(txn, advancedKey);

		return new EncryptedGroupMessageImpl(
				ciphertext,
				nonce,
				epoch,
				messageIndex,
				signature,
				timestamp
		);
	}

	@Override
	public byte[] decryptGroupMessage(
			Transaction txn,
			GroupId groupId,
			AuthorId senderId,
			PublicKey senderPublicKey,
			EncryptedGroupMessage encrypted
	) throws DbException, GeneralSecurityException {
		byte[] signatureInput = buildSignatureInput(
				encrypted.getCiphertext(),
				encrypted.getNonce(),
				groupId
		);

		boolean valid = crypto.verifySignature(
				encrypted.getSignature(),
				MESSAGE_SIGNATURE_LABEL,
				signatureInput,
				senderPublicKey
		);

		if (!valid) {
			throw new GeneralSecurityException("Invalid signature");
		}

		byte[] cachedKey = senderKeyManager.getCachedMessageKey(
				txn, groupId, senderId,
				encrypted.getEpoch(),
				encrypted.getMessageIndex()
		);

		byte[] messageKey;
		if (cachedKey != null) {
			messageKey = cachedKey;
			senderKeyManager.removeCachedMessageKey(
					txn, groupId, senderId,
					encrypted.getEpoch(),
					encrypted.getMessageIndex()
			);
		} else {
			SenderKey senderKey = senderKeyManager.getSenderKey(txn, groupId, senderId);
			if (senderKey == null) {
				throw new GeneralSecurityException("No SenderKey for sender");
			}

			if (encrypted.getEpoch() != senderKey.getEpoch()) {
				throw new GeneralSecurityException("Epoch mismatch");
			}

			int currentIndex = senderKey.getMessageIndex();
			int targetIndex = encrypted.getMessageIndex();

			if (targetIndex < currentIndex) {
				throw new GeneralSecurityException("Message already processed");
			}

			if (targetIndex > currentIndex) {
				cacheSkippedKeys(txn, groupId, senderId, senderKey, targetIndex);
			}

			messageKey = deriveMessageKey(senderKey.getChainKey(), currentIndex);

			for (int i = currentIndex; i < targetIndex; i++) {
				SecretKey advancedChain = advanceChainKey(senderKey.getChainKey());
				senderKey = senderKey.withAdvancedChain(advancedChain, i + 1);
				messageKey = deriveMessageKey(senderKey.getChainKey(), i + 1);
			}

			SecretKey finalChain = advanceChainKey(senderKey.getChainKey());
			SenderKey advancedKey = senderKey.withAdvancedChain(finalChain, targetIndex + 1);
			senderKeyManager.updateSenderKey(txn, advancedKey);
		}

		byte[] aad = buildAad(groupId, senderId,
				encrypted.getEpoch(),
				encrypted.getMessageIndex(),
				encrypted.getTimestamp()
		);

		return aesGcmDecrypt(messageKey, encrypted.getNonce(),
				encrypted.getCiphertext(), aad);
	}

	@Override
	public byte[] deriveMessageKeyAt(SenderKey senderKey, int targetIndex)
			throws GeneralSecurityException {
		SecretKey chainKey = senderKey.getChainKey();
		int currentIndex = senderKey.getMessageIndex();

		for (int i = currentIndex; i < targetIndex; i++) {
			chainKey = advanceChainKey(chainKey);
		}

		return deriveMessageKey(chainKey, targetIndex);
	}

	private byte[] deriveMessageKey(SecretKey chainKey, int messageIndex) {
		byte[] indexBytes = ByteBuffer.allocate(4).putInt(messageIndex).array();
		SecretKey messageKey = crypto.deriveKey(MESSAGE_KEY_LABEL, chainKey, indexBytes);
		return messageKey.getBytes();
	}

	private SecretKey advanceChainKey(SecretKey currentChainKey) {
		return crypto.deriveKey(CHAIN_KEY_LABEL, currentChainKey);
	}

	private byte[] buildNonce(int epoch, int messageIndex) {
		byte[] nonce = new byte[NONCE_SIZE];

		nonce[0] = (byte) (epoch >> 24);
		nonce[1] = (byte) (epoch >> 16);
		nonce[2] = (byte) (epoch >> 8);
		nonce[3] = (byte) epoch;

		nonce[4] = (byte) (messageIndex >> 24);
		nonce[5] = (byte) (messageIndex >> 16);
		nonce[6] = (byte) (messageIndex >> 8);
		nonce[7] = (byte) messageIndex;

		byte[] random = new byte[4];
		crypto.getSecureRandom().nextBytes(random);
		System.arraycopy(random, 0, nonce, 8, 4);

		return nonce;
	}

	private byte[] buildAad(GroupId groupId, AuthorId senderId,
			int epoch, int messageIndex, long timestamp) {
		byte[] groupIdBytes = groupId.getBytes();
		byte[] senderIdBytes = senderId.getBytes();

		ByteBuffer buffer = ByteBuffer.allocate(
				groupIdBytes.length + senderIdBytes.length + 4 + 4 + 8);
		buffer.put(groupIdBytes);
		buffer.put(senderIdBytes);
		buffer.putInt(epoch);
		buffer.putInt(messageIndex);
		buffer.putLong(timestamp);

		return buffer.array();
	}

	private byte[] buildSignatureInput(byte[] ciphertext, byte[] nonce, GroupId groupId) {
		byte[] groupIdBytes = groupId.getBytes();
		byte[] input = new byte[ciphertext.length + nonce.length + groupIdBytes.length];

		int offset = 0;
		System.arraycopy(ciphertext, 0, input, offset, ciphertext.length);
		offset += ciphertext.length;
		System.arraycopy(nonce, 0, input, offset, nonce.length);
		offset += nonce.length;
		System.arraycopy(groupIdBytes, 0, input, offset, groupIdBytes.length);

		return input;
	}

	private void cacheSkippedKeys(
			Transaction txn,
			GroupId groupId,
			AuthorId senderId,
			SenderKey senderKey,
			int targetIndex
	) throws DbException {
		SecretKey chainKey = senderKey.getChainKey();
		int maxSkip = Math.min(targetIndex - senderKey.getMessageIndex(),
				SenderKeyManager.MAX_SKIP);

		for (int i = senderKey.getMessageIndex(); i < senderKey.getMessageIndex() + maxSkip; i++) {
			byte[] skippedKey = deriveMessageKey(chainKey, i);
			senderKeyManager.cacheMessageKey(txn, groupId, senderId,
					senderKey.getEpoch(), i, skippedKey);
			chainKey = advanceChainKey(chainKey);
		}
	}

	private byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(AES_GCM);
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE_BITS, nonce);
		cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
		cipher.updateAAD(aad);
		return cipher.doFinal(plaintext);
	}

	private byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(AES_GCM);
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE_BITS, nonce);
		cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
		cipher.updateAAD(aad);
		return cipher.doFinal(ciphertext);
	}

	private static class EncryptedGroupMessageImpl implements EncryptedGroupMessage {
		private final byte[] ciphertext;
		private final byte[] nonce;
		private final int epoch;
		private final int messageIndex;
		private final byte[] signature;
		private final long timestamp;

		EncryptedGroupMessageImpl(
				byte[] ciphertext,
				byte[] nonce,
				int epoch,
				int messageIndex,
				byte[] signature,
				long timestamp
		) {
			this.ciphertext = ciphertext;
			this.nonce = nonce;
			this.epoch = epoch;
			this.messageIndex = messageIndex;
			this.signature = signature;
			this.timestamp = timestamp;
		}

		@Override
		public byte[] getCiphertext() {
			return ciphertext;
		}

		@Override
		public byte[] getNonce() {
			return nonce;
		}

		@Override
		public int getEpoch() {
			return epoch;
		}

		@Override
		public int getMessageIndex() {
			return messageIndex;
		}

		@Override
		public byte[] getSignature() {
			return signature;
		}

		@Override
		public long getTimestamp() {
			return timestamp;
		}
	}
}
