package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet.KdfCkResult;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class PcsRatchetImplTest {

	private CryptoComponent crypto;
	private PcsRatchetImpl ratchet;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName("org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		constructor.setAccessible(true);
		crypto = (CryptoComponent) constructor.newInstance(
				new TestSecureRandomProvider(), null);
		ratchet = new PcsRatchetImpl(crypto);
	}

	private SecretKey getSecretKey() {
		byte[] keyBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(keyBytes);
		return new SecretKey(keyBytes);
	}

	@Test
	public void testKdfCkProducesUniqueKeys() {
		SecretKey chainKey = getSecretKey();

		KdfCkResult result = ratchet.kdfCk(chainKey);

		assertNotNull(result.getNewChainKey());
		assertNotNull(result.getMessageKey());

		assertFalse(Arrays.equals(
				result.getNewChainKey().getBytes(),
				result.getMessageKey().getBytes()));

		assertFalse(Arrays.equals(
				chainKey.getBytes(),
				result.getNewChainKey().getBytes()));
	}

	@Test
	public void testKdfCkIsDeterministic() {
		SecretKey chainKey = getSecretKey();

		KdfCkResult result1 = ratchet.kdfCk(chainKey);
		KdfCkResult result2 = ratchet.kdfCk(chainKey);

		assertArrayEquals(
				result1.getNewChainKey().getBytes(),
				result2.getNewChainKey().getBytes());
		assertArrayEquals(
				result1.getMessageKey().getBytes(),
				result2.getMessageKey().getBytes());
	}

	@Test
	public void streamChainDependsOnStreamNumberAndSalt() {
		SecretKey root = getSecretKey();
		byte[] salt = new byte[24];
		byte[] otherSalt = new byte[24];
		otherSalt[0] = 1;
		byte[] a = ratchet.deriveStreamInitialChainKey(root, 7, salt)
				.getBytes();
		assertArrayEquals(a, ratchet.deriveStreamInitialChainKey(root, 7,
				salt).getBytes());
		assertFalse(Arrays.equals(a, ratchet.deriveStreamInitialChainKey(root,
				8, salt).getBytes()));
		assertFalse(Arrays.equals(a, ratchet.deriveStreamInitialChainKey(root,
				7, otherSalt).getBytes()));
	}
}
