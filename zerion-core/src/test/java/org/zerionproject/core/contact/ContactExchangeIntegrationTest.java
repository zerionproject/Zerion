package org.zerionproject.core.contact;

import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.event.PendingContactReAddRefusedEvent;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactType;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.core.test.TestDuplexTransportConnection;
import org.zerionproject.core.test.TestTransportConnectionWriter;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.fail;
import static org.zerionproject.core.api.contact.ContactType.BRIAR;
import static org.zerionproject.core.api.contact.ContactType.ZERION;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_BYTES;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_RENDEZVOUS_X25519_BYTES;
import static org.zerionproject.core.api.contact.PendingContactState.OFFLINE;
import static org.zerionproject.core.test.TestDuplexTransportConnection.createPair;
import static org.zerionproject.core.test.TestPluginConfigModule.DUPLEX_TRANSPORT_ID;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContactExchangeIntegrationTest extends BrambleTestCase {

	private static final int TIMEOUT = 15_000;

	private final File testDir = getTestDirectory();
	private final File aliceDir = new File(testDir, "alice");
	private final File bobDir = new File(testDir, "bob");
	private final SecretKey masterKey = getSecretKey();
	private final Random random = new Random();

	private ContactExchangeIntegrationTestComponent alice, bob;
	private Identity aliceIdentity, bobIdentity;

	@Before
	public void setUp() throws Exception {
		assertTrue(testDir.mkdirs());

		alice = DaggerContactExchangeIntegrationTestComponent.builder()
				.testDatabaseConfigModule(
						new TestDatabaseConfigModule(aliceDir)).build();
		BrambleCoreIntegrationTestEagerSingletons.Helper
				.injectEagerSingletons(alice);
		bob = DaggerContactExchangeIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(bobDir))
				.build();
		BrambleCoreIntegrationTestEagerSingletons.Helper
				.injectEagerSingletons(bob);

		aliceIdentity = setUp(alice, "Alice");
		bobIdentity = setUp(bob, "Bob");
	}

	private Identity setUp(ContactExchangeIntegrationTestComponent device,
			String name) throws Exception {

		IdentityManager identityManager = device.getIdentityManager();
		Identity identity = identityManager.createIdentity(name);
		identityManager.registerIdentity(identity);

		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(getSecretKey());
		lifecycleManager.waitForStartup();

		ContactManager contactManager = device.getContactManager();
		assertEquals(0, contactManager.getPendingContacts().size());
		assertEquals(0, contactManager.getContacts().size());
		return identity;
	}

	@Test
	public void testExchangeContacts() throws Exception {
		TestDuplexTransportConnection[] pair = createPair();
		TestDuplexTransportConnection aliceConnection = pair[0];
		TestDuplexTransportConnection bobConnection = pair[1];
		CountDownLatch aliceFinished = new CountDownLatch(1);
		CountDownLatch bobFinished = new CountDownLatch(1);
		boolean verified = random.nextBoolean();

		alice.getIoExecutor().execute(() -> {
			try {
				alice.getContactExchangeManager().exchangeContacts(
						aliceConnection, masterKey, true, verified);
				aliceFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		bob.getIoExecutor().execute(() -> {
			try {
				bob.getContactExchangeManager().exchangeContacts(bobConnection,
						masterKey, false, verified);
				bobFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		assertTrue(bobFinished.await(TIMEOUT, MILLISECONDS));
		assertContacts(verified, false);
		assertNoPendingContacts();
	}

	@Test
	public void testExchangeContactsFromPendingContacts() throws Exception {
		PendingContact bobFromAlice = addPendingContact(alice, bob, BRIAR);
		PendingContact aliceFromBob = addPendingContact(bob, alice, BRIAR);
		assertPendingContacts();

		TestDuplexTransportConnection[] pair = createPair();
		TestDuplexTransportConnection aliceConnection = pair[0];
		TestDuplexTransportConnection bobConnection = pair[1];
		CountDownLatch aliceFinished = new CountDownLatch(1);
		CountDownLatch bobFinished = new CountDownLatch(1);
		boolean verified = random.nextBoolean();

		alice.getIoExecutor().execute(() -> {
			try {
				alice.getContactExchangeManager().exchangeContacts(
						bobFromAlice.getId(), aliceConnection, masterKey, true,
						verified, false);
				aliceFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		bob.getIoExecutor().execute(() -> {
			try {
				bob.getContactExchangeManager().exchangeContacts(
						aliceFromBob.getId(), bobConnection, masterKey, false,
						verified, false);
				bobFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		assertTrue(bobFinished.await(TIMEOUT, MILLISECONDS));
		assertContacts(verified, true);
		assertNoPendingContacts();
	}

	@Test
	public void testSeparatedKeysKeepTheRootOutOfTheExchange()
			throws Exception {
		PendingContact bobFromAlice = addPendingContact(alice, bob, BRIAR);
		PendingContact aliceFromBob = addPendingContact(bob, alice, BRIAR);
		SecretKey exchangeKey = getSecretKey();
		SecretKey aliceRoot = getSecretKey();
		SecretKey bobRoot = getSecretKey();
		HandshakeResult aliceResult = new HandshakeResult(
				new SecretKey(exchangeKey.getBytes().clone()),
				new SecretKey(aliceRoot.getBytes().clone()), true,
				true, true, null, null, null, null);
		HandshakeResult bobResult = new HandshakeResult(
				new SecretKey(exchangeKey.getBytes().clone()),
				new SecretKey(bobRoot.getBytes().clone()), true,
				false, true, null, null, null, null);

		TestDuplexTransportConnection[] pair = createPair();
		CountDownLatch aliceFinished = new CountDownLatch(1);
		CountDownLatch bobFinished = new CountDownLatch(1);
		alice.getIoExecutor().execute(() -> {
			try {
				alice.getContactExchangeManager().exchangeContacts(
						bobFromAlice.getId(), pair[0], aliceResult, false,
						false);
				aliceFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		bob.getIoExecutor().execute(() -> {
			try {
				bob.getContactExchangeManager().exchangeContacts(
						aliceFromBob.getId(), pair[1], bobResult, false,
						false);
				bobFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		assertTrue(bobFinished.await(TIMEOUT, MILLISECONDS));
		assertContacts(false, true);
		assertNoPendingContacts();
		assertStoredRoot(alice, aliceRoot);
		assertStoredRoot(bob, bobRoot);
	}

	@Test
	public void aSecondPairingReplacesAnExistingContactsKeysOnlyOnReAdd()
			throws Exception {
		SecretKey first = getSecretKey();
		assertNull(exchange(addPendingContact(alice, bob, BRIAR),
				addPendingContact(bob, alice, BRIAR), first)[0]);
		assertStoredRoot(alice, first);
		assertStoredRoot(bob, first);

		Throwable[] refused = exchange(addPendingContact(alice, bob, ZERION),
				addPendingContact(bob, alice, ZERION), getSecretKey());
		assertTrue(refused[0] instanceof ContactExistsException);
		assertTrue(refused[1] instanceof ContactExistsException);
		assertContacts(false, true);
		assertNoPendingContacts();
		assertStoredRoot(alice, first);
		assertStoredRoot(bob, first);

		ReAddRefusedWaiter waiter = new ReAddRefusedWaiter();
		alice.getEventBus().addListener(waiter);
		PendingContact reAdding = reAdd(alice, bob);
		Throwable[] oneSided = exchange(reAdding,
				addPendingContact(bob, alice, ZERION), getSecretKey());
		assertTrue("the re-adding side refuses the unsigned record",
				oneSided[0] instanceof FormatException);
		assertTrue(oneSided[1] instanceof ContactExistsException);
		assertTrue(waiter.latch.await(TIMEOUT, MILLISECONDS));
		PendingContactReAddRefusedEvent e = waiter.event.get();
		assertNotNull(e);
		assertEquals(reAdding.getId(), e.getId());
		assertEquals(bobIdentity.getLocalAuthor().getId(),
				e.getAuthor().getId());
		assertEquals(reAdding.getAlias(), e.getAlias());
		assertNoPendingContacts();
		assertStoredRoot(alice, first);
		assertStoredRoot(bob, first);
		alice.getEventBus().removeListener(waiter);

		SecretKey reAdded = getSecretKey();
		Throwable[] done = exchange(reAdd(alice, bob), reAdd(bob, alice),
				reAdded);
		assertNull(done[0]);
		assertNull(done[1]);
		assertContacts(false, true);
		assertNoPendingContacts();
		assertStoredRoot(alice, reAdded);
		assertStoredRoot(bob, reAdded);
	}

	private PendingContact reAdd(ContactExchangeIntegrationTestComponent local,
			ContactExchangeIntegrationTestComponent remote) throws Exception {
		String link = remote.getContactManager().getHandshakeLink(ZERION);
		String alias = remote.getIdentityManager().getLocalAuthor().getName();
		return local.getContactManager().addPendingContact(link, alias, null,
				true, false);
	}

	private Throwable[] exchange(PendingContact bobFromAlice,
			PendingContact aliceFromBob, SecretKey root) throws Exception {
		SecretKey exchangeKey = getSecretKey();
		HandshakeResult aliceResult = new HandshakeResult(
				new SecretKey(exchangeKey.getBytes().clone()),
				new SecretKey(root.getBytes().clone()), true, true, true, null,
				null, null, null);
		HandshakeResult bobResult = new HandshakeResult(
				new SecretKey(exchangeKey.getBytes().clone()),
				new SecretKey(root.getBytes().clone()), true, false, true, null,
				null, null, null);
		TestDuplexTransportConnection[] pair = createPair();
		Throwable[] thrown = new Throwable[2];
		CountDownLatch finished = new CountDownLatch(2);
		alice.getIoExecutor().execute(() -> {
			try {
				alice.getContactExchangeManager().exchangeContacts(
						bobFromAlice.getId(), pair[0], aliceResult, false,
						false);
			} catch (Exception e) {
				thrown[0] = e;
			}
			finished.countDown();
		});
		bob.getIoExecutor().execute(() -> {
			try {
				bob.getContactExchangeManager().exchangeContacts(
						aliceFromBob.getId(), pair[1], bobResult, false,
						false);
			} catch (Exception e) {
				thrown[1] = e;
			}
			finished.countDown();
		});
		assertTrue(finished.await(TIMEOUT, MILLISECONDS));
		return thrown;
	}

	@Test
	public void testNearbyExchangeWithSeparatedKeysStoresTheRoot()
			throws Exception {
		SecretKey exchangeKey = getSecretKey();
		SecretKey aliceRoot = getSecretKey();
		SecretKey bobRoot = getSecretKey();
		TestDuplexTransportConnection[] pair = createPair();
		CountDownLatch aliceFinished = new CountDownLatch(1);
		CountDownLatch bobFinished = new CountDownLatch(1);
		alice.getIoExecutor().execute(() -> {
			try {
				alice.getContactExchangeManager().exchangeContacts(pair[0],
						new SecretKey(exchangeKey.getBytes().clone()),
						new SecretKey(aliceRoot.getBytes().clone()), true,
						true);
				aliceFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		bob.getIoExecutor().execute(() -> {
			try {
				bob.getContactExchangeManager().exchangeContacts(pair[1],
						new SecretKey(exchangeKey.getBytes().clone()),
						new SecretKey(bobRoot.getBytes().clone()), false,
						true);
				bobFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		assertTrue(bobFinished.await(TIMEOUT, MILLISECONDS));
		assertContacts(true, false);
		assertStoredRoot(alice, aliceRoot);
		assertStoredRoot(bob, bobRoot);
	}

	@Test
	public void testTheRootIsNeverAKeyDuringTheHandshakeOrTheExchange()
			throws Exception {
		HandshakeKeySeparationTest handshake = new HandshakeKeySeparationTest();
		handshake.setUp();
		HandshakeKeySeparationTest.Party[] parties =
				new HandshakeKeySeparationTest.Party[2];
		HandshakeKeySeparationTest.Outcome o = handshake.pair(
				HandshakeConstants.PROTOCOL_MINOR_VERSION,
				HandshakeConstants.PROTOCOL_MINOR_VERSION,
				HandshakeKeySeparationTest.PASS, parties);
		assertNotNull(o.first);
		assertNotNull(o.second);
		assertTrue(o.first.isKeySeparated());
		byte[] root = o.first.getRootKey().getBytes().clone();
		byte[] exchange = o.first.getExchangeKey().getBytes().clone();
		HandshakeResult aliceResult = o.first.isAlice() ? o.first : o.second;
		HandshakeResult bobResult = o.first.isAlice() ? o.second : o.first;

		PendingContact bobFromAlice = addPendingContact(alice, bob, BRIAR);
		PendingContact aliceFromBob = addPendingContact(bob, alice, BRIAR);
		java.util.List<byte[]> keysUsed = java.util.Collections
				.synchronizedList(new java.util.ArrayList<>());
		ContactExchangeManagerImpl aliceExchange =
				recordingExchange(alice, keysUsed);
		ContactExchangeManagerImpl bobExchange =
				recordingExchange(bob, keysUsed);
		TestDuplexTransportConnection[] pair = createPair();
		CountDownLatch aliceFinished = new CountDownLatch(1);
		CountDownLatch bobFinished = new CountDownLatch(1);
		alice.getIoExecutor().execute(() -> {
			try {
				aliceExchange.exchangeContacts(bobFromAlice.getId(), pair[0],
						aliceResult, false, false);
				aliceFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		bob.getIoExecutor().execute(() -> {
			try {
				bobExchange.exchangeContacts(aliceFromBob.getId(), pair[1],
						bobResult, false, false);
				bobFinished.countDown();
			} catch (Exception e) {
				fail();
			}
		});
		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		assertTrue(bobFinished.await(TIMEOUT, MILLISECONDS));
		assertContacts(false, true);
		assertStoredRoot(alice, new SecretKey(root.clone()));
		assertStoredRoot(bob, new SecretKey(root.clone()));

		boolean exchangeKeyUsed = false;
		synchronized (keysUsed) {
			assertFalse(keysUsed.isEmpty());
			for (byte[] k : keysUsed) {
				assertFalse("the root was a key during the contact exchange",
						Arrays.equals(k, root));
				if (Arrays.equals(k, exchange)) exchangeKeyUsed = true;
			}
		}
		assertTrue(exchangeKeyUsed);
		for (HandshakeKeySeparationTest.Party p : parties) {
			assertFalse("the root was a key during the handshake",
					p.recorder.usedAsKey(root));
		}
	}

	private static Object recording(Object target, Class<?> type,
			java.util.List<byte[]> keysUsed) {
		return java.lang.reflect.Proxy.newProxyInstance(
				type.getClassLoader(), new Class<?>[] {type},
				(proxy, method, args) -> {
					if (args != null) {
						for (Object a : args) {
							if (a instanceof SecretKey) {
								keysUsed.add(((SecretKey) a).getBytes()
										.clone());
							}
						}
					}
					try {
						return method.invoke(target, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	private static ContactExchangeManagerImpl recordingExchange(
			ContactExchangeIntegrationTestComponent d,
			java.util.List<byte[]> keysUsed) {
		ContactExchangeCrypto crypto = d.getContactExchangeCrypto();
		ContactExchangeCrypto recordingCrypto =
				(ContactExchangeCrypto) recording(crypto,
						ContactExchangeCrypto.class, keysUsed);
		return new ContactExchangeManagerImpl(d.getDatabaseComponent(),
				d.getClientHelper(), d.getRecordReaderFactory(),
				d.getRecordWriterFactory(), d.getClock(), d.getContactManager(),
				d.getIdentityManager(), d.getTransportPropertyManager(),
				recordingCrypto,
				(org.zerionproject.core.api.transport.StreamReaderFactory)
						recording(d.getStreamReaderFactory(),
								org.zerionproject.core.api.transport
										.StreamReaderFactory.class, keysUsed),
				(org.zerionproject.core.api.transport.StreamWriterFactory)
						recording(d.getStreamWriterFactory(),
								org.zerionproject.core.api.transport
										.StreamWriterFactory.class, keysUsed),
				d.getSettingsManager());
	}

	private static void assertStoredRoot(
			ContactExchangeIntegrationTestComponent device, SecretKey root)
			throws Exception {
		String label = "org.zerionproject.test/STORED_ROOT_PROBE";
		Contact c = device.getContactManager().getContacts().iterator().next();
		SecretKey probe = device.getContactManager().deriveContactKey(
				c.getId(), label);
		assertArrayEquals(device.getCryptoComponent().deriveKey(label, root)
				.getBytes(), probe.getBytes());
	}

	@Test
	public void testHandshakeAndExchangeContactsFromPendingContacts()
			throws Exception {
		PendingContact bobFromAlice = addPendingContact(alice, bob, ZERION);
		PendingContact aliceFromBob = addPendingContact(bob, alice, ZERION);
		assertHybridPendingContact(alice, bobIdentity);
		assertHybridPendingContact(bob, aliceIdentity);

		TestDuplexTransportConnection[] pair = createPair();
		TestDuplexTransportConnection aliceConnection = pair[0];
		TestDuplexTransportConnection bobConnection = pair[1];
		CountDownLatch aliceFinished = new CountDownLatch(1);
		CountDownLatch bobFinished = new CountDownLatch(1);

		alice.getEventBus().addListener(e -> {
			if (e instanceof ContactAddedEvent) aliceFinished.countDown();
		});
		bob.getEventBus().addListener(e -> {
			if (e instanceof ContactAddedEvent) bobFinished.countDown();
		});

		alice.getConnectionManager().manageOutgoingConnection(
				bobFromAlice.getId(), DUPLEX_TRANSPORT_ID, aliceConnection,
				false);
		bob.getConnectionManager().manageIncomingConnection(
				aliceFromBob.getId(), DUPLEX_TRANSPORT_ID, bobConnection,
				false);
		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		assertTrue(bobFinished.await(TIMEOUT, MILLISECONDS));

		assertHybridContact(alice, bobIdentity);
		assertHybridContact(bob, aliceIdentity);
		assertNoPendingContacts();
		endPairedSessions(aliceConnection, bobConnection);
	}

	private static void endPairedSessions(
			TestDuplexTransportConnection... connections) throws Exception {
		for (TestDuplexTransportConnection c : connections) {
			c.getWriter().getOutputStream().close();
		}
		for (TestDuplexTransportConnection c : connections) {
			TestTransportConnectionWriter writer =
					(TestTransportConnectionWriter) c.getWriter();
			assertTrue("the paired session did not end",
					writer.getDisposedLatch().await(TIMEOUT, MILLISECONDS));
		}
	}

	private PendingContact addPendingContact(
			ContactExchangeIntegrationTestComponent local,
			ContactExchangeIntegrationTestComponent remote, ContactType type)
			throws Exception {
		EventWaiter waiter = new EventWaiter();
		local.getEventBus().addListener(waiter);

		String link = remote.getContactManager().getHandshakeLink(type);
		String alias = remote.getIdentityManager().getLocalAuthor().getName();
		PendingContact pendingContact =
				local.getContactManager().addPendingContact(link, alias);
		assertTrue(waiter.latch.await(TIMEOUT, MILLISECONDS));
		return pendingContact;
	}

	private void assertContacts(boolean verified,
			boolean withHandshakeKeys) throws Exception {
		assertContact(alice, bobIdentity, verified, withHandshakeKeys);
		assertContact(bob, aliceIdentity, verified, withHandshakeKeys);
	}

	private void assertContact(ContactExchangeIntegrationTestComponent local,
			Identity expectedIdentity, boolean verified,
			boolean withHandshakeKey) throws Exception {
		Collection<Contact> contacts = local.getContactManager().getContacts();
		assertEquals(1, contacts.size());
		Contact contact = contacts.iterator().next();
		assertEquals(expectedIdentity.getLocalAuthor(), contact.getAuthor());
		assertEquals(verified, contact.isVerified());
		PublicKey expectedPublicKey = expectedIdentity.getHandshakePublicKey();
		PublicKey actualPublicKey = contact.getHandshakePublicKey();
		assertNotNull(expectedPublicKey);
		if (withHandshakeKey) {
			assertNotNull(actualPublicKey);
			assertArrayEquals(expectedPublicKey.getEncoded(),
					actualPublicKey.getEncoded());
		} else {
			assertNull(actualPublicKey);
		}
	}

	private void assertHybridPendingContact(
			ContactExchangeIntegrationTestComponent local,
			Identity expectedIdentity) throws Exception {
		Collection<Pair<PendingContact, PendingContactState>> pairs =
				local.getContactManager().getPendingContacts();
		assertEquals(1, pairs.size());
		Pair<PendingContact, PendingContactState> pair =
				pairs.iterator().next();
		assertEquals(OFFLINE, pair.getSecond());
		PendingContact pendingContact = pair.getFirst();
		assertTrue(pendingContact.isPostQuantum());
		assertEquals(expectedIdentity.getLocalAuthor().getName(),
				pendingContact.getAlias());
		byte[] blob = pendingContact.getPublicKey().getEncoded();
		assertEquals(HYBRID_COMMITMENT_BYTES + HYBRID_RENDEZVOUS_X25519_BYTES,
				blob.length);
		assertArrayEquals(rendezvousKey(expectedIdentity),
				Arrays.copyOfRange(blob, HYBRID_COMMITMENT_BYTES, blob.length));
	}

	private void assertHybridContact(
			ContactExchangeIntegrationTestComponent local,
			Identity expectedIdentity) throws Exception {
		Collection<Contact> contacts = local.getContactManager().getContacts();
		assertEquals(1, contacts.size());
		Contact contact = contacts.iterator().next();
		assertEquals(expectedIdentity.getLocalAuthor(), contact.getAuthor());
		assertFalse(contact.isVerified());
		assertTrue(contact.isPostQuantum());
		PublicKey actualPublicKey = contact.getHandshakePublicKey();
		assertNotNull(actualPublicKey);
		assertArrayEquals(rendezvousKey(expectedIdentity),
				actualPublicKey.getEncoded());
	}

	private static byte[] rendezvousKey(Identity identity) {
		PublicKey hybrid = identity.getHybridHandshakePublicKey();
		assertNotNull(hybrid);
		return Arrays.copyOfRange(hybrid.getEncoded(), 0,
				HYBRID_RENDEZVOUS_X25519_BYTES);
	}

	private void assertNoPendingContacts() throws Exception {
		assertEquals(0, alice.getContactManager().getPendingContacts().size());
		assertEquals(0, bob.getContactManager().getPendingContacts().size());
	}

	private void assertPendingContacts() throws Exception {
		assertPendingContact(alice, bobIdentity);
		assertPendingContact(bob, aliceIdentity);
	}

	private void assertPendingContact(
			ContactExchangeIntegrationTestComponent local,
			Identity expectedIdentity) throws Exception {
		Collection<Pair<PendingContact, PendingContactState>> pairs =
				local.getContactManager().getPendingContacts();
		assertEquals(1, pairs.size());
		Pair<PendingContact, PendingContactState> pair =
				pairs.iterator().next();
		assertEquals(OFFLINE, pair.getSecond());
		PendingContact pendingContact = pair.getFirst();
		assertEquals(expectedIdentity.getLocalAuthor().getName(),
				pendingContact.getAlias());
		PublicKey expectedPublicKey = expectedIdentity.getHandshakePublicKey();
		assertNotNull(expectedPublicKey);
		assertArrayEquals(expectedPublicKey.getEncoded(),
				pendingContact.getPublicKey().getEncoded());
	}

	private void tearDown(ContactExchangeIntegrationTestComponent device)
			throws Exception {

		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.stopServices();
		lifecycleManager.waitForShutdown();
	}

	@After
	public void tearDown() throws Exception {
		tearDown(alice);
		tearDown(bob);
		deleteTestDirectory(testDir);
	}

	@NotNullByDefault
	private static class EventWaiter implements EventListener {

		private final CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof PendingContactStateChangedEvent) {
				PendingContactStateChangedEvent p =
						(PendingContactStateChangedEvent) e;
				if (p.getPendingContactState() == OFFLINE) latch.countDown();
			}
		}
	}
	private static class ReAddRefusedWaiter implements EventListener {

		private final CountDownLatch latch = new CountDownLatch(1);
		private final AtomicReference<PendingContactReAddRefusedEvent> event =
				new AtomicReference<>();

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof PendingContactReAddRefusedEvent) {
				event.set((PendingContactReAddRefusedEvent) e);
				latch.countDown();
			}
		}
	}
}
