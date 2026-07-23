package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
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

		public void destroy() {
			if (txKey != null) txKey.clear();
			if (rxKey != null) rxKey.clear();
		}
	}

	class VideoKeys {
		public final SecretKey txKey;
		public final SecretKey rxKey;

		public VideoKeys(SecretKey txKey, SecretKey rxKey) {
			this.txKey = txKey;
			this.rxKey = rxKey;
		}

		public void destroy() {
			if (txKey != null) txKey.clear();
			if (rxKey != null) rxKey.clear();
		}
	}
}
