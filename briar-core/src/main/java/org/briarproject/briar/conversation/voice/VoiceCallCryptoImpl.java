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
import java.util.Base64;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.StringUtils.toUtf8;

/**
 * Implementation of VoiceCallCrypto using Briar's CryptoComponent.
 * <p>
 * Key Material Flow:
 * <pre>
 * Caller:
 *   1. generateVoiceCallKey() → voiceCallKey (random 256-bit key)
 *   2. encodeVoiceCallKey(voiceCallKey) → base64 string
 *   3. Send base64 string in CALL_OFFER
 *
 * Callee:
 *   1. Receive base64 string in CALL_OFFER
 *   2. decodeVoiceCallKey(base64) → voiceCallKey (same key as caller)
 *
 * Both parties:
 *   3. createKeyMaterialSource(voiceCallKey, TorConstants.ID)
 *      +--> transportKey = deriveKey("KEY_MATERIAL", voiceCallKey, transportId)
 *             |
 *             +--> VoiceCallKeyMaterialSource (Salsa20 stream)
 *                    |
 *                    +--> aliceSeed (32 bytes)
 *                    +--> bobSeed (32 bytes)
 * </pre>
 *
 * This ensures:
 * - Perfect Forward Secrecy: Each call has a unique random key
 * - Shared Key: Both parties use the same key (exchanged via signaling)
 * - Transport-specific: Different transports get different key material
 */
@Immutable
@NotNullByDefault
class VoiceCallCryptoImpl implements VoiceCallCrypto {

	private static final Logger LOG =
			getLogger(VoiceCallCryptoImpl.class.getName());

	/**
	 * Label for deriving transport-specific key material from the voice call key.
	 * This ensures different transports get different key material.
	 */
	private static final String KEY_MATERIAL_LABEL =
			"org.briarproject.briar.voice/KEY_MATERIAL";

	/**
	 * Label for deriving audio encryption keys from the voice call key.
	 * Separate from KEY_MATERIAL_LABEL to ensure independence.
	 */
	private static final String AUDIO_KEY_LABEL =
			"org.briarproject.briar.voice/AUDIO_KEY";

	private static final int SEED_BYTES = 32;
	private static final int AES_KEY_BYTES = 32; // AES-256
	private static final int GCM_NONCE_BYTES = 12; // 96-bit nonce for GCM
	private static final int GCM_TAG_BITS = 128; // 128-bit authentication tag

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
		// Generate a new random secret key for this voice call
		// This key will be sent to the callee in the CALL_OFFER message
		return crypto.generateSecretKey();
	}

	@Override
	public String encodeVoiceCallKey(SecretKey key) {
		// Encode the key bytes to base64 for safe transmission in text messages
		return Base64.getEncoder().encodeToString(key.getBytes());
	}

	@Override
	public SecretKey decodeVoiceCallKey(String encoded) {
		try {
			// Decode the base64 string back to key bytes
			byte[] keyBytes = Base64.getDecoder().decode(encoded);
			return new SecretKey(keyBytes);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid voice call key encoding", e);
		}
	}

	@Override
	public KeyMaterialSource createKeyMaterialSource(SecretKey voiceCallKey,
			TransportId transportId) {
		// Derive a transport-specific key from the voice call key
		SecretKey sourceKey = crypto.deriveKey(
				KEY_MATERIAL_LABEL,
				voiceCallKey,
				toUtf8(transportId.getString())
		);

		// Create the key material source using Salsa20-based stream cipher
		return new VoiceCallKeyMaterialSource(sourceKey);
	}

	@Override
	public String getLocalOnion(KeyMaterialSource keyMaterial, boolean alice) {
		// Derive the same seeds that TorPlugin uses
		byte[] aliceSeed = keyMaterial.getKeyMaterial(SEED_BYTES);
		byte[] bobSeed = keyMaterial.getKeyMaterial(SEED_BYTES);

		// Select the local seed based on alice flag
		byte[] localSeed = alice ? aliceSeed : bobSeed;

		// Use Ed25519 to derive the public key from the seed
		// This matches TorRendezvousCryptoImpl.getOnion() exactly
		EdDSAPrivateKeySpec spec = new EdDSAPrivateKeySpec(localSeed, CURVE_SPEC);
		byte[] publicKey = spec.getA().toByteArray();

		// Encode the public key to an onion address
		return crypto.encodeOnion(publicKey);
	}

	@Override
	public AudioKeys deriveAudioKeys(SecretKey voiceCallKey, boolean alice) {
		// Create a FRESH KeyMaterialSource for audio keys
		// This does NOT interfere with onion derivation
		SecretKey audioSourceKey = crypto.deriveKey(
				AUDIO_KEY_LABEL,
				voiceCallKey,
				new byte[0] // No additional context
		);

		KeyMaterialSource audioKeyMaterial = new VoiceCallKeyMaterialSource(audioSourceKey);

		// Derive two 256-bit AES keys
		byte[] aliceKeyBytes = audioKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
		byte[] bobKeyBytes = audioKeyMaterial.getKeyMaterial(AES_KEY_BYTES);

		// Alice uses aliceKey for TX, bobKey for RX
		// Bob uses bobKey for TX, aliceKey for RX
		// This ensures perfect bidirectional encryption
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
			// Generate random 12-byte nonce (IV) for GCM
			byte[] nonce = new byte[GCM_NONCE_BYTES];
			secureRandom.nextBytes(nonce);

			// Initialize AES-GCM cipher
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, nonce);
			SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
			cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

			// Encrypt plaintext (produces ciphertext + 16-byte auth tag)
			byte[] ciphertextWithTag = cipher.doFinal(plaintext);

			// Return: nonce || ciphertext || tag
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
			// Parse: nonce || ciphertext || tag
			if (ciphertext.length < GCM_NONCE_BYTES + 16) {
				throw new IllegalArgumentException("Ciphertext too short");
			}

			// Extract nonce
			byte[] nonce = new byte[GCM_NONCE_BYTES];
			System.arraycopy(ciphertext, 0, nonce, 0, GCM_NONCE_BYTES);

			// Extract ciphertext + tag
			int ciphertextLength = ciphertext.length - GCM_NONCE_BYTES;
			byte[] ciphertextWithTag = new byte[ciphertextLength];
			System.arraycopy(ciphertext, GCM_NONCE_BYTES, ciphertextWithTag, 0, ciphertextLength);

			// Initialize AES-GCM cipher for decryption
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, nonce);
			SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "AES");
			cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

			// Decrypt and verify authentication tag
			return cipher.doFinal(ciphertextWithTag);

		} catch (Exception e) {
			if (LOG.isLoggable(WARNING)) {
				LOG.warning("Audio frame decryption failed: " + e.getMessage());
			}
			throw new RuntimeException("Audio frame decryption failed", e);
		}
	}

	/**
	 * Converts bytes to hex string for logging (first N bytes only).
	 */
	private String bytesToHex(byte[] bytes, int offset, int length) {
		StringBuilder sb = new StringBuilder();
		for (int i = offset; i < Math.min(offset + length, bytes.length); i++) {
			sb.append(String.format("%02x", bytes[i]));
		}
		return sb.toString();
	}
}
