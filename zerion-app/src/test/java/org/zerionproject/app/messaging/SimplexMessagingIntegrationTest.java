package org.zerionproject.app.messaging;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.event.MessageStateChangedEvent;
import org.zerionproject.core.api.sync.event.MessagesSentEvent;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfigModule;
import org.zerionproject.core.test.TestTransportConnectionReader;
import org.zerionproject.core.test.TestTransportConnectionWriter;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFactory;
import org.zerionproject.app.api.messaging.event.AttachmentReceivedEvent;
import org.zerionproject.app.api.messaging.event.PrivateMessageReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.test.TestPluginConfigModule.SIMPLEX_TRANSPORT_ID;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;
import static org.junit.Assert.assertTrue;

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
	public void testWriteAndReadWithLazyRetransmission() throws Exception {
		testWriteAndRead(false);
	}

	@Test
	public void testWriteAndReadWithEagerRetransmission() throws Exception {
		testWriteAndRead(true);
	}

	private void testWriteAndRead(boolean eager) throws Exception {

		Identity aliceIdentity =
				alice.getIdentityManager().createIdentity("Alice");
		Identity bobIdentity = bob.getIdentityManager().createIdentity("Bob");

		ContactId bobId = setUp(alice, aliceIdentity,
				bobIdentity.getLocalAuthor(), true);
		ContactId aliceId = setUp(bob, bobIdentity,
				aliceIdentity.getLocalAuthor(), false);

		PrivateMessageListener listener = new PrivateMessageListener();
		bob.getEventBus().addListener(listener);

		sendMessage(alice, bobId);

		read(bob, write(alice, bobId, eager, 1), 1);

		read(alice, write(bob, aliceId, eager, 1), 1);

		read(bob, write(alice, bobId, eager, 3), 3);

		assertTrue(listener.messageAdded);

		assertTrue(listener.attachmentAdded);

		read(bob, write(alice, bobId, eager, eager ? 3 : 0), 0);
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

	private void sendMessage(SimplexMessagingIntegrationTestComponent device,
			ContactId contactId) throws Exception {
		MessagingManager messagingManager = device.getMessagingManager();
		GroupId groupId = messagingManager.getConversationId(contactId);
		long timestamp = System.currentTimeMillis();
		InputStream in = new ByteArrayInputStream(new byte[] {0, 1, 2, 3});
		AttachmentHeader attachmentHeader = messagingManager.addLocalAttachment(
				groupId, timestamp, "image/png", in);
		PrivateMessageFactory privateMessageFactory =
				device.getPrivateMessageFactory();
		PrivateMessage message = privateMessageFactory.createPrivateMessage(
				groupId, timestamp, "Hi!", singletonList(attachmentHeader),
				MIN_AUTO_DELETE_TIMER_MS);
		messagingManager.addLocalMessage(message);
	}

	@SuppressWarnings("SameParameterValue")
	private void read(SimplexMessagingIntegrationTestComponent device,
			byte[] stream, int deliveries) throws Exception {

		MessageDeliveryListener listener =
				new MessageDeliveryListener(deliveries);
		device.getEventBus().addListener(listener);

		ByteArrayInputStream in = new ByteArrayInputStream(stream);
		TestTransportConnectionReader reader =
				new TestTransportConnectionReader(in);
		device.getConnectionManager().manageIncomingConnection(
				SIMPLEX_TRANSPORT_ID, reader);

		assertTrue(listener.delivered.await(TIMEOUT_MS, MILLISECONDS));

		device.getEventBus().removeListener(listener);
	}

	private byte[] write(SimplexMessagingIntegrationTestComponent device,
			ContactId contactId, boolean eager, int transmissions)
			throws Exception {

		MessageTransmissionListener listener =
				new MessageTransmissionListener(transmissions);
		device.getEventBus().addListener(listener);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		TestTransportConnectionWriter writer =
				new TestTransportConnectionWriter(out, eager);
		device.getConnectionManager().manageOutgoingConnection(contactId,
				SIMPLEX_TRANSPORT_ID, writer);

		writer.getDisposedLatch().await(TIMEOUT_MS, MILLISECONDS);

		assertTrue(listener.sent.await(TIMEOUT_MS, MILLISECONDS));

		device.getEventBus().removeListener(listener);

		return out.toByteArray();
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

	@NotNullByDefault
	private static class MessageTransmissionListener implements EventListener {

		private final CountDownLatch sent;

		private MessageTransmissionListener(int transmissions) {
			sent = new CountDownLatch(transmissions);
		}

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof MessagesSentEvent) {
				MessagesSentEvent m = (MessagesSentEvent) e;
				for (MessageId ignored : m.getMessageIds()) sent.countDown();
			}
		}
	}

	@NotNullByDefault
	private static class MessageDeliveryListener implements EventListener {

		private final CountDownLatch delivered;

		private MessageDeliveryListener(int deliveries) {
			delivered = new CountDownLatch(deliveries);
		}

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof MessageStateChangedEvent) {
				MessageStateChangedEvent m = (MessageStateChangedEvent) e;
				if (!m.isLocal() && m.getState().equals(DELIVERED)) {
					delivered.countDown();
				}
			}
		}
	}

	@NotNullByDefault
	private static class PrivateMessageListener implements EventListener {

		private volatile boolean messageAdded = false;
		private volatile boolean attachmentAdded = false;

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof PrivateMessageReceivedEvent) {
				messageAdded = true;
			} else if (e instanceof AttachmentReceivedEvent) {
				attachmentAdded = true;
			}
		}
	}
}
