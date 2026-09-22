package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.crypto.MnemonicCode;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * BTC-09: the mnemonic never becomes a string on the wallet path. The
 * character-based seed derivation matches BIP-39 and the library, the
 * account derived from it yields the library's addresses, and a closed
 * account holds no key.
 */
public class Bip39SeedTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String VECTOR_SEED =
			"5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1"
					+ "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4";

	@Test
	public void seedMatchesTheBip39Vector() throws Exception {
		byte[] seed = Bip39Seed.fromMnemonic(MNEMONIC.toCharArray());
		assertEquals(VECTOR_SEED, BtcKeys.toHex(seed));
		assertArrayEquals(MnemonicCode.toSeed(
				Arrays.asList(MNEMONIC.split(" ")), ""), seed);
	}

	@Test
	public void whitespaceIsNormalisedLikeTheWordList() throws Exception {
		byte[] a = Bip39Seed.fromMnemonic(MNEMONIC.toCharArray());
		byte[] b = Bip39Seed.fromMnemonic(("  " + MNEMONIC.replace(" ",
				"\n  ") + "\t").toCharArray());
		assertArrayEquals(a, b);
	}

	@Test
	public void nonAsciiMnemonicIsRefused() {
		try {
			Bip39Seed.fromMnemonic(("abandoné " + MNEMONIC).toCharArray());
			fail();
		} catch (IllegalArgumentException expected) {
		} catch (Exception e) {
			fail(e.toString());
		}
	}

	@Test
	public void checksumAndWordListAreVerifiedWithoutStrings() {
		Bip39Seed.check(MNEMONIC.toCharArray());
		Bip39Seed.check(("  " + MNEMONIC.replace(" ", "  ") + " ").toCharArray());
		try {
			Bip39Seed.check(MNEMONIC.replace("about", "abandon").toCharArray());
			fail("wrong checksum");
		} catch (IllegalArgumentException expected) {
		}
		try {
			Bip39Seed.check(MNEMONIC.replace("about", "zzzzz").toCharArray());
			fail("unknown word");
		} catch (IllegalArgumentException expected) {
		}
		try {
			Bip39Seed.check(MNEMONIC.substring(MNEMONIC.indexOf(' ') + 1)
					.toCharArray());
			fail("eleven words");
		} catch (IllegalArgumentException expected) {
		}
		String twentyFour = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo "
				+ "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo vote";
		Bip39Seed.check(twentyFour.toCharArray());
	}

	@Test
	public void accountDerivesTheLibraryAddresses() throws Exception {
		BtcKeys.Account acct = BtcKeys.Account.fromMnemonic(
				MNEMONIC.toCharArray(), 0);
		assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
				acct.address(0));
		assertEquals(acct.address(3), TestKeys.address(MNEMONIC, 0, 3));
		assertEquals(acct.changeScriptHash(2),
				TestKeys.changeScriptHash(MNEMONIC, 0, 2));
		assertTrue(acct.ownedAddresses(2, 1).contains(acct.changeAddress(0)));
		assertFalse(acct.isClosed());
		acct.close();
		assertTrue(acct.isClosed());
		try {
			acct.address(0);
			fail("a closed account must not derive");
		} catch (IllegalStateException expected) {
		}
	}
}
