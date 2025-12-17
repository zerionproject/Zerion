package org.briarproject.briar.conversation.voice;

import net.i2p.crypto.eddsa.spec.EdDSANamedCurveSpec;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.security.SecureRandom;
import java.util.logging.Logger;

import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.toHexString;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.StringUtils.toUtf8;

@Immutable
@NotNullByDefault
class VoiceCallCryptoImpl implements VoiceCallCrypto {

	private static final Logger LOG =
			getLogger(VoiceCallCryptoImpl.class.getName());

	private static final String KEY_MATERIAL_LABEL =
			"org.briarproject.briar.voice/KEY_MATERIAL";

	private static final String AUDIO_KEY_LABEL =
			"org.briarproject.briar.voice/AUDIO_KEY";

	private static final int SEED_BYTES = 32;
	private static final int AES_KEY_BYTES = 32;
	private static final int GCM_NONCE_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;

	private static final EdDSANamedCurveSpec CURVE_SPEC =
			EdDSANamedCurveTable.getByName("Ed25519");

	private final CryptoComponent crypto;
	private final SecureRandom secureRandom;

	@Inject
	VoiceCallCryptoImpl(CryptoComponent crypto) {
		this.crypto = crypto;
		this.secureRandom = crypto.getSecureRandom();
	}

	@Override
	public SecretKey generateVoiceCallKey() {
		return crypto.generateSecretKey();
	}

	@Override
	public String encodeVoiceCallKey(SecretKey key) {
		return toHexString(key.getBytes());
	}

	@Override
	public SecretKey decodeVoiceCallKey(String encoded) {
		try {
			byte[] keyBytes = fromHexString(encoded);
			return new SecretKey(keyBytes);
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid voice call key encoding", e);
		}
	}

	@Override
	public KeyMaterialSource createKeyMaterialSource(SecretKey voiceCallKey,
			TransportId transportId) {
		SecretKey sourceKey = crypto.deriveKey(
				KEY_MATERIAL_LABEL,
				voiceCallKey,
				toUtf8(transportId.getString())
		);
		return new VoiceCallKeyMaterialSource(sourceKey);
	}

	@Override
	public String getLocalOnion(KeyMaterialSource keyMaterial, boolean alice) {
		byte[] aliceSeed = keyMaterial.getKeyMaterial(SEED_BYTES);
		byte[] bobSeed = keyMaterial.getKeyMaterial(SEED_BYTES);
		byte[] localSeed = alice ? aliceSeed : bobSeed;
		EdDSAPrivateKeySpec spec = new EdDSAPrivateKeySpec(localSeed, CURVE_SPEC);
		byte[] publicKey = spec.getA().toByteArray();
		return crypto.encodeOnion(publicKey);
	}

	@Override
	public AudioKeys deriveAudioKeys(SecretKey voiceCallKey, boolean alice) {
		SecretKey audioSourceKey = crypto.deriveKey(
				AUDIO_KEY_LABEL,
				voiceCallKey,
				new byte[0]
		);

		KeyMaterialSource audioKeyMaterial = new VoiceCallKeyMaterialSource(audioSourceKey);
		byte[] aliceKeyBytes = audioKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
		byte[] bobKeyBytes = audioKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
		SecretKey txKey = new SecretKey(alice ? aliceKeyBytes : bobKeyBytes);
		SecretKey rxKey = new SecretKey(alice ? bobKeyBytes : aliceKeyBytes);

		if (LOG.isLoggable(java.util.logging.Level.INFO)) {
			LOG.info("Derived audio keys (alice=" + alice + ") " +
					"txKey=" + bytesToHex(txKey.getBytes(), 0, 8) + "... " +
					"rxKey=" + bytesToHex(rxKey.getBytes(), 0, 8) + "...");
		}

		return new AudioKeys(txKey, rxKey);
	}

	@Override
	public byte[] encryptAudioFrame(byte[] plaintext, SecretKey key) {
		try {
			byte[] nonce = new byte[GCM_NONCE_BYTES];
			secureRandom.nextBytes(nonce);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, nonce);
			SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
			cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

			byte[] ciphertextWithTag = cipher.doFinal(plaintext);

			byte[] result = new byte[GCM_NONCE_BYTES + ciphertextWithTag.length];
			System.arraycopy(nonce, 0, result, 0, GCM_NONCE_BYTES);
			System.arraycopy(ciphertextWithTag, 0, result, GCM_NONCE_BYTES, ciphertextWithTag.length);

			return result;

		} catch (Exception e) {
			throw new RuntimeException("Audio frame encryption failed", e);
		}
	}

	@Override
	public byte[] decryptAudioFrame(byte[] ciphertext, SecretKey key) {
		try {
			if (ciphertext.length < GCM_NONCE_BYTES + 16) {
				throw new IllegalArgumentException("Ciphertext too short");
			}

			byte[] nonce = new byte[GCM_NONCE_BYTES];
			System.arraycopy(ciphertext, 0, nonce, 0, GCM_NONCE_BYTES);

			int ciphertextLength = ciphertext.length - GCM_NONCE_BYTES;
			byte[] ciphertextWithTag = new byte[ciphertextLength];
			System.arraycopy(ciphertext, GCM_NONCE_BYTES, ciphertextWithTag, 0, ciphertextLength);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, nonce);
			SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
			cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

			return cipher.doFinal(ciphertextWithTag);

		} catch (Exception e) {
			if (LOG.isLoggable(WARNING)) {
				LOG.warning("Audio frame decryption failed: " + e.getMessage());
			}
			throw new RuntimeException("Audio frame decryption failed", e);
		}
	}

	private String bytesToHex(byte[] bytes, int offset, int length) {
		StringBuilder sb = new StringBuilder();
		for (int i = offset; i < Math.min(offset + length, bytes.length); i++) {
			sb.append(String.format("%02x", bytes[i]));
		}
		return sb.toString();
	}
}
