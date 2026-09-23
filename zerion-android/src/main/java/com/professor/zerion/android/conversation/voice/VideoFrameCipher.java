package com.professor.zerion.android.conversation.voice;

import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for one video session. A session owns one transmit key and one
 * receive key that are derived from fresh random contributions of both
 * peers and are never reused by another session. Within the session the
 * nonce is the 64-bit frame counter itself, big-endian in the last eight of
 * the twelve nonce bytes, so for a given key every nonce is distinct by
 * construction until the counter would wrap, at which point encryption
 * refuses instead of repeating. Transmit and receive use different keys, so
 * the two directions cannot collide either. The object is single use: once
 * closed it cannot encrypt again.
 */
final class VideoFrameCipher {

	static final int NONCE_LENGTH = 12;
	static final int GCM_TAG_LENGTH = 128;
	static final int KEY_LENGTH = 32;

	private final byte[] txKey;
	private final byte[] rxKey;
	private long txCounter = 0;
	private long rxCounter = 0;
	private boolean closed = false;

	VideoFrameCipher(byte[] txKey, byte[] rxKey) {
		if (txKey.length != KEY_LENGTH || rxKey.length != KEY_LENGTH) {
			throw new IllegalArgumentException("video keys must be 256 bits");
		}
		if (Arrays.equals(txKey, rxKey)) {
			throw new IllegalArgumentException(
					"transmit and receive keys must differ");
		}
		this.txKey = txKey.clone();
		this.rxKey = rxKey.clone();
	}

	static byte[] nonceFor(long counter) {
		if (counter < 0) throw new IllegalStateException("counter exhausted");
		byte[] nonce = new byte[NONCE_LENGTH];
		for (int i = 0; i < 8; i++) {
			nonce[NONCE_LENGTH - 1 - i] = (byte) (counter >>> (8 * i));
		}
		return nonce;
	}

	synchronized long nextTxCounter() {
		return txCounter;
	}

	synchronized byte[] encrypt(byte[] plaintext) throws Exception {
		if (closed) throw new IllegalStateException("cipher closed");
		if (txCounter == Long.MAX_VALUE) {
			close();
			throw new IllegalStateException("counter exhausted");
		}
		byte[] nonce = nonceFor(txCounter);
		txCounter++;
		return run(Cipher.ENCRYPT_MODE, txKey, nonce, plaintext);
	}

	synchronized byte[] decrypt(byte[] ciphertext) throws Exception {
		if (closed) throw new IllegalStateException("cipher closed");
		if (rxCounter == Long.MAX_VALUE) {
			close();
			throw new AEADBadTagException("counter exhausted");
		}
		byte[] nonce = nonceFor(rxCounter);
		rxCounter++;
		return run(Cipher.DECRYPT_MODE, rxKey, nonce, ciphertext);
	}

	private static byte[] run(int mode, byte[] key, byte[] nonce,
			byte[] input) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(GCM_TAG_LENGTH, nonce));
		return cipher.doFinal(input);
	}

	synchronized void close() {
		closed = true;
		Arrays.fill(txKey, (byte) 0);
		Arrays.fill(rxKey, (byte) 0);
	}

	synchronized boolean isClosed() {
		return closed;
	}
}
