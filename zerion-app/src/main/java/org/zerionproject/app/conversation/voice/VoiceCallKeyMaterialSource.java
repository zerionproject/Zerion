package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

@ThreadSafe
@NotNullByDefault
class VoiceCallKeyMaterialSource implements KeyMaterialSource {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final SecretKey sourceKey;

	@GuardedBy("this")
	private int counter = 0;

	VoiceCallKeyMaterialSource(SecretKey sourceKey) {
		this.sourceKey = sourceKey;
	}

	@Override
	public synchronized byte[] getKeyMaterial(int length) {
		byte[] keyBytes = sourceKey.getBytes().clone();
		try {
			byte[] result = new byte[length];
			int offset = 0;
			while (offset < length) {
				Mac mac = Mac.getInstance(HMAC_ALGORITHM);
				mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
				byte[] counterBytes = intToBytes(counter++);
				byte[] block = mac.doFinal(counterBytes);
				int toCopy = Math.min(block.length, length - offset);
				System.arraycopy(block, 0, result, offset, toCopy);
				offset += toCopy;
				java.util.Arrays.fill(block, (byte) 0);
			}
			return result;
		} catch (GeneralSecurityException e) {
			throw new RuntimeException("Failed to generate key material", e);
		} finally {
			java.util.Arrays.fill(keyBytes, (byte) 0);
		}
	}

	private byte[] intToBytes(int value) {
		return new byte[] {
			(byte) (value >>> 24),
			(byte) (value >>> 16),
			(byte) (value >>> 8),
			(byte) value
		};
	}
}
