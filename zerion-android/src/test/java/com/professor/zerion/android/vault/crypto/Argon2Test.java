package com.professor.zerion.android.vault.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;

public class Argon2Test {

	private final Argon2 argon2 = new Argon2();
	private final Argon2.Argon2Params fast =
			new Argon2.Argon2Params(1024, 1, 1, 32);

	private static char[] pw() {
		return "correct horse battery staple".toCharArray();
	}

	@Test
	public void sameInputsProduceSameKey() {
		byte[] salt = new byte[32];
		byte[] a = argon2.deriveKey(pw(), salt, fast);
		byte[] b = argon2.deriveKey(pw(), salt, fast);
		assertEquals(32, a.length);
		assertEquals(32, b.length);
		org.junit.Assert.assertArrayEquals(a, b);
	}

	@Test
	public void differentSaltProducesDifferentKey() {
		byte[] salt1 = new byte[32];
		byte[] salt2 = new byte[32];
		salt2[0] = 1;
		byte[] a = argon2.deriveKey(pw(), salt1, fast);
		byte[] b = argon2.deriveKey(pw(), salt2, fast);
		assertFalse(Arrays.equals(a, b));
	}

	@Test
	public void generatedSaltsAreUniqueAndCorrectLength() {
		byte[] s1 = argon2.generateSalt();
		byte[] s2 = argon2.generateSalt();
		assertEquals(Argon2.DEFAULT_SALT_LENGTH, s1.length);
		assertFalse(Arrays.equals(s1, s2));
	}

	@Test
	public void defaultParamsMeetHardeningFloor() {
		Argon2.Argon2Params p = Argon2.Argon2Params.getDefault();
		assertEquals(256 * 1024, p.memoryKb);
		assertEquals(3, p.iterations);
		assertEquals(32, p.hashLength);
	}

	@Test
	public void theUnstoredParametersNeverChange() {
		assertEquals("262144 3 1 32 32",
				Argon2.DEFAULT_MEMORY_KB + " " + Argon2.DEFAULT_ITERATIONS + " "
						+ Argon2.DEFAULT_PARALLELISM + " "
						+ Argon2.DEFAULT_HASH_LENGTH + " "
						+ Argon2.DEFAULT_SALT_LENGTH);
		Argon2.Argon2Params d = Argon2.Argon2Params.getDefault();
		assertEquals("262144 3 1 32", d.memoryKb + " " + d.iterations + " "
				+ d.parallelism + " " + d.hashLength);
		Argon2.Argon2Params backup = Argon2.Argon2Params.getBackupStrong();
		assertEquals("131072 8 1", backup.memoryKb + " " + backup.iterations
				+ " " + backup.parallelism);
		Argon2.Argon2Params low = Argon2.Argon2Params.getLowMemory();
		assertEquals("131072 2 1", low.memoryKb + " " + low.iterations + " "
				+ low.parallelism);
		Argon2.Argon2Params wallet = Argon2.Argon2Params.getWalletPassword();
		assertEquals("65536 3 1", wallet.memoryKb + " " + wallet.iterations
				+ " " + wallet.parallelism);
	}

	@Test
	public void portableFilesKeepThe256MiBCap() {
		assertEquals(256 * 1024, Argon2.MAX_MEMORY_KB);
		assertEquals(256 * 1024, Argon2.PORTABLE_MAX_MEMORY_KB);
		Argon2.requireSaneParams(256 * 1024, 3, 1);
		refused(() -> Argon2.requireSaneParams(256 * 1024 + 1, 3, 1));
		refused(() -> Argon2.requireSaneParams(512 * 1024, 2, 1));
		refused(() -> Argon2.requireSaneParams(1024 * 1024, 1, 1));
	}

	@Test
	public void theVaultHeaderAcceptsUpTo1GiB() {
		assertEquals(1024 * 1024, Argon2.VAULT_HEADER_MAX_MEMORY_KB);
		Argon2.requireSaneVaultHeaderParams(1024 * 1024, 1, 1);
		Argon2.requireSaneVaultHeaderParams(512 * 1024, 2, 1);
		Argon2.requireSaneVaultHeaderParams(256 * 1024, 3, 1);
		refused(() -> Argon2.requireSaneVaultHeaderParams(1024 * 1024 + 1, 1,
				1));
		refused(() -> Argon2.requireSaneVaultHeaderParams(
				Integer.MAX_VALUE, 1, 1));
		refused(() -> Argon2.requireSaneVaultHeaderParams(1024 * 1024, 11,
				1));
		refused(() -> Argon2.requireSaneVaultHeaderParams(1024 * 1024, 1,
				5));
		refused(() -> Argon2.requireSaneVaultHeaderParams(512, 1, 1));
	}

	private static void refused(Runnable r) {
		try {
			r.run();
			fail("must be refused");
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void withoutTheNativeLibraryMoreThan256MiBIsRefusedAtOnce() {
		assertFalse(NativeArgon2.isAvailable());
		long start = System.nanoTime();
		try {
			argon2.deriveKey(pw(), new byte[32],
					new Argon2.Argon2Params(512 * 1024, 2, 1, 32));
			fail("the Java fallback cannot hold 512 MiB");
		} catch (Argon2.NativeRequiredException expected) {
		}
		assertTrue((System.nanoTime() - start) / 1_000_000L < 2000);
	}

	@Test
	public void theSelfTestFailsWithoutTheNativeLibrary() {
		assertFalse(Argon2.runNativeSelfTest());
		assertFalse(Argon2.nativeSelfTestPasses());
	}

	@Test
	public void theAppPathMatchesTheReferenceAtASmallSize() {
		byte[] salt = new byte[32];
		salt[0] = 7;
		Argon2.Argon2Params p = new Argon2.Argon2Params(2048, 2, 1, 32);
		byte[] app = argon2.deriveKey(pw(), salt, p);
		byte[] ref = Argon2.deriveKeyBouncyCastle(Argon2.toBytes(pw()),
				salt, p);
		org.junit.Assert.assertArrayEquals(ref, app);
	}
}
