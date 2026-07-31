package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.contact.HandshakeCrypto;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.crypto.ZwfTagRecogniser;
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
import java.util.Collection;
import java.util.Collections;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Priority;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

/**
 * Exercises the established-contact connection handler end to end: a dialled
 * (outgoing) endpoint and a tag-recognised (incoming) endpoint each resume their
 * stored session, hand the live connection to the runner, exchange messages both
 * ways, and persist the evolved Mode 3-Full state when the session ends.
 */
public class ZtpConnectionHandlerTest {

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

	/** A fake session provider backed by an in-memory map and a tag recogniser. */
	private static class FakeProvider implements ZtpSessionProvider {
		private final ZwfTagRecogniser recogniser;
		private final Map<Integer, StoredContactSession> stored;
		final List<Integer> saved = Collections.synchronizedList(
				new ArrayList<>());

		FakeProvider(ZwfTagRecogniser recogniser,
				Map<Integer, StoredContactSession> stored) {
			this.recogniser = recogniser;
			this.stored = stored;
		}

		@Override
		public int recogniseIncoming(byte[] tag) {
			ZwfTagRecogniser.Match m = recogniser.recognise(tag);
			return m == null ? -1 : m.contactId;
		}

		@Override
		public StoredContactSession getStoredSession(int contactId) {
			return stored.get(contactId);
		}

		@Override
		public void saveMode3FullState(int contactId, Mode3FullState state) {
			saved.add(contactId);
		}
	}

	/**
	 * A runner that sends and receives {@code n} messages on the connection,
	 * recording what it received keyed by contact id.
	 */
	private static class ExchangeRunner implements ZppConnectionRunner {
		private final int n;
		final Map<Integer, List<String>> received = new ConcurrentHashMap<>();
		final List<Throwable> errors = Collections.synchronizedList(
				new ArrayList<>());

		ExchangeRunner(int n) {
			this.n = n;
		}

		@Override
		public void run(int contactId, ZwfDuplexConnection connection) {
			List<String> got = Collections.synchronizedList(new ArrayList<>());
			Thread sender = new Thread(() -> {
				try {
					for (int i = 0; i < n; i++) {
						connection.sendMessage(("from-" + contactId + "-" + i)
								.getBytes(StandardCharsets.UTF_8));
					}
				} catch (Throwable t) {
					errors.add(t);
				}
			});
			sender.start();
			try {
				for (int i = 0; i < n; i++) {
					byte[] m = connection.receiveMessage();
					got.add(new String(m, StandardCharsets.UTF_8));
				}
				sender.join(20_000);
			} catch (Throwable t) {
				errors.add(t);
			}
			received.put(contactId, got);
		}
	}

	@Test(timeout = 30_000)
	public void handlesOutgoingAndIncomingByResumingStoredSessions()
			throws Exception {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		SecretKey rootKey = new SecretKey(rootBytes);

		// Bring up a first connection so both sides have learned the peer's
		// ML-KEM key; capture the state each side would persist.
		Mode3FullState[] states = firstExchangeStates(rootKey);
		Mode3FullState aliceState = states[0];
		Mode3FullState bobState = states[1];

		// Alice's view: contact 2 is bob. Bob's view: contact 1 is alice.
		Map<Integer, StoredContactSession> aliceStored = new HashMap<>();
		aliceStored.put(2, new StoredContactSession(rootKey, true, aliceState));
		Map<Integer, StoredContactSession> bobStored = new HashMap<>();
		bobStored.put(1, new StoredContactSession(rootKey, false, bobState));

		// Bob recognises alice's outgoing tag (bob's recv tag key = alice's send
		// tag key; tag keys derive only from the root key and role).
		ZwfTagRecogniser bobRecogniser =
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
		SecretKey bobRecvTagKey = sessionFactory
				.resumeSession(rootKey, false, bobState).getRecvTagKey();
		bobRecogniser.register(1, bobRecvTagKey, 0);

		FakeProvider aliceProvider = new FakeProvider(
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE), aliceStored);
		FakeProvider bobProvider = new FakeProvider(bobRecogniser, bobStored);

		ExchangeRunner runner = new ExchangeRunner(6);

		// Separate establishers/counters model the two independent devices.
		ZtpConnectionEstablisher aliceEstablisher = new ZtpConnectionEstablisher(
				crypto, (HandshakeCrypto) null, ratchet, mode3FullRatchet,
				sessionFactory, new ZwfStreamCounter(new MemStore()),
				cipherFactory());
		ZtpConnectionEstablisher bobEstablisher = new ZtpConnectionEstablisher(
				crypto, (HandshakeCrypto) null, ratchet, mode3FullRatchet,
				sessionFactory, new ZwfStreamCounter(new MemStore()),
				cipherFactory());

		ConnectionRegistry reg = noOpConnectionRegistry();
		ZtpConnectionHandlerImpl aliceHandler = new ZtpConnectionHandlerImpl(
				aliceEstablisher, aliceProvider, runner, reg);
		ZtpConnectionHandlerImpl bobHandler = new ZtpConnectionHandlerImpl(
				bobEstablisher, bobProvider, runner, reg);

		PipedOutputStream aOut = new PipedOutputStream();
		PipedInputStream bIn = new PipedInputStream(aOut, 1 << 20);
		PipedOutputStream bOut = new PipedOutputStream();
		PipedInputStream aIn = new PipedInputStream(bOut, 1 << 20);

		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		Thread aliceThread = new Thread(() -> {
			try {
				aliceHandler.handleOutgoing(
						org.zerionproject.core.api.plugin.TorConstants.ID,
						2, aIn, aOut);
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread bobThread = new Thread(() -> {
			try {
				bobHandler.handleIncoming(
						org.zerionproject.core.api.plugin.TorConstants.ID,
						bIn, bOut);
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		aliceThread.start();
		bobThread.start();
		aliceThread.join(25_000);
		bobThread.join(25_000);

		synchronized (errors) {
			if (!errors.isEmpty()) {
				throw new AssertionError("handler threw: " + errors.get(0),
						errors.get(0));
			}
		}
		synchronized (runner.errors) {
			if (!runner.errors.isEmpty()) {
				throw new AssertionError("runner threw: " + runner.errors.get(0),
						runner.errors.get(0));
			}
		}

		// Alice's runner ran for contact 2, bob's for contact 1; each exchanged 6.
		assertNotNull(runner.received.get(2));
		assertNotNull(runner.received.get(1));
		assertEquals(6, runner.received.get(2).size());
		assertEquals(6, runner.received.get(1).size());
		// Both sides persisted their evolved state when the session ended.
		assertTrue(aliceProvider.saved.contains(2));
		assertTrue(bobProvider.saved.contains(1));
	}

	/**
	 * Runs a throwaway first connection between two fresh sessions so both sides
	 * learn the peer's ML-KEM key, and returns {alice, bob} Mode3Full states.
	 */
	private Mode3FullState[] firstExchangeStates(SecretKey rootKey)
			throws Exception {
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

		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		int n = 8;
		Thread aSend = new Thread(() -> send(alice, n, "a", errors));
		Thread bSend = new Thread(() -> send(bob, n, "b", errors));
		Thread aRecv = new Thread(() -> recv(alice, n, errors));
		Thread bRecv = new Thread(() -> recv(bob, n, errors));
		aSend.start();
		bSend.start();
		aRecv.start();
		bRecv.start();
		aSend.join(20_000);
		bSend.join(20_000);
		aRecv.join(20_000);
		bRecv.join(20_000);
		synchronized (errors) {
			if (!errors.isEmpty()) {
				throw new AssertionError("warm-up threw: " + errors.get(0),
						errors.get(0));
			}
		}
		return new Mode3FullState[] {alice.currentMode3FullState(),
				bob.currentMode3FullState()};
	}

	private static void send(ZwfDuplexConnection c, int n, String tag,
			List<Throwable> errors) {
		try {
			for (int i = 0; i < n; i++) {
				c.sendMessage((tag + i).getBytes(StandardCharsets.UTF_8));
			}
		} catch (Throwable t) {
			errors.add(t);
		}
	}

	private static void recv(ZwfDuplexConnection c, int n,
			List<Throwable> errors) {
		try {
			for (int i = 0; i < n; i++) c.receiveMessage();
		} catch (Throwable t) {
			errors.add(t);
		}
	}

	private static ConnectionRegistry noOpConnectionRegistry() {
		return new ConnectionRegistry() {
			@Override
			public void registerIncomingConnection(ContactId c, TransportId t,
					InterruptibleConnection conn) {
			}

			@Override
			public void registerOutgoingConnection(ContactId c, TransportId t,
					InterruptibleConnection conn, Priority priority) {
			}

			@Override
			public void unregisterConnection(ContactId c, TransportId t,
					InterruptibleConnection conn, boolean incoming,
					boolean exception) {
			}

			@Override
			public void setPriority(ContactId c, TransportId t,
					InterruptibleConnection conn, Priority priority) {
			}

			@Override
			public Collection<ContactId> getConnectedContacts(TransportId t) {
				return Collections.emptyList();
			}

			@Override
			public Collection<ContactId> getConnectedOrBetterContacts(
					TransportId t) {
				return Collections.emptyList();
			}

			@Override
			public boolean isConnected(ContactId c, TransportId t) {
				return false;
			}

			@Override
			public boolean isConnected(ContactId c) {
				return false;
			}

			@Override
			public boolean registerConnection(PendingContactId p) {
				return true;
			}

			@Override
			public void unregisterConnection(PendingContactId p,
					boolean success) {
			}
		};
	}
}
