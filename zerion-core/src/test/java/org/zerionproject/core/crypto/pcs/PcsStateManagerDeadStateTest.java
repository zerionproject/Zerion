package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestMessageFactory;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_RECEIVE;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_SEND;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class PcsStateManagerDeadStateTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private DatabaseComponent db;

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void unusedRatchetKeysAreNeitherStoredNorKept() throws Exception {
		db = HyperSqlDatabaseForTests.open(testDir, getSecretKey(),
				new NoBus(), new TestMessageFactory());
		Constructor<?> cc = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl")
				.getDeclaredConstructor(Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
						Class.forName(
								"org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		CryptoComponent crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		LifecycleManager lifecycle = (LifecycleManager) Proxy.newProxyInstance(
				LifecycleManager.class.getClassLoader(),
				new Class<?>[] {LifecycleManager.class},
				(proxy, method, args) -> null);
		PcsStateManager manager = new PcsStateManager(db, lifecycle);
		Identity identity = getIdentity();
		SecretKey root = getSecretKey();
		SecretKey legacyRoot = getSecretKey();
		KeyPair dh = crypto.generateAgreementKeyPair();
		ContactId[] ids = db.transactionWithResult(false, txn -> {
			db.addIdentity(txn, identity);
			ContactId fresh = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, true);
			ContactId legacy = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, true);
			manager.initializePairingRoot(txn, fresh, root);
			for (int direction : new int[] {PCS_DIRECTION_SEND,
					PCS_DIRECTION_RECEIVE}) {
				db.setPcsMode2SessionState(txn, legacy, direction, legacyRoot,
						0, 0, legacyRoot, dh.getPrivate(), dh.getPublic(),
						null, true, new byte[] {1, 2, 3});
			}
			return new ContactId[] {fresh, legacy};
		});

		Object[] row = db.transactionWithResult(true, txn ->
				db.getPcsMode2SessionState(txn, ids[0], PCS_DIRECTION_SEND));
		assertArrayEquals(root.getBytes(), (byte[]) row[3]);
		assertNull(row[4]);
		assertNull(row[5]);
		assertNull(row[8]);

		manager.stripDeadState(ids[1]);
		for (int direction : new int[] {PCS_DIRECTION_SEND,
				PCS_DIRECTION_RECEIVE}) {
			Object[] stripped = db.transactionWithResult(true, txn ->
					db.getPcsMode2SessionState(txn, ids[1], direction));
			assertArrayEquals(legacyRoot.getBytes(), (byte[]) stripped[3]);
			assertNull(stripped[4]);
			assertNull(stripped[5]);
			assertNull(stripped[8]);
		}
		assertNotNull(manager.loadSendState(ids[1]));
	}

	private static final class NoBus implements EventBus {
		@Override
		public void addListener(EventListener l) {
		}

		@Override
		public void removeListener(EventListener l) {
		}

		@Override
		public void broadcast(Event e) {
		}
	}
}
