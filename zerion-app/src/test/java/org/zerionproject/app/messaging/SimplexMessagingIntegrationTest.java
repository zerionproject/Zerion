package org.zerionproject.app.messaging;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.event.MessagesSentEvent;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.core.test.TestTransportConnectionReader;
import org.zerionproject.core.test.TestTransportConnectionWriter;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.event.PrivateMessageReceivedEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.emptyList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.test.TestPluginConfigModule.SIMPLEX_TRANSPORT_ID;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A2-CRY-05: an established contact's traffic runs only over the ZWF
 * session layer. The rotation-key sync connections that carried it before
 * are refused for contacts, so a caller that asks the connection manager
 * for a simplex sync connection to a contact gets a disposed writer and
 * no bytes, and a stream offered on the incoming side delivers nothing.
 */
public class SimplexMessagingIntegrationTest extends BrambleTestCase {

	private static final int TIMEOUT_MS = 5_000;

	private final File testDir = getTestDirectory();
	private final File aliceDir = new File(testDir, "alice");
	private final File bobDir = new File(testDir, "bob");

	private final SecretKey rootKey = getSecretKey();
	private final long timestamp = System.currentTimeMillis();

	private SimplexMessagingIntegrationTestComponent alice, bob;

	@Before
	public void setUp() {
		assertTrue(testDir.mkdirs());
		alice = DaggerSimplexMessagingIntegrationTestComponent.builder()
				.testDatabaseConfigModule(
						new TestDatabaseConfigModule(aliceDir)).build();
		SimplexMessagingIntegrationTestComponent.Helper
				.injectEagerSingletons(alice);
		bob = DaggerSimplexMessagingIntegrationTestComponent.builder()
				.testDatabaseConfigModule(new TestDatabaseConfigModule(bobDir))
				.build();
		SimplexMessagingIntegrationTestComponent.Helper
				.injectEagerSingletons(bob);
	}

	@Test
	public void contactSyncOverRotationKeysIsRefused() throws Exception {
		Identity aliceIdentity =
				alice.getIdentityManager().createIdentity("Alice");
		Identity bobIdentity = bob.getIdentityManager().createIdentity("Bob");
		ContactId bobId = setUp(alice, aliceIdentity,
				bobIdentity.getLocalAuthor(), true);
		setUp(bob, bobIdentity, aliceIdentity.getLocalAuthor(), false);

		queueMessage(alice, bobId);

		AtomicBoolean sent = new AtomicBoolean();
		EventListener sendListener = e -> {
			if (e instanceof MessagesSentEvent) sent.set(true);
		};
		alice.getEventBus().addListener(sendListener);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		TestTransportConnectionWriter writer =
				new TestTransportConnectionWriter(out, false);
		alice.getConnectionManager().manageOutgoingConnection(bobId,
				SIMPLEX_TRANSPORT_ID, writer);
		assertTrue("the writer must be disposed, not left open",
				writer.getDisposedLatch().await(TIMEOUT_MS, MILLISECONDS));
		alice.getEventBus().removeListener(sendListener);
		assertEquals("no bytes may leave over rotation keys", 0, out.size());
		assertFalse("nothing may be reported as sent", sent.get());

		AtomicBoolean received = new AtomicBoolean();
		EventListener receiveListener = e -> {
			if (e instanceof PrivateMessageReceivedEvent) received.set(true);
		};
		bob.getEventBus().addListener(receiveListener);
		CountDownLatch disposed = new CountDownLatch(1);
		AtomicBoolean recognised = new AtomicBoolean(true);
		TestTransportConnectionReader reader = new TestTransportConnectionReader(
				new ByteArrayInputStream(getRandomBytes(1024))) {
			@Override
			public void dispose(boolean exception, boolean wasRecognised)
					throws IOException {
				recognised.set(wasRecognised);
				disposed.countDown();
				super.dispose(exception, wasRecognised);
			}
		};
		bob.getConnectionManager().manageIncomingConnection(
				SIMPLEX_TRANSPORT_ID, reader);
		assertTrue("the reader must be disposed, not left open",
				disposed.await(TIMEOUT_MS, MILLISECONDS));
		bob.getEventBus().removeListener(receiveListener);
		assertFalse("an unrecognised stream is not a contact's", recognised.get());
		assertFalse(received.get());
	}

	private ContactId setUp(SimplexMessagingIntegrationTestComponent device,
			Identity local, Author remote, boolean alice) throws Exception {
		IdentityManager identityManager = device.getIdentityManager();
		identityManager.registerIdentity(local);
		LifecycleManager lifecycleManager = device.getLifecycleManager();
		lifecycleManager.startServices(getSecretKey());
		lifecycleManager.waitForStartup();
		ContactManager contactManager = device.getContactManager();
		return contactManager.addContact(remote, local.getId(), rootKey,
				timestamp, alice, true, true);
	}

	private void queueMessage(SimplexMessagingIntegrationTestComponent device,
			ContactId contactId) throws Exception {
		MessagingManager messagingManager = device.getMessagingManager();
		GroupId groupId = messagingManager.getConversationId(contactId);
		PrivateMessageFactory privateMessageFactory =
				device.getPrivateMessageFactory();
		PrivateMessage message = privateMessageFactory.createPrivateMessage(
				groupId, System.currentTimeMillis(), "Hi!", emptyList(),
				MIN_AUTO_DELETE_TIMER_MS);
		messagingManager.addLocalMessage(message);
	}

	private void tearDown(SimplexMessagingIntegrationTestComponent device)
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
}
