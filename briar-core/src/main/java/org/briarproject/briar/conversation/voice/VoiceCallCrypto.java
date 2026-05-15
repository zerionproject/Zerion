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

	byte[] generateEphemeralSecret();

	AudioKeys deriveEphemeralAudioKeys(SecretKey voiceCallKey,
			byte[] localEphemeral, byte[] remoteEphemeral, boolean alice);

	byte[] encryptAudioFrame(byte[] plaintext, SecretKey key);

	byte[] encryptAudioFrame(byte[] plaintext, SecretKey key,
			long frameCounter);

	byte[] decryptAudioFrame(byte[] ciphertext, SecretKey key);

	VideoKeys deriveVideoKeys(SecretKey voiceCallKey, boolean alice);

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

	class VideoKeys {
		public final SecretKey txKey;
		public final SecretKey rxKey;

		public VideoKeys(SecretKey txKey, SecretKey rxKey) {
			this.txKey = txKey;
			this.rxKey = rxKey;
		}
	}
}
