package org.zerionproject.app.conversation.voice;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;

import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.zerionproject.core.util.StringUtils.toUtf8;

@Immutable
@NotNullByDefault
class VoiceCallCryptoImpl implements VoiceCallCrypto {

	private static final String KEY_MATERIAL_LABEL =
			"org.zerionproject.app.voice/KEY_MATERIAL";

	private static final String AUDIO_KEY_LABEL =
			"org.zerionproject.app.voice/AUDIO_KEY";

	private static final String VIDEO_KEY_LABEL =
			"org.zerionproject.app.voice/VIDEO_KEY";

	private static final int SEED_BYTES = 32;
	private static final int AES_KEY_BYTES = 32;
	private static final int GCM_NONCE_BYTES = 12;
	private static final int GCM_TAG_BITS = 128;


	private static final ThreadLocal<Cipher> CIPHER_CACHE =
			ThreadLocal.withInitial(() -> {
				try {
					return Cipher.getInstance("AES/GCM/NoPadding");
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});

	private final CryptoComponent crypto;
	private final SecureRandom secureRandom;

	@Inject
	VoiceCallCryptoImpl(CryptoComponent crypto) {
		this.crypto = crypto;
		this.secureRandom = crypto != null ? crypto.getSecureRandom()
				: new SecureRandom();
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
		try {
			byte[] publicKey = new Ed25519PrivateKeyParameters(localSeed, 0)
					.generatePublicKey().getEncoded();
			return crypto.encodeOnion(publicKey);
		} finally {
			java.util.Arrays.fill(aliceSeed, (byte) 0);
			java.util.Arrays.fill(bobSeed, (byte) 0);
		}
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
		java.util.Arrays.fill(aliceKeyBytes, (byte) 0);
		java.util.Arrays.fill(bobKeyBytes, (byte) 0);

		return new AudioKeys(txKey, rxKey);
	}

	@Override
	public byte[] generateEphemeralSecret() {
		byte[] secret = new byte[AES_KEY_BYTES];
		secureRandom.nextBytes(secret);
		return secret;
	}

	@Override
	public AudioKeys deriveEphemeralAudioKeys(SecretKey voiceCallKey,
			byte[] localEphemeral, byte[] remoteEphemeral, boolean alice) {
		byte[] aliceEphemeral = alice ? localEphemeral : remoteEphemeral;
		byte[] bobEphemeral = alice ? remoteEphemeral : localEphemeral;

		byte[] combined = new byte[aliceEphemeral.length + bobEphemeral.length];
		byte[] aliceKeyBytes = null;
		byte[] bobKeyBytes = null;
		try {
			System.arraycopy(aliceEphemeral, 0, combined, 0,
					aliceEphemeral.length);
			System.arraycopy(bobEphemeral, 0, combined,
					aliceEphemeral.length, bobEphemeral.length);

			SecretKey ephemeralSourceKey = crypto.deriveKey(
					AUDIO_KEY_LABEL + "/EPHEMERAL",
					voiceCallKey,
					combined
			);

			KeyMaterialSource audioKeyMaterial =
					new VoiceCallKeyMaterialSource(ephemeralSourceKey);
			aliceKeyBytes = audioKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
			bobKeyBytes = audioKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
			SecretKey txKey = new SecretKey(
					alice ? aliceKeyBytes : bobKeyBytes);
			SecretKey rxKey = new SecretKey(
					alice ? bobKeyBytes : aliceKeyBytes);
			return new AudioKeys(txKey, rxKey);
		} finally {
			java.util.Arrays.fill(combined, (byte) 0);
			if (aliceKeyBytes != null) {
				java.util.Arrays.fill(aliceKeyBytes, (byte) 0);
			}
			if (bobKeyBytes != null) {
				java.util.Arrays.fill(bobKeyBytes, (byte) 0);
			}
		}
	}

	@Override
	public VideoKeys deriveVideoKeys(SecretKey voiceCallKey, boolean alice) {
		SecretKey videoSourceKey = crypto.deriveKey(
				VIDEO_KEY_LABEL,
				voiceCallKey,
				new byte[0]
		);

		KeyMaterialSource videoKeyMaterial =
				new VoiceCallKeyMaterialSource(videoSourceKey);
		byte[] aliceKeyBytes = videoKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
		byte[] bobKeyBytes = videoKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
		SecretKey txKey = new SecretKey(alice ? aliceKeyBytes : bobKeyBytes);
		SecretKey rxKey = new SecretKey(alice ? bobKeyBytes : aliceKeyBytes);
		java.util.Arrays.fill(aliceKeyBytes, (byte) 0);
		java.util.Arrays.fill(bobKeyBytes, (byte) 0);

		return new VideoKeys(txKey, rxKey);
	}

	@Override
	public VideoKeys deriveEphemeralVideoKeys(SecretKey voiceCallKey,
			byte[] localEphemeral, byte[] remoteEphemeral, boolean alice) {
		byte[] aliceEphemeral = alice ? localEphemeral : remoteEphemeral;
		byte[] bobEphemeral = alice ? remoteEphemeral : localEphemeral;

		byte[] combined = new byte[aliceEphemeral.length + bobEphemeral.length];
		System.arraycopy(aliceEphemeral, 0, combined, 0,
				aliceEphemeral.length);
		System.arraycopy(bobEphemeral, 0, combined, aliceEphemeral.length,
				bobEphemeral.length);

		SecretKey ephemeralSourceKey = crypto.deriveKey(
				VIDEO_KEY_LABEL + "/EPHEMERAL",
				voiceCallKey,
				combined
		);
		java.util.Arrays.fill(combined, (byte) 0);

		KeyMaterialSource videoKeyMaterial =
				new VoiceCallKeyMaterialSource(ephemeralSourceKey);
		byte[] aliceKeyBytes = videoKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
		byte[] bobKeyBytes = videoKeyMaterial.getKeyMaterial(AES_KEY_BYTES);
		SecretKey txKey = new SecretKey(alice ? aliceKeyBytes : bobKeyBytes);
		SecretKey rxKey = new SecretKey(alice ? bobKeyBytes : aliceKeyBytes);
		java.util.Arrays.fill(aliceKeyBytes, (byte) 0);
		java.util.Arrays.fill(bobKeyBytes, (byte) 0);

		return new VideoKeys(txKey, rxKey);
	}

	@Override
	public byte[] encryptAudioFrame(byte[] plaintext, SecretKey key) {
		try {
			byte[] nonce = new byte[GCM_NONCE_BYTES];
			secureRandom.nextBytes(nonce);

			byte[] keyBytes = key.getBytes().clone();
			Cipher cipher = CIPHER_CACHE.get();
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, nonce);
			SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
			cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

			byte[] ciphertextWithTag = cipher.doFinal(plaintext);

			byte[] result = new byte[GCM_NONCE_BYTES + ciphertextWithTag.length];
			System.arraycopy(nonce, 0, result, 0, GCM_NONCE_BYTES);
			System.arraycopy(ciphertextWithTag, 0, result, GCM_NONCE_BYTES, ciphertextWithTag.length);

			java.util.Arrays.fill(keyBytes, (byte) 0);
			return result;

		} catch (Exception e) {
			throw new RuntimeException("Audio frame encryption failed", e);
		}
	}

	@Override
	public byte[] encryptAudioFrame(byte[] plaintext, SecretKey key,
			long frameCounter) {
		try {
			byte[] nonce = new byte[GCM_NONCE_BYTES];
			byte[] keyBytes = key.getBytes().clone();
			java.security.MessageDigest sha256 =
					java.security.MessageDigest.getInstance("SHA-256");
			sha256.update("VOICE_NONCE_SALT".getBytes(
					java.nio.charset.StandardCharsets.UTF_8));
			sha256.update(keyBytes);
			byte[] derived = sha256.digest();
			nonce[0] = derived[0];
			nonce[1] = derived[1];
			nonce[2] = derived[2];
			nonce[3] = derived[3];
			java.util.Arrays.fill(derived, (byte) 0);
			ByteBuffer.wrap(nonce, 4, 8).order(ByteOrder.BIG_ENDIAN)
					.putLong(frameCounter);

			Cipher cipher = CIPHER_CACHE.get();
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, nonce);
			SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
			cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

			byte[] ciphertextWithTag = cipher.doFinal(plaintext);

			byte[] result = new byte[GCM_NONCE_BYTES + ciphertextWithTag.length];
			System.arraycopy(nonce, 0, result, 0, GCM_NONCE_BYTES);
			System.arraycopy(ciphertextWithTag, 0, result, GCM_NONCE_BYTES,
					ciphertextWithTag.length);

			java.util.Arrays.fill(keyBytes, (byte) 0);
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

			byte[] keyBytes = key.getBytes().clone();
			Cipher cipher = CIPHER_CACHE.get();
			GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_BITS, nonce);
			SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
			cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

			byte[] result = cipher.doFinal(ciphertextWithTag);
			java.util.Arrays.fill(keyBytes, (byte) 0);
			return result;

		} catch (Exception e) {
			throw new RuntimeException("Audio frame decryption failed", e);
		}
	}
}
