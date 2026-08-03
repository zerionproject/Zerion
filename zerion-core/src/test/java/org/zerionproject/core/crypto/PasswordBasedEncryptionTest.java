package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.KeyStrengthener;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.jmock.Expectations;
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
