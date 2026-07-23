package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet.PqRecvResult;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet.PqSendResult;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class Mode3FullPerMessagePqTest {

	private CryptoComponent crypto;
	private MlKemProvider mlKemProvider;
	private Mode3FullRatchetImpl ratchet;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
		constructor.setAccessible(true);
		crypto = (CryptoComponent) constructor.newInstance(
				new TestSecureRandomProvider(), null);
		mlKemProvider = new MlKemProviderImpl(crypto.getSecureRandom());
		ratchet = new Mode3FullRatchetImpl(crypto, mlKemProvider);
	}

	private static final class Peer {
		Mode3FullState state;
	}

	private byte[] send(Peer from, Peer to) {
		PqSendResult r = ratchet.pqEncapsulateSend(from.state);
		from.state = r.getNewState();
		assertNotNull("every send must carry fresh ML-KEM material once the "
				+ "peer PK is known", r.getSharedSecret());

		SecretKey classical = randomKey();
		SecretKey hybrid = ratchet.deriveHybridMessageKey(classical,
				r.getSharedSecret());
		assertFalse("body key must differ from the classical key (PQ mixed in)",
				Arrays.equals(classical.getBytes(), hybrid.getBytes()));

		byte[] sentSs = r.getSharedSecret().clone();
		try {
			PqRecvResult rr = ratchet.pqDecapsulateRecv(to.state,
					r.getKpIdUsed(), r.getCiphertext(), r.getPkAdvertise());
			to.state = rr.getNewState();
			assertNotNull(rr.getSharedSecret());
			assertArrayEquals("receiver must derive the same ML-KEM secret",
					sentSs, rr.getSharedSecret());
		} catch (Exception e) {
			throw new AssertionError("decapsulation failed", e);
		}
		return sentSs;
	}

	private SecretKey randomKey() {
		byte[] k = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(k);
		return new SecretKey(k);
	}

	private static String toHex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format("%02x", x));
		return sb.toString();
	}

	@Test
	public void everyMessageAfterPkExchangeIsHybridPq() throws Exception {
		Peer alice = new Peer();
		Peer bob = new Peer();
		alice.state = ratchet.createInitialState();
		bob.state = ratchet.createInitialState();

		PqSendResult aOpen = ratchet.pqEncapsulateSend(alice.state);
		alice.state = aOpen.getNewState();
		assertNull(aOpen.getSharedSecret());
		bob.state = ratchet.pqDecapsulateRecv(bob.state, aOpen.getKpIdUsed(),
				aOpen.getCiphertext(), aOpen.getPkAdvertise()).getNewState();

		int messages = 200;
		Set<String> secrets = new HashSet<>();
		for (int i = 0; i < messages; i++) {
			byte[] ss = (i % 2 == 0) ? send(bob, alice) : send(alice, bob);
			assertTrue("every ML-KEM secret must be globally unique",
					secrets.add(toHex(ss)));
		}
		assertEquals(messages, secrets.size());
	}
}
