package org.briarproject.bramble.api.crypto;

import org.briarproject.bramble.api.Bytes;

import java.util.Arrays;

public class SecretKey extends Bytes {

	public static final int LENGTH = 32;

	public SecretKey(byte[] key) {
		super(key);
		if (key.length != LENGTH) throw new IllegalArgumentException();
	}

	public void clear() {
		Arrays.fill(getBytes(), (byte) 0);
	}
}
