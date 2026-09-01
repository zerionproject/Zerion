package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class XmrWalletKekTest {

	private static char[] pw() {
		return "correct horse battery staple".toCharArray();
	}

	private static byte[] salt(int b) {
		byte[] s = new byte[32];
		Arrays.fill(s, (byte) b);
		return s;
	}

	@Test
	public void samePasswordAndSaltDeriveTheSameFilePassword() {
		char[] a = XmrWalletKek.deriveMainFilePassword(pw(), salt(1));
		char[] b = XmrWalletKek.deriveMainFilePassword(pw(), salt(1));
		assertTrue("derivation must be deterministic", Arrays.equals(a, b));
		assertTrue("the derived password is high entropy (non-trivial length)",
				a.length >= 40);
	}

	@Test
	public void differentSaltDerivesADifferentFilePassword() {
		char[] a = XmrWalletKek.deriveMainFilePassword(pw(), salt(1));
		char[] b = XmrWalletKek.deriveMainFilePassword(pw(), salt(2));
		assertFalse("a different salt must change the derived password",
				Arrays.equals(a, b));
	}

	@Test
	public void differentPasswordDerivesADifferentFilePassword() {
		char[] a = XmrWalletKek.deriveMainFilePassword(pw(), salt(1));
		char[] b = XmrWalletKek.deriveMainFilePassword(
				"wrong horse".toCharArray(), salt(1));
		assertFalse("a different wallet password must change the derivation",
				Arrays.equals(a, b));
	}

	@Test
	public void derivedPasswordIsNotTheRawPassword() {
		char[] a = XmrWalletKek.deriveMainFilePassword(pw(), salt(1));
		assertFalse("the file password must not be the raw wallet password",
				Arrays.equals(a, pw()));
	}

	@Test
	public void newSaltIs32Bytes() {
		assertEquals(32, XmrWalletKek.newSalt().length);
	}
}
