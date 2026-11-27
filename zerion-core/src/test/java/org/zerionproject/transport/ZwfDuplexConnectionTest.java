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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
		ratchet = new PcsRatchetImpl(crypto);
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

		assertNotNull("alice should have learned bob's ML-KEM key",
				alice.currentMode3FullState().getTheirActivePqPk());
		assertNotNull("bob should have learned alice's ML-KEM key",
				bob.currentMode3FullState().getTheirActivePqPk());
	}

	@Test(timeout = 120_000)
	public void anUnknownTagCostsOneBoundedSearchPerContactAndInterval()
			throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);
		java.util.concurrent.atomic.AtomicLong now =
				new java.util.concurrent.atomic.AtomicLong(1_000_000L);
		ZwfStreamCounter counter =
				new ZwfStreamCounter(new MemStore(), now::get);
		int epochs = sessionFactory.deriveSession(rootKey, false)
				.getRecvKeys().size();

		long first = costOfAnUnknownTag(rootKey, counter);
		assertTrue("the first unknown tag is searched for", first > 0);
		assertTrue("the search is bounded",
				first <= ZwfDuplexConnection.MAX_RECV_STREAM_GAP * epochs);

		assertEquals("a second unknown tag within the interval costs nothing",
				0, costOfAnUnknownTag(rootKey, counter));
		assertEquals(0, costOfAnUnknownTag(rootKey, counter));

		now.addAndGet(ZwfStreamCounter.BEYOND_WINDOW_SEARCH_INTERVAL_MS);
		assertTrue("the search runs again after the interval",
				costOfAnUnknownTag(rootKey, counter) > 0);

		assertTrue(counter.startBeyondWindowSearch(9));
		assertFalse(counter.startBeyondWindowSearch(9));
		counter.beyondWindowSearchMatched(9, 1L);
		assertTrue("a search that found its tag holds nothing back",
				counter.startBeyondWindowSearch(9));
	}

	@Test(timeout = 120_000)
	public void aStreamAlreadyFoundBySearchIsTakenWithoutSearchingAgain()
			throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);
		java.util.concurrent.atomic.AtomicLong now =
				new java.util.concurrent.atomic.AtomicLong(1_000_000L);
		ZwfStreamCounter bobCounter =
				new ZwfStreamCounter(new MemStore(), now::get);
		assertTrue(costOfAnUnknownTag(rootKey, bobCounter) > 0);
		long ahead = 5000;
		bobCounter.beyondWindowSearchMatched(2, ahead + 1);

		java.util.concurrent.atomic.AtomicLong macs =
				new java.util.concurrent.atomic.AtomicLong();
		CryptoComponent counting = countingMacs(macs);
		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		MemStore aliceStore = new MemStore();
		aliceStore.storeHighWater(1,
				org.zerionproject.wire.ZwfConstants.DIRECTION_SEND, ahead);
		ZwfDuplexConnection alice = new ZwfDuplexConnection(1,
				sessionFactory.deriveSession(rootKey, true),
				new ZwfStreamCounter(aliceStore), crypto, ratchet,
				mode3FullRatchet, cipherFactory(),
				new java.io.ByteArrayInputStream(new byte[0]), aOut);
		ZwfDuplexConnection bob = new ZwfDuplexConnection(2,
				sessionFactory.deriveSession(rootKey, false), bobCounter,
				counting, ratchet, mode3FullRatchet, cipherFactory(), bIn,
				new java.io.ByteArrayOutputStream());
		alice.sendMessage("found".getBytes(StandardCharsets.UTF_8));
		long before = macs.get();
		assertEquals("found", new String(bob.receiveMessage(),
				StandardCharsets.UTF_8));
		assertTrue("the stream found by the inbound search was searched for"
				+ " again", macs.get() - before < ahead / 2);
	}

	private CryptoComponent countingMacs(
			java.util.concurrent.atomic.AtomicLong macs) {
		return (CryptoComponent) java.lang.reflect.Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					if (method.getName().equals("mac")) macs.incrementAndGet();
					try {
						return method.invoke(crypto, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	private long costOfAnUnknownTag(SecretKey rootKey, ZwfStreamCounter counter)
			throws Exception {
		java.util.concurrent.atomic.AtomicLong macs =
				new java.util.concurrent.atomic.AtomicLong();
		CryptoComponent counting = (CryptoComponent) java.lang.reflect.Proxy
				.newProxyInstance(CryptoComponent.class.getClassLoader(),
						new Class<?>[] {CryptoComponent.class},
						(proxy, method, args) -> {
							if (method.getName().equals("mac")) {
								macs.incrementAndGet();
							}
							try {
								return method.invoke(crypto, args);
							} catch (java.lang.reflect.InvocationTargetException e) {
								throw e.getCause();
							}
						});
		byte[] garbage = new byte[FRAME_BYTES_FOR_GARBAGE];
		crypto.getSecureRandom().nextBytes(garbage);
		ZwfDuplexConnection c = new ZwfDuplexConnection(2,
				sessionFactory.deriveSession(rootKey, false), counter, counting,
				ratchet, mode3FullRatchet, cipherFactory(),
				new java.io.ByteArrayInputStream(garbage),
				new java.io.ByteArrayOutputStream());
		long before = macs.get();
		try {
			c.receiveMessage();
			fail("an unknown tag must be refused");
		} catch (org.zerionproject.core.api.FormatException expected) {
		}
		return macs.get() - before;
	}

	private static final int FRAME_BYTES_FOR_GARBAGE = 8192;

	private ZwfDuplexConnection[] pairWithSenderAhead(long senderSendHighWater)
			throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);
		ZwfSession aliceSession = sessionFactory.deriveSession(rootKey, true);
		ZwfSession bobSession = sessionFactory.deriveSession(rootKey, false);
		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);
		MemStore aliceStore = new MemStore();
		aliceStore.storeHighWater(1, org.zerionproject.wire.ZwfConstants.DIRECTION_SEND,
				senderSendHighWater);
		ZwfDuplexConnection alice = new ZwfDuplexConnection(1, aliceSession,
				new ZwfStreamCounter(aliceStore), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), aIn, aOut);
		ZwfDuplexConnection bob = new ZwfDuplexConnection(2, bobSession,
				new ZwfStreamCounter(new MemStore()), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), bIn, bOut);
		return new ZwfDuplexConnection[] {alice, bob};
	}

	@Test(timeout = 60_000)
	public void noFrameIsWrittenAfterTheKeyMaterialIsDestroyed()
			throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);
		ZwfSession session = sessionFactory.deriveSession(rootKey, true);
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		ZwfDuplexConnection c = new ZwfDuplexConnection(1, session,
				new ZwfStreamCounter(new MemStore()), crypto, ratchet,
				mode3FullRatchet, cipherFactory(),
				new java.io.ByteArrayInputStream(new byte[0]), out);
		c.destroyKeyMaterial();
		assertTrue(c.isDestroyed());
		try {
			c.sendMessage("late".getBytes(StandardCharsets.UTF_8));
			fail("a frame was written after the keys were destroyed");
		} catch (java.io.IOException expected) {
		}
		assertEquals(0, out.size());
	}

	@Test(timeout = 60_000)
	public void recoversFromAPeerCounterFarBeyondTheReceiveWindow()
			throws Exception {
		ZwfDuplexConnection[] c = pairWithSenderAhead(5000);
		ZwfDuplexConnection alice = c[0], bob = c[1];
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		Thread sender = new Thread(() -> {
			try {
				alice.sendMessage("first".getBytes(StandardCharsets.UTF_8));
				alice.sendMessage("second".getBytes(StandardCharsets.UTF_8));
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		sender.start();
		assertEquals("first", new String(bob.receiveMessage(),
				StandardCharsets.UTF_8));
		assertEquals("second", new String(bob.receiveMessage(),
				StandardCharsets.UTF_8));
		sender.join(10_000);
		assertTrue(errors.isEmpty());
	}

	@Test(timeout = 60_000)
	public void refusesAPeerCounterBeyondTheRecoveryBound() throws Exception {
		ZwfDuplexConnection[] c = pairWithSenderAhead(
				ZwfDuplexConnection.MAX_RECV_STREAM_GAP + 10_000);
		ZwfDuplexConnection alice = c[0], bob = c[1];
		Thread sender = new Thread(() -> {
			try {
				alice.sendMessage("first".getBytes(StandardCharsets.UTF_8));
			} catch (Throwable ignored) {
			}
		});
		sender.start();
		try {
			bob.receiveMessage();
			fail("a stream beyond the recovery bound must be refused");
		} catch (org.zerionproject.core.api.FormatException expected) {
		}
		sender.join(10_000);
	}

	@Test(timeout = 60_000)
	public void aRefusedStreamDoesNotAdvanceTheReceiveWindow()
			throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);
		MemStore bobStore = new MemStore();
		ZwfDuplexConnection[] far = pair(rootKey,
				ZwfDuplexConnection.MAX_RECV_STREAM_GAP + 10_000, bobStore);
		Thread farSender = new Thread(() -> {
			try {
				far[0].sendMessage("refused".getBytes(StandardCharsets.UTF_8));
			} catch (Throwable ignored) {
			}
		});
		farSender.start();
		try {
			far[1].receiveMessage();
			fail("a stream beyond the recovery bound must be refused");
		} catch (org.zerionproject.core.api.FormatException expected) {
		}
		farSender.join(10_000);
		assertEquals(0, bobStore.loadHighWater(2,
				org.zerionproject.wire.ZwfConstants.DIRECTION_RECV));

		ZwfDuplexConnection[] near = pair(rootKey, 5, bobStore);
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		Thread nearSender = new Thread(() -> {
			try {
				near[0].sendMessage("accepted".getBytes(StandardCharsets.UTF_8));
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		nearSender.start();
		assertEquals("accepted", new String(near[1].receiveMessage(),
				StandardCharsets.UTF_8));
		nearSender.join(10_000);
		assertTrue(errors.isEmpty());
		assertTrue(bobStore.loadHighWater(2,
				org.zerionproject.wire.ZwfConstants.DIRECTION_RECV) >= 6);
	}

	private ZwfDuplexConnection[] pair(SecretKey rootKey,
			long senderSendHighWater, MemStore bobStore) throws Exception {
		ZwfSession aliceSession = sessionFactory.deriveSession(rootKey, true);
		ZwfSession bobSession = sessionFactory.deriveSession(rootKey, false);
		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);
		MemStore aliceStore = new MemStore();
		aliceStore.storeHighWater(1,
				org.zerionproject.wire.ZwfConstants.DIRECTION_SEND,
				senderSendHighWater);
		ZwfDuplexConnection alice = new ZwfDuplexConnection(1, aliceSession,
				new ZwfStreamCounter(aliceStore), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), aIn, aOut);
		ZwfDuplexConnection bob = new ZwfDuplexConnection(2, bobSession,
				new ZwfStreamCounter(bobStore), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), bIn, bOut);
		return new ZwfDuplexConnection[] {alice, bob};
	}
}
