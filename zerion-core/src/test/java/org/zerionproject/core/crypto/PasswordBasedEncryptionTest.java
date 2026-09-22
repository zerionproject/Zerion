package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.jmock.Expectations;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_CIPHERTEXT;
import static org.zerionproject.core.api.crypto.DecryptionResult.INVALID_PASSWORD;
import static org.zerionproject.core.api.crypto.DecryptionResult.KEY_STRENGTHENER_ERROR;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class PasswordBasedEncryptionTest extends BrambleMockTestCase {

	private final KeyStrengthener keyStrengthener =
			context.mock(KeyStrengthener.class);

	private final CryptoComponentImpl crypto =
			new CryptoComponentImpl(new TestSecureRandomProvider(),
					new ScryptKdf(new SystemClock()),
					new Argon2idKdf(new SystemClock()));

	@Test
	public void testEncryptionAndDecryption() throws Exception {
		byte[] input = getRandomBytes(1234);
		char[] password = "password".toCharArray();
		byte[] ciphertext = crypto.encryptWithPassword(input, password, null);
		byte[] output = crypto.decryptWithPassword(ciphertext, password, null);
		assertArrayEquals(input, output);
	}

	@Test
	public void testInvalidFormatVersionThrowsException() {
		byte[] input = getRandomBytes(1234);
		char[] password = "password".toCharArray();
		byte[] ciphertext = crypto.encryptWithPassword(input, password, null);

		ciphertext[0] ^= (byte) 0xFF;
		try {
			crypto.decryptWithPassword(ciphertext, password, null);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(INVALID_CIPHERTEXT, expected.getDecryptionResult());
		}
	}

	/**
	 * A2-CRY-03: the legacy scrypt formats carry the cost in the file; a
	 * cost outside the range ever written, or not a power of two, is a
	 * corrupt or tampered file and must be reported as such, not reach the
	 * library as an argument error or an allocation.
	 */
	@Test
	public void testLegacyScryptCostIsBounded() {
		assertFalse(CryptoComponentImpl.validScryptCost(3));
		assertFalse(CryptoComponentImpl.validScryptCost(2));
		assertFalse(CryptoComponentImpl.validScryptCost(1L << 30));
		assertFalse(CryptoComponentImpl.validScryptCost(384));
		assertTrue(CryptoComponentImpl.validScryptCost(256));
		assertTrue(CryptoComponentImpl.validScryptCost(1024 * 1024));
		byte[] input = getRandomBytes(64);
		char[] password = "password".toCharArray();
		byte[] ciphertext = crypto.encryptWithPassword(input, password, null);
		ciphertext[0] = 0;
		int costOffset = 1 + 32;
		ciphertext[costOffset] = 0;
		ciphertext[costOffset + 1] = 0;
		ciphertext[costOffset + 2] = 0;
		ciphertext[costOffset + 3] = 3;
		try {
			crypto.decryptWithPassword(ciphertext, password, null);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(INVALID_CIPHERTEXT, expected.getDecryptionResult());
		}
	}

	@Test
	public void testInvalidPasswordThrowsException() {
		byte[] input = getRandomBytes(1234);
		byte[] ciphertext = crypto.encryptWithPassword(input, "password".toCharArray(), null);

		try {
			crypto.decryptWithPassword(ciphertext, "wrong".toCharArray(), null);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(INVALID_PASSWORD, expected.getDecryptionResult());
		}
	}

	@Test
	public void testMissingKeyStrengthenerThrowsException() {
		SecretKey strengthened = getSecretKey();
		context.checking(new Expectations() {{
			oneOf(keyStrengthener).strengthenKey(with(any(SecretKey.class)));
			will(returnValue(strengthened));
		}});

		byte[] input = getRandomBytes(1234);
		char[] password = "password".toCharArray();
		byte[] ciphertext =
				crypto.encryptWithPassword(input, password, keyStrengthener);

		try {
			crypto.decryptWithPassword(ciphertext, password, null);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(KEY_STRENGTHENER_ERROR, expected.getDecryptionResult());
		}
	}

	@Test
	public void testKeyStrengthenerFailureThrowsException() {
		SecretKey strengthened = getSecretKey();
		context.checking(new Expectations() {{
			oneOf(keyStrengthener).strengthenKey(with(any(SecretKey.class)));
			will(returnValue(strengthened));
			oneOf(keyStrengthener).isInitialised();
			will(returnValue(false));
		}});

		byte[] input = getRandomBytes(1234);
		char[] password = "password".toCharArray();
		byte[] ciphertext =
				crypto.encryptWithPassword(input, password, keyStrengthener);

		try {
			crypto.decryptWithPassword(ciphertext, password, keyStrengthener);
			fail();
		} catch (DecryptionException expected) {
			assertEquals(KEY_STRENGTHENER_ERROR, expected.getDecryptionResult());
		}
	}
}
