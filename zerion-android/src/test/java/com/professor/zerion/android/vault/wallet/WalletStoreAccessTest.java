package com.professor.zerion.android.vault.wallet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Fail-closed decision for reading a wallet seed. Covers existing/legacy
 * password-protected wallets: an empty or absent password can never reach the
 * no-password decryption path, and a supplied password always routes through
 * the password-verified AEAD path (which itself fails closed on a wrong one).
 */
public class WalletStoreAccessTest {

	@Test
	public void protectedWalletWithEmptyPasswordIsRejected() {
		assertEquals(WalletStore.Access.REJECT,
				WalletStore.accessFor(true, new char[0]));
	}

	@Test
	public void protectedWalletWithNullPasswordIsRejected() {
		assertEquals(WalletStore.Access.REJECT,
				WalletStore.accessFor(true, null));
	}

	@Test
	public void protectedWalletWithArbitraryPasswordUsesVerifiedPath() {
		assertEquals("wrong/arbitrary password still goes through the AEAD path,"
				+ " which fails closed", WalletStore.Access.WITH_PASSWORD,
				WalletStore.accessFor(true, "an-arbitrary-guess".toCharArray()));
	}

	@Test
	public void protectedWalletWithCorrectShapedPasswordUsesVerifiedPath() {
		assertEquals(WalletStore.Access.WITH_PASSWORD,
				WalletStore.accessFor(true, "correct-horse".toCharArray()));
	}

	@Test
	public void unprotectedWalletUsesNoPasswordPath() {
		assertEquals(WalletStore.Access.NO_PASSWORD,
				WalletStore.accessFor(false, null));
	}

	@Test
	public void protectedWalletNeverFallsThroughToNoPasswordPath() {
		for (char[] pw : new char[][] {null, new char[0], " ".toCharArray(),
				"x".toCharArray()}) {
			WalletStore.Access a = WalletStore.accessFor(true, pw);
			assertEquals("protected wallet must never use NO_PASSWORD",
					a == WalletStore.Access.REJECT
							|| a == WalletStore.Access.WITH_PASSWORD, true);
		}
	}
}
