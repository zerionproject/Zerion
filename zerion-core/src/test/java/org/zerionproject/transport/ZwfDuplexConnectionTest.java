package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Before;
import org.junit.Test;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * End-to-end plumbing test: two duplex connections over crossed pipes exchange
 * messages in both directions, exercising the whole native stack below the
 * message model - stream-id allocation, tag recognition, session-derived keys,
 * fixed-frame Mode3Full stream and chain.
 */
public class ZwfDuplexConnectionTest {

	private CryptoComponent crypto;
	private PcsRatchet ratchet;
	private Mode3FullRatchet mode3FullRatchet;
	private ZwfSessionFactory sessionFactory;

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
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return System.currentTimeMillis();
			}

			@Override
			public void sleep(long ms) throws InterruptedException {
				Thread.sleep(ms);
			}
		};
		ratchet = new PcsRatchetImpl(crypto, clock);
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
		mode3FullRatchet = (Mode3FullRatchet) ratchetCtor.newInstance(crypto,
				mlKemProvider);
		sessionFactory = new ZwfSessionFactory(crypto, mode3FullRatchet);
	}

	private static class MemStore implements StreamCounterStore {
		private final Map<Long, Long> m = new HashMap<>();

		@Override
		public synchronized long loadHighWater(int c, int d) {
			Long v = m.get((((long) c) << 1) | (d & 1L));
			return v == null ? 0 : v;
		}

		@Override
		public synchronized void storeHighWater(int c, int d, long hw) {
			m.put((((long) c) << 1) | (d & 1L), hw);
		}
	}

	private Supplier<AuthenticatedCipher> cipherFactory() {
		return XSalsa20Poly1305AuthenticatedCipher::new;
	}

	@Test(timeout = 30_000)
	public void messagesFlowBothDirections() throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);

		ZwfSession aliceSession = sessionFactory.deriveSession(rootKey, true);
		ZwfSession bobSession = sessionFactory.deriveSession(rootKey, false);

		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);

		ZwfDuplexConnection alice = new ZwfDuplexConnection(1, aliceSession,
				new ZwfStreamCounter(new MemStore()), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), aIn, aOut);
		ZwfDuplexConnection bob = new ZwfDuplexConnection(2, bobSession,
				new ZwfStreamCounter(new MemStore()), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), bIn, bOut);

		int n = 8;
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		List<String> bobReceived = Collections.synchronizedList(
				new ArrayList<>());
		List<String> aliceReceived = Collections.synchronizedList(
				new ArrayList<>());

		Thread aliceSender = new Thread(() -> {
			try {
				for (int i = 0; i < n; i++) {
					alice.sendMessage(("a2b-" + i).getBytes(
							StandardCharsets.UTF_8));
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread bobSender = new Thread(() -> {
			try {
				for (int i = 0; i < n; i++) {
					bob.sendMessage(("b2a-" + i).getBytes(
							StandardCharsets.UTF_8));
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread bobReceiver = new Thread(() -> {
			try {
				for (int i = 0; i < n; i++) {
					byte[] m = bob.receiveMessage();
					bobReceived.add(new String(m, StandardCharsets.UTF_8));
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread aliceReceiver = new Thread(() -> {
			try {
				for (int i = 0; i < n; i++) {
					byte[] m = alice.receiveMessage();
					aliceReceived.add(new String(m, StandardCharsets.UTF_8));
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});

		aliceSender.start();
		bobSender.start();
		bobReceiver.start();
		aliceReceiver.start();
		aliceSender.join(20_000);
		bobSender.join(20_000);
		bobReceiver.join(20_000);
		aliceReceiver.join(20_000);

		synchronized (errors) {
			if (!errors.isEmpty()) {
				throw new AssertionError("connection threw: " + errors.get(0),
						errors.get(0));
			}
		}
		assertEquals(n, bobReceived.size());
		assertEquals(n, aliceReceived.size());
		for (int i = 0; i < n; i++) {
			assertEquals("a2b-" + i, bobReceived.get(i));
			assertEquals("b2a-" + i, aliceReceived.get(i));
		}

		// Per-message PQ engaged: each side learned the peer's ML-KEM key
		// in-band, so subsequent sends encapsulate to it (not the sentinel).
		assertNotNull("alice should have learned bob's ML-KEM key",
				alice.currentMode3FullState().getTheirActivePqPk());
		assertNotNull("bob should have learned alice's ML-KEM key",
				bob.currentMode3FullState().getTheirActivePqPk());
	}
}
