package com.professor.zerion.android.security;

public final class TestZerionEncryptedPrefs {

	private TestZerionEncryptedPrefs() {
	}

	public static void reset() {
		ZerionEncryptedPrefs.resetForTests();
	}
}
