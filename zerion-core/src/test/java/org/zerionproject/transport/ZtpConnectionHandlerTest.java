package org.zerionproject.transport;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

/**
 * Exercises the established-contact connection handler end to end: a dialled
 * (outgoing) endpoint and a tag-recognised (incoming) endpoint each resume
 * their stored session, hand the live connection to the runner, exchange
 * messages both ways, report the closed session to the provider and zeroize
 * the session's ML-KEM key material. The socket a pairing ran on takes the
 * same path with the contact id known on both sides.
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
		final List<Integer> closed = Collections.synchronizedList(
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
		public void sessionClosed(int contactId) {
			closed.add(contactId);
		}
	}

	/**
	 * A runner that sends and receives {@code n} messages on the connection,
	 * recording what it received and the connection it ran, keyed by contact
	 * id.
	 */
	private static class ExchangeRunner implements ZppConnectionRunner {
		private final int n;
		final Map<Integer, List<String>> received = new ConcurrentHashMap<>();
		final Map<Integer, ZwfDuplexConnection> connections =
				new ConcurrentHashMap<>();
		final List<Throwable> errors = Collections.synchronizedList(
				new ArrayList<>());

		ExchangeRunner(int n) {
			this.n = n;
		}

		@Override
		public void run(int contactId, ZwfDuplexConnection connection) {
			connections.put(contactId, connection);
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

	private interface Endpoint {
		void run() throws IOException;
	}

	private static final class Pipes {
		final PipedOutputStream aOut = new PipedOutputStream();
		final PipedInputStream bIn;
		final PipedOutputStream bOut = new PipedOutputStream();
		final PipedInputStream aIn;

		Pipes() throws IOException {
			bIn = new PipedInputStream(aOut, 1 << 20);
			aIn = new PipedInputStream(bOut, 1 << 20);
		}
	}

	@Test(timeout = 30_000)
	public void handlesOutgoingAndIncomingByResumingStoredSessions()
			throws Exception {
		SecretKey rootKey = randomRootKey();
		Map<Integer, StoredContactSession> aliceStored = new HashMap<>();
		aliceStored.put(2, new StoredContactSession(rootKey, true));
		Map<Integer, StoredContactSession> bobStored = new HashMap<>();
		bobStored.put(1, new StoredContactSession(rootKey, false));

		ZwfTagRecogniser bobRecogniser =
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
		bobRecogniser.register(1,
				sessionFactory.deriveRecvTagKey(rootKey, false), 0);
		FakeProvider aliceProvider = new FakeProvider(
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE), aliceStored);
		FakeProvider bobProvider = new FakeProvider(bobRecogniser, bobStored);
		ExchangeRunner runner = new ExchangeRunner(6);
		ZtpConnectionHandlerImpl alice = handler(aliceProvider, runner);
		ZtpConnectionHandlerImpl bob = handler(bobProvider, runner);
		Pipes p = new Pipes();

		runBoth(() -> alice.handleOutgoing(TorConstants.ID, 2, p.aIn, p.aOut),
				() -> bob.handleIncoming(TorConstants.ID, p.bIn, p.bOut),
				runner);

		assertExchanged(runner, aliceProvider, bobProvider);
	}

	@Test(timeout = 30_000)
	public void pairedSocketResumesWithoutTagLookupAndZeroizesKeysOnClose()
			throws Exception {
		SecretKey rootKey = randomRootKey();
		Map<Integer, StoredContactSession> aliceStored = new HashMap<>();
		aliceStored.put(2, new StoredContactSession(rootKey, true));
		Map<Integer, StoredContactSession> bobStored = new HashMap<>();
		bobStored.put(1, new StoredContactSession(rootKey, false));

		FakeProvider aliceProvider = new FakeProvider(
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE), aliceStored);
		FakeProvider bobProvider = new FakeProvider(
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE), bobStored);
		ExchangeRunner runner = new ExchangeRunner(6);
		ZtpConnectionHandlerImpl alice = handler(aliceProvider, runner);
		ZtpConnectionHandlerImpl bob = handler(bobProvider, runner);
		Pipes p = new Pipes();

		runBoth(() -> alice.handlePaired(TorConstants.ID, 2, false, p.aIn,
						p.aOut),
				() -> bob.handlePaired(TorConstants.ID, 1, true, p.bIn, p.bOut),
				runner);

		assertExchanged(runner, aliceProvider, bobProvider);
		assertZeroized(runner.connections.get(2));
		assertZeroized(runner.connections.get(1));
	}

	@Test(timeout = 30_000)
	public void incomingConnectionWithUnknownTagIsRefusedBeforeAnySession()
			throws Exception {
		FakeProvider provider = new FakeProvider(
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE),
				new HashMap<>());
		ExchangeRunner runner = new ExchangeRunner(1);
		ZtpConnectionHandlerImpl handler = handler(provider, runner);
		byte[] tag = new byte[TAG_LENGTH];
		crypto.getSecureRandom().nextBytes(tag);
		try {
			handler.handleIncoming(TorConstants.ID,
					new ByteArrayInputStream(tag), new ByteArrayOutputStream());
			fail();
		} catch (FormatException expected) {
		}
		assertTrue(runner.connections.isEmpty());
		assertTrue(provider.closed.isEmpty());
	}

	private SecretKey randomRootKey() {
		byte[] rootBytes = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(rootBytes);
		return new SecretKey(rootBytes);
	}

	/** A separate establisher and counter model an independent device. */
	private ZtpConnectionHandlerImpl handler(ZtpSessionProvider provider,
			ZppConnectionRunner runner) {
		ZtpConnectionEstablisher establisher = new ZtpConnectionEstablisher(
				crypto, ratchet, mode3FullRatchet, sessionFactory,
				new ZwfStreamCounter(new MemStore()), cipherFactory());
		return new ZtpConnectionHandlerImpl(establisher, provider, runner,
				noOpConnectionRegistry(),
				new org.zerionproject.core.test.PermissiveOnionClientAuth());
	}

	private static void runBoth(Endpoint alice, Endpoint bob,
			ExchangeRunner runner) throws Exception {
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		Thread aliceThread = new Thread(() -> {
			try {
				alice.run();
			} catch (Throwable t) {
				errors.add(t);
			}
		});
		Thread bobThread = new Thread(() -> {
			try {
				bob.run();
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
	}

	/**
	 * Alice's runner ran for contact 2 and Bob's for contact 1, each exchanged
	 * six messages, and each side reported the closed session.
	 */
	private static void assertExchanged(ExchangeRunner runner,
			FakeProvider aliceProvider, FakeProvider bobProvider) {
		assertNotNull(runner.received.get(2));
		assertNotNull(runner.received.get(1));
		assertEquals(6, runner.received.get(2).size());
		assertEquals(6, runner.received.get(1).size());
		assertTrue(aliceProvider.closed.contains(2));
		assertTrue(bobProvider.closed.contains(1));
	}

	private static void assertZeroized(ZwfDuplexConnection connection) {
		assertNotNull(connection);
		Mode3FullState s = connection.currentMode3FullState();
		assertNotNull(s);
		assertAllZero(s.getOurActiveKeyPair().getDecapsulationKey());
		for (MlKemKeyPair kp : s.getRecentKeyPairs().values()) {
			assertAllZero(kp.getDecapsulationKey());
		}
	}

	private static void assertAllZero(byte[] b) {
		assertTrue(b.length > 0);
		for (byte x : b) assertEquals(0, x);
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
