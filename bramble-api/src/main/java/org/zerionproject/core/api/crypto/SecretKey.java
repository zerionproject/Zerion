package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.Bytes;

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
