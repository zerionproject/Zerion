package org.briarproject.bramble.plugin.file;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.Identity;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.plugin.file.RemovableDriveTask;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.sync.event.MessageStateChangedEvent;
import org.briarproject.bramble.test.BrambleTestCase;
import org.briarproject.bramble.test.TestDatabaseConfigModule;
import org.briarproject.nullsafety.NotNullByDefault;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.briarproject.bramble.api.plugin.file.FileConstants.PROP_PATH;
import static org.briarproject.bramble.api.sync.validation.MessageState.DELIVERED;
import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertTrue;

public class RemovableDriveIntegrationTest extends BrambleTestCase {

	private static final int TIMEOUT_MS = 5_000;

	private final File testDir = getTestDirectory();
	private final File aliceDir = new File(testDir, "alice");
	private final File bobDir = new File(testDir, "bob");

	private final SecretKey rootKey = getSecretKey();
	private final long timestamp = System.currentTimeMillis();

	private RemovableDriveIntegrationTestComponent alice, bob;

	@Before
	public void setUp() {
		assertTrue(testDir.mkdirs());
		alice = DaggerRemovableDriveIntegrationTestComponent.builder()
				.testDatabaseConfigModule(
						new TestDatabaseConfigModule(aliceDir)).build();
		RemovableDriveIntegrationTestComponent.Helper
				.injectEagerSingletons(alice);
		bob = DaggerRemovableDriveIntegrationTestComponent.builder()
				.testDatabaseConfigModule(
						new TestDatabaseConfigModule(bobDir)).build();
		RemovableDriveIntegrationTestComponent.Helper
				.injectEagerSingletons(bob);
	}

	@Test
	public void testWriteAndRead() throws Exception {

		Identity aliceIdentity =
				alice.getIdentityManager().createIdentity("Alice");
		Identity bobIdentity = bob.getIdentityManager().createIdentity("Bob");

		ContactId bobId = setUp(alice, aliceIdentity,
				bobIdentity.getLocalAuthor(), true);
		ContactId aliceId = setUp(bob, bobIdentity,
				aliceIdentity.getLocalAuthor(), false);

		read(bob, write(alice, bobId), 2);

		read(alice, write(bob, aliceId), 2);
	}

	private ContactId setUp(RemovableDriveIntegrationTestComponent device,
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

	@SuppressWarnings("SameParameterValue")
	private void read(RemovableDriveIntegrationTestComponent device,
			File file, int deliveries) throws Exception {

		MessageDeliveryListener listener =
				new MessageDeliveryListener(deliveries);
		device.getEventBus().addListener(listener);

		TransportProperties p = new TransportProperties();
		p.put(PROP_PATH, file.getAbsolutePath());
		RemovableDriveTask reader =
				device.getRemovableDriveManager().startReaderTask(p);
		CountDownLatch disposedLatch = new CountDownLatch(1);
		reader.addObserver(state -> {
			if (state.isFinished()) disposedLatch.countDown();
		});

		assertTrue(listener.delivered.await(TIMEOUT_MS, MILLISECONDS));

		device.getEventBus().removeListener(listener);

		disposedLatch.await(TIMEOUT_MS, MILLISECONDS);
	}

	private File write(RemovableDriveIntegrationTestComponent device,
			ContactId contactId) throws Exception {

		File file = File.createTempFile("sync", ".tmp", testDir);
		TransportProperties p = new TransportProperties();
		p.put(PROP_PATH, file.getAbsolutePath());
		RemovableDriveTask writer = device.getRemovableDriveManager()
				.startWriterTask(contactId, p);
		CountDownLatch disposedLatch = new CountDownLatch(1);
		writer.addObserver(state -> {
			if (state.isFinished()) disposedLatch.countDown();
		});

		disposedLatch.await(TIMEOUT_MS, MILLISECONDS);

		return file;
	}

	private void tearDown(RemovableDriveIntegrationTestComponent device)
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
	private static class MessageDeliveryListener implements EventListener {

		private final CountDownLatch delivered;

		private MessageDeliveryListener(int deliveries) {
			delivered = new CountDownLatch(deliveries);
		}

		@Override
		public void eventOccurred(Event e) {
			if (e instanceof MessageStateChangedEvent) {
				MessageStateChangedEvent m = (MessageStateChangedEvent) e;
				if (m.getState().equals(DELIVERED)) delivered.countDown();
			}
		}
	}
}
