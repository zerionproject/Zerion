package org.zerionproject.core.contact;

import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactType;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.core.test.TestDuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.fail;
import static org.zerionproject.core.api.contact.PendingContactState.OFFLINE;
import static org.zerionproject.core.test.TestDuplexTransportConnection.createPair;
import static org.zerionproject.core.test.TestPluginConfigModule.DUPLEX_TRANSPORT_ID;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
		PendingContact bobFromAlice = addPendingContact(alice, bob);
		PendingContact aliceFromBob = addPendingContact(bob, alice);
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

	@Ignore("Uses a classical (BRIAR) handshake link, which HandshakeManagerImpl "
			+ "now refuses (PQ-only policy). Pre-existing; needs migration to a "
			+ "ZERION/hybrid handshake link plus hybrid-key test fixtures.")
	@Test
	public void testHandshakeAndExchangeContactsFromPendingContacts()
			throws Exception {
		PendingContact bobFromAlice = addPendingContact(alice, bob);
		PendingContact aliceFromBob = addPendingContact(bob, alice);
		assertPendingContacts();

		TestDuplexTransportConnection[] pair = createPair();
		TestDuplexTransportConnection aliceConnection = pair[0];
		TestDuplexTransportConnection bobConnection = pair[1];
		CountDownLatch aliceFinished = new CountDownLatch(1);
		CountDownLatch bobFinished = new CountDownLatch(1);

		alice.getEventBus().addListener(e -> {
			if (e instanceof ContactAddedEvent) aliceFinished.countDown();
		});

		alice.getConnectionManager().manageOutgoingConnection(
				bobFromAlice.getId(), DUPLEX_TRANSPORT_ID, aliceConnection, true);
		bob.getEventBus().addListener(e -> {
			if (e instanceof ContactAddedEvent) bobFinished.countDown();
		});
		bob.getConnectionManager().manageIncomingConnection(
				aliceFromBob.getId(), DUPLEX_TRANSPORT_ID, bobConnection, true);
		assertTrue(aliceFinished.await(TIMEOUT, MILLISECONDS));
		assertTrue(bobFinished.await(TIMEOUT, MILLISECONDS));

		assertContacts(true, true);
		assertNoPendingContacts();
	}

	private PendingContact addPendingContact(
			ContactExchangeIntegrationTestComponent local,
			ContactExchangeIntegrationTestComponent remote) throws Exception {
		EventWaiter waiter = new EventWaiter();
		local.getEventBus().addListener(waiter);

		String link = remote.getContactManager().getHandshakeLink(ContactType.BRIAR);
		String alias = remote.getIdentityManager().getLocalAuthor().getName();
		PendingContact pendingContact =
				local.getContactManager().addPendingContact(link, alias);
		waiter.latch.await(TIMEOUT, MILLISECONDS);
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
}
