package com.professor.zerion.android.vault.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

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
}
