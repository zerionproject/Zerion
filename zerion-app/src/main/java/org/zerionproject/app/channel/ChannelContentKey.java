package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;

@NotNullByDefault
class ChannelContentKey {

	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final int GCM_IV_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;

	private static void zeroSecretKeySpec(SecretKeySpec spec) {
	}
	private static final String DERIVE_LABEL_WRAP =
			"org.zerionproject/CHANNEL_CONTENT_KEY_WRAP";
	private static final String DERIVE_LABEL_BODY_NONCE =
			"org.zerionproject/CHANNEL_BODY_NONCE";

	private final CryptoComponent crypto;
	private final SecureRandom random;

	@Inject
	ChannelContentKey(CryptoComponent crypto) {
		this.crypto = crypto;
		this.random = new SecureRandom();
	}

	byte[] generateContentKey() {
		byte[] k = new byte[ChannelConstants.CONTENT_KEY_BYTES];
		random.nextBytes(k);
		return k;
	}

	byte[] hashContentKey(byte[] contentKey) {
		return crypto.hash(
				"org.zerionproject/CHANNEL_CONTENT_KEY_HASH",
				contentKey);
	}

	byte[] wrapContentKey(byte[] capability, byte[] channelId,
			byte[] contentKey) throws GeneralSecurityException {
		SecretKey wrapKey = deriveWrapKey(capability, channelId);
		byte[] wrapBytes = wrapKey.getBytes();
		SecretKeySpec spec = new SecretKeySpec(wrapBytes, "AES");
		try {
			byte[] iv = new byte[GCM_IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.ENCRYPT_MODE, spec,
					new GCMParameterSpec(GCM_TAG_BITS, iv));
			byte[] ct = cipher.doFinal(contentKey);
			ByteBuffer envelope =
					ByteBuffer.allocate(iv.length + ct.length);
			envelope.put(iv);
			envelope.put(ct);
			return envelope.array();
		} finally {
			Arrays.fill(wrapBytes, (byte) 0);
			zeroSecretKeySpec(spec);
		}
	}

	byte[] unwrapContentKey(byte[] capability, byte[] channelId,
			byte[] envelope) throws GeneralSecurityException {
		if (envelope.length < GCM_IV_BYTES + 16) {
			throw new GeneralSecurityException("Envelope too short");
		}
		byte[] iv = Arrays.copyOfRange(envelope, 0, GCM_IV_BYTES);
		byte[] ct = Arrays.copyOfRange(envelope, GCM_IV_BYTES,
				envelope.length);
		SecretKey wrapKey = deriveWrapKey(capability, channelId);
		byte[] wrapBytes = wrapKey.getBytes();
		SecretKeySpec spec = new SecretKeySpec(wrapBytes, "AES");
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.DECRYPT_MODE, spec,
					new GCMParameterSpec(GCM_TAG_BITS, iv));
			return cipher.doFinal(ct);
		} finally {
			Arrays.fill(wrapBytes, (byte) 0);
			zeroSecretKeySpec(spec);
		}
	}

	byte[] encryptBody(byte[] contentKey, byte[] channelId,
			long seqNum, String plaintextBody)
			throws GeneralSecurityException {
		byte[] nonce = bodyNonce(channelId, seqNum);
		byte[] aad = bodyAad(channelId, seqNum);
		SecretKeySpec spec = new SecretKeySpec(contentKey, "AES");
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.ENCRYPT_MODE, spec,
					new GCMParameterSpec(GCM_TAG_BITS, nonce));
			cipher.updateAAD(aad);
			return cipher.doFinal(
					plaintextBody.getBytes(StandardCharsets.UTF_8));
		} finally {
			zeroSecretKeySpec(spec);
		}
	}

	String decryptBody(byte[] contentKey, byte[] channelId,
			long seqNum, byte[] ciphertextBody)
			throws GeneralSecurityException {
		byte[] nonce = bodyNonce(channelId, seqNum);
		byte[] aad = bodyAad(channelId, seqNum);
		SecretKeySpec spec = new SecretKeySpec(contentKey, "AES");
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.DECRYPT_MODE, spec,
					new GCMParameterSpec(GCM_TAG_BITS, nonce));
			cipher.updateAAD(aad);
			byte[] plain = cipher.doFinal(ciphertextBody);
			return new String(plain, StandardCharsets.UTF_8);
		} finally {
			zeroSecretKeySpec(spec);
		}
	}

	byte[] generateAttachmentKey() {
		byte[] k = new byte[ChannelConstants.CONTENT_KEY_BYTES];
		random.nextBytes(k);
		return k;
	}

	byte[] encryptBlob(byte[] perAttKey, byte[] channelId, String mime,
			long sizeBytes, byte[] plaintext)
			throws GeneralSecurityException {
		byte[] nonce = new byte[GCM_IV_BYTES];
		random.nextBytes(nonce);
		byte[] aad = blobAad(channelId, mime, sizeBytes);
		SecretKeySpec spec = new SecretKeySpec(perAttKey, "AES");
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.ENCRYPT_MODE, spec,
					new GCMParameterSpec(GCM_TAG_BITS, nonce));
			cipher.updateAAD(aad);
			byte[] ct = cipher.doFinal(plaintext);
			ByteBuffer out = ByteBuffer.allocate(nonce.length + ct.length);
			out.put(nonce);
			out.put(ct);
			return out.array();
		} finally {
			zeroSecretKeySpec(spec);
		}
	}

	byte[] decryptBlob(byte[] perAttKey, byte[] channelId, String mime,
			long sizeBytes, byte[] blob)
			throws GeneralSecurityException {
		if (blob.length < GCM_IV_BYTES + 16) {
			throw new GeneralSecurityException("Blob too short");
		}
		byte[] nonce = Arrays.copyOfRange(blob, 0, GCM_IV_BYTES);
		byte[] ct = Arrays.copyOfRange(blob, GCM_IV_BYTES, blob.length);
		byte[] aad = blobAad(channelId, mime, sizeBytes);
		SecretKeySpec spec = new SecretKeySpec(perAttKey, "AES");
		try {
			Cipher cipher = Cipher.getInstance(AES_GCM);
			cipher.init(Cipher.DECRYPT_MODE, spec,
					new GCMParameterSpec(GCM_TAG_BITS, nonce));
			cipher.updateAAD(aad);
			return cipher.doFinal(ct);
		} finally {
			zeroSecretKeySpec(spec);
		}
	}

	private byte[] blobAad(byte[] channelId, String mime, long sizeBytes) {
		byte[] mimeBytes = mime.getBytes(StandardCharsets.UTF_8);
		ByteBuffer buf = ByteBuffer.allocate(
				channelId.length + 4 + mimeBytes.length + 8);
		buf.put(channelId);
		buf.putInt(mimeBytes.length);
		buf.put(mimeBytes);
		buf.putLong(sizeBytes);
		return buf.array();
	}

	private SecretKey deriveWrapKey(byte[] capability, byte[] channelId) {
		SecretKey capabilityKey = new SecretKey(capability);
		byte[] info = ChannelConstants.CONTENT_KEY_WRAP_INFO
				.getBytes(StandardCharsets.US_ASCII);
		return crypto.deriveKey(DERIVE_LABEL_WRAP,
				capabilityKey, channelId, info);
	}

	private byte[] bodyNonce(byte[] channelId, long seqNum) {
		ByteBuffer buf = ByteBuffer.allocate(8);
		buf.putLong(seqNum);
		byte[] derived = crypto.hash(DERIVE_LABEL_BODY_NONCE,
				channelId, buf.array());
		return Arrays.copyOfRange(derived, 0, GCM_IV_BYTES);
	}

	private byte[] bodyAad(byte[] channelId, long seqNum) {
		ByteBuffer buf = ByteBuffer.allocate(channelId.length + 8);
		buf.put(channelId);
		buf.putLong(seqNum);
		return buf.array();
	}
}
