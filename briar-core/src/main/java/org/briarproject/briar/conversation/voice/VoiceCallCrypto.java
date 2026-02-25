package org.briarproject.briar.conversation.voice;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.nullsafety.NotNullByDefault;


@NotNullByDefault
public interface VoiceCallCrypto {

	
	SecretKey generateVoiceCallKey();

	
	String encodeVoiceCallKey(SecretKey key);

	
	SecretKey decodeVoiceCallKey(String encoded);

	
	KeyMaterialSource createKeyMaterialSource(SecretKey voiceCallKey,
			TransportId transportId);

	
	String getLocalOnion(KeyMaterialSource keyMaterial, boolean alice);

	
	AudioKeys deriveAudioKeys(SecretKey voiceCallKey, boolean alice);

	/**
	 * Generate a 32-byte ephemeral secret for forward secrecy.
	 * This secret is exchanged during call signaling and mixed into
	 * the audio key derivation. It MUST be zeroed after use.
	 */
	byte[] generateEphemeralSecret();

	/**
	 * Derive audio keys with forward secrecy using ephemeral secrets
	 * from both parties. The ephemeral secrets ensure that even if the
	 * static voiceCallKey is compromised, past call audio cannot be
	 * decrypted.
	 */
	AudioKeys deriveEphemeralAudioKeys(SecretKey voiceCallKey,
			byte[] localEphemeral, byte[] remoteEphemeral, boolean alice);


	byte[] encryptAudioFrame(byte[] plaintext, SecretKey key);

	byte[] encryptAudioFrame(byte[] plaintext, SecretKey key,
			long frameCounter);


	byte[] decryptAudioFrame(byte[] ciphertext, SecretKey key);

	/**
	 * Derive separate encryption keys for the video stream.
	 * Uses a distinct HKDF label so video keys are cryptographically
	 * independent from audio keys.
	 */
	VideoKeys deriveVideoKeys(SecretKey voiceCallKey, boolean alice);

	/**
	 * Derive video keys with forward secrecy using ephemeral secrets.
	 */
	VideoKeys deriveEphemeralVideoKeys(SecretKey voiceCallKey,
			byte[] localEphemeral, byte[] remoteEphemeral, boolean alice);


	class AudioKeys {

		public final SecretKey txKey;

		public final SecretKey rxKey;

		public AudioKeys(SecretKey txKey, SecretKey rxKey) {
			this.txKey = txKey;
			this.rxKey = rxKey;
		}
	}

	/**
	 * Holds separate tx/rx keys for the video stream.
	 */
	class VideoKeys {
		public final SecretKey txKey;
		public final SecretKey rxKey;

		public VideoKeys(SecretKey txKey, SecretKey rxKey) {
			this.txKey = txKey;
			this.rxKey = rxKey;
		}
	}
}
