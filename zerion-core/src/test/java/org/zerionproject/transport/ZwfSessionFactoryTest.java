package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;

public class ZwfSessionFactoryTest {

	private CryptoComponent crypto;
	private ZwfSessionFactory factory;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> providerCtor = providerImpl.getDeclaredConstructor(
				java.security.SecureRandom.class);
		providerCtor.setAccessible(true);
		MlKemProvider mlKemProvider = (MlKemProvider) providerCtor
				.newInstance(crypto.getSecureRandom());
		Class<?> ratchetImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> ratchetCtor = ratchetImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		ratchetCtor.setAccessible(true);
		Mode3FullRatchet mode3FullRatchet = (Mode3FullRatchet)
				ratchetCtor.newInstance(crypto, mlKemProvider);
		factory = new ZwfSessionFactory(crypto, mode3FullRatchet);
	}

	private SecretKey randomKey() {
		byte[] k = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(k);
		return new SecretKey(k);
	}

	@Test
	public void sendAndReceiveKeysMirrorBetweenEndpoints() {
		SecretKey rootKey = randomKey();
		ZwfSession alice = factory.deriveSession(rootKey, true);
		ZwfSession bob = factory.deriveSession(rootKey, false);

		assertArrayEquals(alice.getSendTagKey().getBytes(),
				bob.getRecvTagKey().getBytes());
		assertArrayEquals(bob.getSendTagKey().getBytes(),
				alice.getRecvTagKey().getBytes());
		assertArrayEquals(alice.getSendHeaderKey().getBytes(),
				bob.getRecvHeaderKey().getBytes());
		assertArrayEquals(bob.getSendHeaderKey().getBytes(),
				alice.getRecvHeaderKey().getBytes());
		assertArrayEquals(alice.getSendState().getRootKey().getBytes(),
				bob.getRecvState().getRootKey().getBytes());
		assertArrayEquals(alice.getRecvState().getRootKey().getBytes(),
				bob.getSendState().getRootKey().getBytes());
	}

	@Test
	public void directionsAreSeparated() {
		SecretKey rootKey = randomKey();
		ZwfSession s = factory.deriveSession(rootKey, true);
		assertFalse(java.util.Arrays.equals(s.getSendTagKey().getBytes(),
				s.getRecvTagKey().getBytes()));
		assertFalse(java.util.Arrays.equals(s.getSendHeaderKey().getBytes(),
				s.getRecvHeaderKey().getBytes()));
		assertFalse(java.util.Arrays.equals(
				s.getSendState().getRootKey().getBytes(),
				s.getRecvState().getRootKey().getBytes()));
	}
}
