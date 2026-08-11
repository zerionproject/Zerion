package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
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
import static org.junit.Assert.assertNull;

/**
 * Proves that resuming an ongoing contact carries the post-quantum ratchet state
 * across a reconnection: after a first connection engages per-message ML-KEM, a
 * second connection built with {@link ZwfSessionFactory#resumeSession} starts
 * already knowing the peer's key (a fresh session would not), keeps a distinct
 * stream space via the persistent counter, and continues to carry messages.
 */
public class ZwfSessionResumeTest {

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

	/**
	 * Runs one bidirectional exchange of {@code n} messages each way between two
	 * connections over crossed pipes and asserts every message arrives in order.
	 */
	private void exchange(ZwfDuplexConnection alice, ZwfDuplexConnection bob,
			int n, String tag) throws Exception {
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		List<String> bobReceived = Collections.synchronizedList(
				new ArrayList<>());
		List<String> aliceReceived = Collections.synchronizedList(
				new ArrayList<>());

		Thread aliceSender = new Thread(() -> {
			try {
				for (int i = 0; i < n; i++) {
					alice.sendMessage((tag + "-a2b-" + i).getBytes(
							StandardCharsets.UTF_8));
				}
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread bobSender = new Thread(() -> {
			try {
				for (int i = 0; i < n; i++) {
					bob.sendMessage((tag + "-b2a-" + i).getBytes(
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
				throw new AssertionError(tag + " connection threw: "
						+ errors.get(0), errors.get(0));
			}
		}
		assertEquals(n, bobReceived.size());
		assertEquals(n, aliceReceived.size());
		for (int i = 0; i < n; i++) {
			assertEquals(tag + "-a2b-" + i, bobReceived.get(i));
			assertEquals(tag + "-b2a-" + i, aliceReceived.get(i));
		}
	}

	@Test(timeout = 30_000)
	public void resumePreservesPostQuantumStateAcrossReconnect()
			throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);

		// Per-endpoint counters that persist across the reconnection, so the
		// resumed connection gets a fresh stream id (never a reused one).
		StreamCounterStore aliceStore = new MemStore();
		StreamCounterStore bobStore = new MemStore();

		// First connection: fresh sessions, exchange until per-message PQ engages.
		ZwfSession aliceS1 = sessionFactory.deriveSession(rootKey, true);
		ZwfSession bobS1 = sessionFactory.deriveSession(rootKey, false);

		PipedOutputStream aOut1 = new PipedOutputStream();
		PipedInputStream bIn1 = new PipedInputStream(aOut1, 1 << 20);
		PipedOutputStream bOut1 = new PipedOutputStream();
		PipedInputStream aIn1 = new PipedInputStream(bOut1, 1 << 20);

		ZwfDuplexConnection alice1 = new ZwfDuplexConnection(1, aliceS1,
				new ZwfStreamCounter(aliceStore), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), aIn1, aOut1);
		ZwfDuplexConnection bob1 = new ZwfDuplexConnection(2, bobS1,
				new ZwfStreamCounter(bobStore), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), bIn1, bOut1);

		exchange(alice1, bob1, 8, "s1");

		Mode3FullState aliceState = alice1.currentMode3FullState();
		Mode3FullState bobState = bob1.currentMode3FullState();
		assertNotNull("alice learned bob's ML-KEM key",
				aliceState.getTheirActivePqPk());
		assertNotNull("bob learned alice's ML-KEM key",
				bobState.getTheirActivePqPk());

		// Second connection: resume from the persisted Mode3Full state. Unlike a
		// fresh session, the peer's ML-KEM key is present before any frame flows,
		// so the post-quantum path never falls back to the classical sentinel.
		ZwfSession aliceS2 = sessionFactory.resumeSession(rootKey, true,
				aliceState);
		ZwfSession bobS2 = sessionFactory.resumeSession(rootKey, false, bobState);

		// Contrast: a fresh session would have no peer key yet.
		assertNull("fresh session has no peer key before messaging",
				sessionFactory.deriveSession(rootKey, true).getSendState()
						.getMode3FullState().getTheirActivePqPk());
		assertNotNull("resumed session already knows the peer key",
				aliceS2.getSendState().getMode3FullState().getTheirActivePqPk());
		assertNotNull("resumed session already knows the peer key",
				bobS2.getSendState().getMode3FullState().getTheirActivePqPk());

		PipedOutputStream aOut2 = new PipedOutputStream();
		PipedInputStream bIn2 = new PipedInputStream(aOut2, 1 << 20);
		PipedOutputStream bOut2 = new PipedOutputStream();
		PipedInputStream aIn2 = new PipedInputStream(bOut2, 1 << 20);

		ZwfDuplexConnection alice2 = new ZwfDuplexConnection(1, aliceS2,
				new ZwfStreamCounter(aliceStore), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), aIn2, aOut2);
		ZwfDuplexConnection bob2 = new ZwfDuplexConnection(2, bobS2,
				new ZwfStreamCounter(bobStore), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), bIn2, bOut2);

		exchange(alice2, bob2, 6, "s2");

		assertNotNull("PQ still engaged after resume",
				alice2.currentMode3FullState().getTheirActivePqPk());
		assertNotNull("PQ still engaged after resume",
				bob2.currentMode3FullState().getTheirActivePqPk());
	}
}
