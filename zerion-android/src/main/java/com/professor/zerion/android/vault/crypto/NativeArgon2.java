package com.professor.zerion.android.vault.crypto;

/**
 * Bridge to the vendored reference Argon2 (see src/main/cpp). Fail-closed: if
 * the native library is missing or errors, {@link #deriveOrNull} returns null
 * and the caller uses the cryptographically equivalent Bouncy Castle path with
 * the SAME parameters. Never returns a weaker result. Produces the identical
 * Argon2id v1.3 derived key as the Java implementation (proven by tests), so no
 * stored ciphertext needs re-encryption when this is enabled.
 */
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
