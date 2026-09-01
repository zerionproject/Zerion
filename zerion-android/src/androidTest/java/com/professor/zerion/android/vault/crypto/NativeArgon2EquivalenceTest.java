package com.professor.zerion.android.vault.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

/**
 * Cross-implementation equivalence gate: the native reference Argon2id must
 * produce the exact same derived key as the Bouncy Castle Java implementation
 * for the same parameters, so existing wallets open unchanged when native is
 * enabled. Runs on a real device where libzargon2.so is loaded.
 */
@RunWith(AndroidJUnit4.class)
public class NativeArgon2EquivalenceTest {

	private static final int WALLET_256MB = 256 * 1024;
	private static final int WALLET_64MB = 64 * 1024;
	private static final int FAST_8MB = 8 * 1024;

	private static byte[] salt(int fill, int len) {
		byte[] s = new byte[len];
		java.util.Arrays.fill(s, (byte) fill);
		return s;
	}

	private void assertEquivalent(String password, byte[] salt, int memKb,
			int iters, int par, int hashLen) {
		byte[] pwd = password.getBytes(StandardCharsets.UTF_8);
		byte[] nativeKey = NativeArgon2.deriveOrNull(pwd.clone(), salt.clone(),
				memKb, iters, par, hashLen);
		byte[] javaKey = Argon2.deriveKeyBouncyCastle(pwd.clone(), salt.clone(),
				new Argon2.Argon2Params(memKb, iters, par, hashLen));
		assertNotNull("native derivation returned null (lib missing?)",
				nativeKey);
		assertArrayEquals("native and Java Argon2id must be byte-identical for "
				+ "mem=" + memKb + " iters=" + iters, javaKey, nativeKey);
	}

	@Test
	public void nativeLibraryIsAvailableOnDevice() {
		assertTrue("libzargon2.so must load on a shipped ABI",
				NativeArgon2.isAvailable());
	}

	@Test
	public void equivalentAcrossPasswordsAndSaltsFast() {
		String[] passwords = {
				"", "p", "correct horse battery staple",
				"пароль-Ünïcodé-中文-🔐",
				"aVeryLongPasswordThatExceedsTypicalLengthsToStressTheKdf1234567890"
		};
		for (int s = 1; s <= 3; s++) {
			for (String p : passwords) {
				assertEquivalent(p, salt(s, 32), FAST_8MB, 3, 1, 32);
			}
		}
	}

	@Test
	public void equivalentAtCurrentNewWalletProfile64Mb() {
		assertEquivalent("wallet-secondary-factor", salt(7, 32),
				WALLET_64MB, 3, 1, 32);
	}

	@Test
	public void equivalentAtExistingWalletProfile256Mb() {
		// The exact profile existing wallets were created with. Slow but this
		// is the load-bearing guarantee that they still open.
		assertEquivalent("existing-wallet-password", salt(2, 32),
				WALLET_256MB, 3, 1, 32);
	}

	@Test
	public void equivalentAcrossIterationCounts() {
		assertEquivalent("iter-check", salt(4, 16), FAST_8MB, 1, 1, 32);
		assertEquivalent("iter-check", salt(4, 16), FAST_8MB, 4, 1, 32);
		assertEquivalent("iter-check", salt(4, 16), FAST_8MB, 3, 1, 64);
	}

	@Test
	public void nativeKdfPerformanceRegressionGate() {
		byte[] pwd = "bench".getBytes(StandardCharsets.UTF_8);
		byte[] s = salt(9, 32);
		long start = android.os.SystemClock.elapsedRealtime();
		byte[] key = NativeArgon2.deriveOrNull(pwd, s, WALLET_256MB, 3, 1, 32);
		long ms = android.os.SystemClock.elapsedRealtime() - start;
		assertNotNull(key);
		assertTrue("native 256MB Argon2id must stay responsive, took " + ms
				+ "ms", ms < 30_000);
	}
}
