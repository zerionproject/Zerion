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

	
	byte[] encryptAudioFrame(byte[] plaintext, SecretKey key);

	
	byte[] decryptAudioFrame(byte[] ciphertext, SecretKey key);

	
	class AudioKeys {
		
		public final SecretKey txKey;
		
		public final SecretKey rxKey;

		public AudioKeys(SecretKey txKey, SecretKey rxKey) {
			this.txKey = txKey;
			this.rxKey = rxKey;
		}
	}
}
