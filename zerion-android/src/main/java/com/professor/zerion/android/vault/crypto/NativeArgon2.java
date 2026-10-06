package com.professor.zerion.android.vault.crypto;

public final class NativeArgon2 {

	private static final boolean AVAILABLE;

	static {
		boolean ok;
		try {
			System.loadLibrary("zargon2");
			ok = true;
		} catch (Throwable t) {
			ok = false;
		}
		AVAILABLE = ok;
	}

	private NativeArgon2() {
	}

	public static boolean isAvailable() {
		return AVAILABLE;
	}

	static native byte[] deriveRaw(byte[] pwd, byte[] salt, int mCostKb,
			int tCost, int parallelism, int hashLen);

	public static byte[] deriveOrNull(byte[] pwd, byte[] salt, int mCostKb,
			int tCost, int parallelism, int hashLen) {
		if (!AVAILABLE) {
			return null;
		}
		try {
			byte[] out = deriveRaw(pwd, salt, mCostKb, tCost, parallelism,
					hashLen);
			return (out != null && out.length == hashLen) ? out : null;
		} catch (Throwable t) {
			return null;
		}
	}
}
