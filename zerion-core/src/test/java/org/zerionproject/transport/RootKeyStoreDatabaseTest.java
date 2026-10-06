package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_RECEIVE;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_SEND;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class RootKeyStoreDatabaseTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final SecretKey dbKey = getSecretKey();
	private final MessageFactory messageFactory = new TestMessageFactory();
	private DatabaseComponent db;
	private ContactId contact;
	private SecretKey pairingRoot;

	@Before
	public void setUp() throws Exception {
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, new NoBus(),
				messageFactory);
		Identity identity = getIdentity();
		pairingRoot = getSecretKey();
		contact = db.transactionWithResult(false, txn -> {
			db.addIdentity(txn, identity);
			ContactId c = db.addContact(txn, getAuthor(),
					identity.getLocalAuthor().getId(), null, true);
			for (int direction : new int[] {PCS_DIRECTION_SEND,
					PCS_DIRECTION_RECEIVE}) {
				db.setPcsMode2SessionState(txn, c, direction, pairingRoot, 0,
						0, pairingRoot, null, null, null, true, null);
			}
			return c;
		});
	}

	@After
	public void tearDown() throws Exception {
		if (db != null) db.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void pendingRootsArePromotedOrDroppedOnlyFromTheirEpoch()
			throws Exception {
		RootKeyStore store = new RootKeyStore(db);
		ContactRootKeys keys = store.load(contact);
		assertNotNull(keys);
		assertEquals(0, keys.getEpoch());
		assertArrayEquals(pairingRoot.getBytes(),
				keys.getCurrent().getBytes());
		assertNull(keys.getPending());

		SecretKey first = getSecretKey();
		assertFalse(store.storePending(contact, 1, first, false));
		assertTrue(store.storePending(contact, 0, first, false));
		keys = store.load(contact);
		assertNotNull(keys);
		assertArrayEquals(first.getBytes(), keys.getPending().getBytes());
		assertFalse(keys.isPendingConfirmed());
		assertEquals(0, keys.getSendEpoch());

		assertFalse(store.promote(contact, 1));
		assertTrue(store.promote(contact, 0));
		keys = store.load(contact);
		assertEquals(1, keys.getEpoch());
		assertArrayEquals(first.getBytes(), keys.getCurrent().getBytes());
		assertNull(keys.getPending());
		assertFalse(store.promote(contact, 1));

		SecretKey second = getSecretKey();
		assertTrue(store.storePending(contact, 1, second, true));
		keys = store.load(contact);
		assertTrue(keys.isPendingConfirmed());
		assertEquals(2, keys.getSendEpoch());
		assertFalse(store.discardPending(contact, 0));
		assertTrue(store.discardPending(contact, 1));
		keys = store.load(contact);
		assertEquals(1, keys.getEpoch());
		assertNull(keys.getPending());

		Object[] memoRow = db.transactionWithResult(true, txn ->
				db.getPcsMode2SessionState(txn, contact, PCS_DIRECTION_SEND));
		assertArrayEquals("the pairing root wrapping voice memos is kept",
				pairingRoot.getBytes(), (byte[]) memoRow[3]);

		db.close();
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, new NoBus(),
				messageFactory);
		keys = new RootKeyStore(db).load(contact);
		assertNotNull(keys);
		assertEquals(1, keys.getEpoch());
		assertArrayEquals(first.getBytes(), keys.getCurrent().getBytes());
	}

	@Test
	public void theKeysGoWithTheContact() throws Exception {
		RootKeyStore store = new RootKeyStore(db);
		assertTrue(store.storePending(contact, 0, getSecretKey(), false));
		assertTrue(store.promote(contact, 0));
		assertTrue(store.storePending(contact, 1, getSecretKey(), true));
		db.transaction(false, txn -> db.removeContact(txn, contact));
		assertNull(store.load(contact));
	}

	@Test
	public void noRootChangesWhileASnapshotIsWritten() throws Exception {
		RootKeyStore store = new RootKeyStore(db);
		store.beginSnapshot();
		assertTrue(store.isSnapshotting());
		assertFalse(store.storePending(contact, 0, getSecretKey(), false));
		assertNull(store.load(contact).getPending());
		store.endSnapshot();
		assertFalse(store.isSnapshotting());
		assertTrue(store.storePending(contact, 0, getSecretKey(), false));
		store.beginSnapshot();
		assertFalse(store.promote(contact, 0));
		assertFalse(store.discardPending(contact, 0));
		assertEquals(0, store.load(contact).getEpoch());
		store.endSnapshot();
		assertTrue(store.promote(contact, 0));
		assertEquals(1, store.load(contact).getEpoch());
	}

	@Test
	public void theOutOfSyncMarkIsStoredAndCleared() throws Exception {
		RootKeyStore store = new RootKeyStore(db);
		assertFalse(store.isOutOfSync(contact));
		store.markOutOfSync(contact, true);
		assertTrue(store.isOutOfSync(contact));
		db.close();
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, new NoBus(),
				messageFactory);
		store = new RootKeyStore(db);
		assertTrue(store.isOutOfSync(contact));
		store.markOutOfSync(contact, false);
		assertFalse(store.isOutOfSync(contact));
	}

	@Test
	public void evolutionPausesUntilTheGivenTime() throws Exception {
		RootKeyStore store = new RootKeyStore(db);
		assertFalse(store.isPaused(1_000));
		db.transaction(false, txn -> store.pauseUntil(txn, 5_000));
		assertTrue(store.isPaused(1_000));
		assertFalse(store.isPaused(5_000));
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
