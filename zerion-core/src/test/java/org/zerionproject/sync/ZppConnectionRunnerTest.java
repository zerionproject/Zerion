package org.zerionproject.sync;

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
import org.zerionproject.message.ZmmConstants;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.zerionproject.transport.ZwfSession;
import org.zerionproject.transport.ZwfSessionFactory;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the ZPP connection runner end to end: one endpoint enqueues real
 * records while both run the constant-rate loop, and the peer receives exactly
 * those records (cover frames are dropped, never delivered as data), proving the
 * send scheduler, cover fill and receive dispatch work together over the real
 * stream.
 */
public class ZppConnectionRunnerTest {

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

	private static class CapturingRegistry implements ZppConnectionRegistry {
		final Map<Integer, ZppSendScheduler> schedulers =
				new ConcurrentHashMap<>();

		@Override
		public void onConnectionOpened(int contactId,
				ZppSendScheduler scheduler, int maxRecordBytes) {
			schedulers.put(contactId, scheduler);
		}

		@Override
		public void onConnectionClosed(int contactId,
				ZppSendScheduler scheduler) {
			schedulers.remove(contactId);
		}
	}

	private static class CollectingSink implements ZppRecordSink {
		final List<String> received =
				Collections.synchronizedList(new ArrayList<>());

		@Override
		public void deliver(int contactId, int type, byte[] payload) {
			received.add(contactId + "|" + type + "|"
					+ new String(payload, StandardCharsets.UTF_8));
		}

		@Override
		public void onDisconnected(int contactId) {
		}
	}

	@Test(timeout = 30_000)
	public void deliversRealRecordsAndDropsCover() throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);

		ZwfSession aliceSession = sessionFactory.deriveSession(rootKey, true);
		ZwfSession bobSession = sessionFactory.deriveSession(rootKey, false);

		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);

		ZwfDuplexConnection aliceConn = new ZwfDuplexConnection(1, aliceSession,
				new ZwfStreamCounter(new MemStore()), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), aIn, aOut);
		ZwfDuplexConnection bobConn = new ZwfDuplexConnection(2, bobSession,
				new ZwfStreamCounter(new MemStore()), crypto, ratchet,
				mode3FullRatchet, cipherFactory(), bIn, bOut);

		CapturingRegistry registry = new CapturingRegistry();
		CollectingSink sink = new CollectingSink();
		// Fast tick so the test does not wait real ZPP slots.
		ZppConnectionRunnerImpl runner =
				new ZppConnectionRunnerImpl(sink, registry, 5);

		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		Thread aliceThread = new Thread(() -> {
			try {
				runner.run(1, aliceConn);
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread bobThread = new Thread(() -> {
			try {
				runner.run(2, bobConn);
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		aliceThread.start();
		bobThread.start();

		// Wait for alice's connection to register its scheduler, then enqueue
		// three real records for bob to receive.
		ZppSendScheduler aliceScheduler = awaitScheduler(registry, 1);
		int n = 3;
		for (int i = 0; i < n; i++) {
			aliceScheduler.enqueue(ZmmConstants.TYPE_TEXT,
					("hello-" + i).getBytes(StandardCharsets.UTF_8));
		}

		// Bob receives them (delivered under bob's contact id 2), while idle
		// slots keep emitting cover in both directions.
		awaitReceived(sink, 2, n);
		Thread.sleep(60);

		// Stop both runners by closing the pipes.
		aOut.close();
		bOut.close();
		aIn.close();
		bIn.close();
		aliceThread.join(10_000);
		bobThread.join(10_000);

		synchronized (errors) {
			if (!errors.isEmpty()) {
				throw new AssertionError("runner threw: " + errors.get(0),
						errors.get(0));
			}
		}

		List<String> bobGot = new ArrayList<>();
		synchronized (sink.received) {
			for (String s : sink.received) {
				if (s.startsWith("2|")) bobGot.add(s);
			}
		}
		assertEquals(n, bobGot.size());
		for (int i = 0; i < n; i++) {
			assertEquals("2|" + ZmmConstants.TYPE_TEXT + "|hello-" + i,
					bobGot.get(i));
		}
		// Idle slots emitted cover, so both a real and a cover frame flowed.
		assertEquals(n, aliceScheduler.getRealFrameCount());
		assertTrue("idle slots should emit cover frames",
				aliceScheduler.getCoverFrameCount() > 0);
	}

	private static ZppSendScheduler awaitScheduler(CapturingRegistry registry,
			int contactId) throws InterruptedException {
		for (int i = 0; i < 2000; i++) {
			ZppSendScheduler s = registry.schedulers.get(contactId);
			if (s != null) return s;
			Thread.sleep(2);
		}
		throw new AssertionError("scheduler not registered for " + contactId);
	}

	private static void awaitReceived(CollectingSink sink, int contactId,
			int n) throws InterruptedException {
		String prefix = contactId + "|";
		for (int i = 0; i < 2000; i++) {
			long count;
			synchronized (sink.received) {
				count = sink.received.stream().filter(s -> s.startsWith(prefix))
						.count();
			}
			if (count >= n) return;
			Thread.sleep(2);
		}
		throw new AssertionError("did not receive " + n + " records");
	}
}
