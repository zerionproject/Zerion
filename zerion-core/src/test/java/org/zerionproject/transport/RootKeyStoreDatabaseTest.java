package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.PcsSessionRow;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.db.HyperSqlDatabaseForTests;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

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

		PcsSessionRow memoRow = db.transactionWithResult(true, txn ->
				db.getPcsMode2SessionState(txn, contact, PCS_DIRECTION_SEND));
		assertArrayEquals("the pairing root wrapping voice memos is kept",
				pairingRoot.getBytes(), memoRow.getRootKey());

		db.close();
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, new NoBus(),
				messageFactory);
		keys = new RootKeyStore(db).load(contact);
		assertNotNull(keys);
		assertEquals(1, keys.getEpoch());
		assertArrayEquals(first.getBytes(), keys.getCurrent().getBytes());
	}

	@Test
	public void aSeparatedPendingRootKeepsItsConfirmKeyAcrossARestart()
			throws Exception {
		RootKeyStore store = new RootKeyStore(db);
		SecretKey root = getSecretKey();
		SecretKey confirmKey = getSecretKey();
		SecretKey idKey = getSecretKey();
		assertTrue(store.storePending(contact, 0, root, confirmKey, idKey,
				true));
		db.close();
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, new NoBus(),
				messageFactory);
		store = new RootKeyStore(db);
		ContactRootKeys keys = store.load(contact);
		assertNotNull(keys);
		assertTrue(keys.isPendingSeparated());
		assertTrue(keys.isPendingConfirmed());
		assertArrayEquals(root.getBytes(), keys.getPending().getBytes());
		assertArrayEquals(confirmKey.getBytes(),
				keys.getPendingConfirmKey().getBytes());
		assertArrayEquals("the pending-id key survives a restart",
				idKey.getBytes(), keys.getPendingIdKey().getBytes());
		PcsSessionRow row = db.transactionWithResult(true, txn ->
				db.getPcsMode2SessionState(txn, contact,
						DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING));
		assertArrayEquals(confirmKey.getBytes(), row.getChainKey());
		assertArrayEquals(idKey.getBytes(), row.getDhPrivateKey());
		assertTrue(store.promote(contact, 0));
		keys = store.load(contact);
		assertEquals(1, keys.getEpoch());
		assertArrayEquals(root.getBytes(), keys.getCurrent().getBytes());
		assertNull(keys.getPending());
		assertNull(keys.getPendingConfirmKey());
		assertNull(keys.getPendingIdKey());
		row = db.transactionWithResult(true, txn ->
				db.getPcsMode2SessionState(txn, contact,
						DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT));
		assertNull("the promoted root carries no id key", row.getDhPrivateKey());

		SecretKey unproved = getSecretKey();
		assertTrue(store.storePending(contact, 1, unproved, getSecretKey(),
				getSecretKey(), false));
		keys = store.load(contact);
		assertTrue(keys.isPendingSeparated());
		assertNotNull(keys.getPendingIdKey());
		assertFalse(keys.isPendingConfirmed());
		assertTrue(store.storePending(contact, 1, getSecretKey(), false));
		keys = store.load(contact);
		assertFalse("a version 2 pending root replaces the confirm key",
				keys.isPendingSeparated());
		assertNull(keys.getPendingIdKey());
		try {
			store.storePending(contact, 1, getSecretKey(), getSecretKey(),
					null, false);
			org.junit.Assert.fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void aPendingRootStoredByAnEarlierReleaseReadsAsVersionTwo()
			throws Exception {
		SecretKey pending = getSecretKey();
		for (int confirmed = 0; confirmed < 2; confirmed++) {
			int flag = confirmed;
			db.transaction(false, txn -> db.setPcsMode2SessionState(txn,
					contact, DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING,
					pending, 1, flag, pending, null, null, null, true, null));
			ContactRootKeys keys = new RootKeyStore(db).load(contact);
			assertNotNull(keys);
			assertArrayEquals(pending.getBytes(), keys.getPending().getBytes());
			assertFalse(keys.isPendingSeparated());
			assertNull(keys.getPendingConfirmKey());
			assertNull(keys.getPendingIdKey());
			assertEquals(confirmed == 1, keys.isPendingConfirmed());
		}
	}

	@Test
	public void aSeparatedPendingRowWithoutAnIdKeyNamesItsRootUnderTheConfirmKey()
			throws Exception {
		SecretKey pending = getSecretKey();
		SecretKey confirmKey = getSecretKey();
		for (int flags = 2; flags < 4; flags++) {
			int stored = flags;
			db.transaction(false, txn -> db.setPcsMode2SessionState(txn,
					contact, DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING,
					confirmKey, 1, stored, pending, null, null, null, true,
					null));
			ContactRootKeys keys = new RootKeyStore(db).load(contact);
			assertNotNull(keys);
			assertArrayEquals(pending.getBytes(), keys.getPending().getBytes());
			assertTrue("flags " + flags, keys.isPendingSeparated());
			assertEquals(RootEvolutionRecord.VERSION_SEPARATED,
					RootEvolutionCrypto.pendingVersion(keys));
			assertEquals("flags " + flags, flags == 3,
					keys.isPendingConfirmed());
			assertArrayEquals(confirmKey.getBytes(),
					keys.getPendingConfirmKey().getBytes());
			assertNotNull("flags " + flags, keys.getPendingIdKey());
			assertArrayEquals("a row without an id key names its root under"
							+ " another key than its confirm key, flags " + flags,
					confirmKey.getBytes(), keys.getPendingIdKey().getBytes());
		}
	}

	private DatabaseComponent pendingIdKeyReadAs(byte[] idKey) {
		return (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					Object result;
					try {
						result = method.invoke(db, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
					if (!method.getName().equals("getPcsMode2SessionState")
							|| result == null || !Integer.valueOf(
							DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING)
							.equals(args[2])) {
						return result;
					}
					PcsSessionRow row = (PcsSessionRow) result;
					return new PcsSessionRow(row.getChainKey(),
							row.getMessageNumber(),
							row.getPreviousChainLength(), row.getRootKey(),
							idKey.clone(), row.getDhPublicKey(),
							row.getDhRemotePublicKey(), row.isMode2Enabled(),
							row.getMode3FullStateBlob());
				});
	}

	@Test
	public void aSeparatedPendingRowWithAnIdKeyOfTheWrongLengthUsesTheConfirmKey()
			throws Exception {
		SecretKey pending = getSecretKey();
		SecretKey confirmKey = getSecretKey();
		SecretKey idKey = getSecretKey();
		assertTrue(new RootKeyStore(db).storePending(contact, 0, pending,
				confirmKey, idKey, true));
		for (int length : new int[] {0, 1, SecretKey.LENGTH - 1,
				SecretKey.LENGTH + 1, 2 * SecretKey.LENGTH}) {
			byte[] misread = new byte[length];
			java.util.Arrays.fill(misread, (byte) 7);
			ContactRootKeys keys = new RootKeyStore(
					pendingIdKeyReadAs(misread)).load(contact);
			assertNotNull("length " + length, keys);
			assertTrue(keys.isPendingSeparated());
			assertTrue(keys.isPendingConfirmed());
			assertArrayEquals(pending.getBytes(), keys.getPending().getBytes());
			assertArrayEquals(confirmKey.getBytes(),
					keys.getPendingConfirmKey().getBytes());
			assertArrayEquals("an id key of " + length + " bytes was used",
					confirmKey.getBytes(), keys.getPendingIdKey().getBytes());
		}
		SecretKey other = getSecretKey();
		ContactRootKeys keys = new RootKeyStore(
				pendingIdKeyReadAs(other.getBytes())).load(contact);
		assertNotNull(keys);
		assertArrayEquals("an id key of the right length was not used",
				other.getBytes(), keys.getPendingIdKey().getBytes());
		keys = new RootKeyStore(db).load(contact);
		assertNotNull(keys);
		assertArrayEquals(idKey.getBytes(), keys.getPendingIdKey().getBytes());
	}

	@Test
	public void wipingTheCallersKeysLeavesTheStoredKeysIntact()
			throws Exception {
		RootKeyStore store = new RootKeyStore(db);
		SecretKey root = getSecretKey();
		SecretKey confirmKey = getSecretKey();
		SecretKey idKey = getSecretKey();
		byte[] rootCopy = root.getBytes().clone();
		byte[] confirmCopy = confirmKey.getBytes().clone();
		byte[] idCopy = idKey.getBytes().clone();
		assertTrue(store.storePending(contact, 0, root, confirmKey, idKey,
				true));
		root.clear();
		confirmKey.clear();
		idKey.clear();
		ContactRootKeys keys = store.load(contact);
		assertArrayEquals(rootCopy, keys.getPending().getBytes());
		assertArrayEquals(confirmCopy, keys.getPendingConfirmKey().getBytes());
		assertArrayEquals(idCopy, keys.getPendingIdKey().getBytes());
		keys.clear();
		keys = store.load(contact);
		assertArrayEquals(idCopy, keys.getPendingIdKey().getBytes());
		keys.getPending().clear();
		keys.getCurrent().clear();
		assertTrue(store.promote(contact, 0));
		keys = store.load(contact);
		assertArrayEquals(rootCopy, keys.getCurrent().getBytes());
		SecretKey pairing = getSecretKey();
		byte[] pairingCopy = pairing.getBytes().clone();
		db.transaction(false, txn -> db.setPcsMode2SessionState(txn, contact,
				PCS_DIRECTION_SEND, pairing, 0, 0, pairing, null, null, null,
				true, null));
		pairing.clear();
		PcsSessionRow row = db.transactionWithResult(true, txn ->
				db.getPcsMode2SessionState(txn, contact, PCS_DIRECTION_SEND));
		assertArrayEquals(pairingCopy, row.getRootKey());
	}

	@Test
	public void wipingALoadedRowLeavesTheStoredRowIntact() throws Exception {
		SecretKey k = getSecretKey();
		byte[] copy = k.getBytes().clone();
		db.transaction(false, txn -> db.setPcsMode2SessionState(txn, contact,
				DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING, k, 1, 0, k,
				null, null, null, true, null));
		PcsSessionRow first = db.transactionWithResult(true, txn ->
				db.getPcsMode2SessionState(txn, contact,
						DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING));
		java.util.Arrays.fill(first.getRootKey(), (byte) 0);
		java.util.Arrays.fill(first.getChainKey(), (byte) 0);
		PcsSessionRow second = db.transactionWithResult(true, txn ->
				db.getPcsMode2SessionState(txn, contact,
						DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING));
		assertArrayEquals(copy, second.getRootKey());
		assertArrayEquals(copy, second.getChainKey());
		RootKeyStore store = new RootKeyStore(db);
		ContactRootKeys keys = store.load(contact);
		keys.getCurrent().clear();
		keys.getPending().clear();
		keys = store.load(contact);
		assertArrayEquals(pairingRoot.getBytes(), keys.getCurrent().getBytes());
		assertArrayEquals(copy, keys.getPending().getBytes());
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
	public void aConnectionEndingAfterItsContactWasRemovedStoresNothing()
			throws Exception {
		RootEvolutionTestBed bed = new RootEvolutionTestBed();
		RootKeyStore store = new RootKeyStore(db);
		RootEvolutionManager manager = new RootEvolutionManager(store,
				bed.crypto, bed.mlKem, () -> 5_000_000L);
		String stalls = RootKeyStore.STALLS_KEY_PREFIX + contact.getInt();
		String since = RootKeyStore.STALLED_SINCE_KEY_PREFIX + contact.getInt();
		String notRenewing =
				RootKeyStore.NOT_RENEWING_KEY_PREFIX + contact.getInt();
		assertTrue(store.hasContact(contact));
		store.setPeerEnforces(contact, true);

		RootEvolution counted = new RootEvolution(manager, contact, true);
		counted.start(ZwfControlHandler.eager(p -> {
		}));
		counted.onPeerStreamAuthenticated(0);
		counted.close();
		assertArrayEquals("a connection that ended before the greeting of a"
				+ " contact that is still there was not counted",
				new long[] {1, 5_000_000L}, store.stalls(contact));
		assertTrue(manager.recentlyStalled(contact.getInt()));

		RootEvolution open = new RootEvolution(manager, contact, true);
		open.start(ZwfControlHandler.eager(p -> {
		}));
		open.onPeerStreamAuthenticated(0);
		db.transaction(false, txn -> {
			db.removeContact(txn, contact);
			Settings forgotten = new Settings();
			forgotten.putInt(stalls, 0);
			forgotten.putLong(since, 0);
			forgotten.putBoolean(notRenewing, false);
			db.mergeSettings(txn, forgotten, RootKeyStore.SETTINGS_NAMESPACE);
		});
		manager.forget(contact);
		assertFalse(store.hasContact(contact));
		open.close();
		open.close();

		Settings s = db.transactionWithResult(true, txn ->
				db.getSettings(txn, RootKeyStore.SETTINGS_NAMESPACE));
		assertEquals("a stalled connection was stored for a removed contact",
				0, s.getInt(stalls, -1));
		assertEquals(0, s.getLong(since, -1));
		assertFalse(s.getBoolean(notRenewing, true));
		assertFalse(manager.recentlyStalled(contact.getInt()));
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

	private DatabaseComponent copyStartsFirst(
			AtomicReference<RootKeyStore> racing) {
		return (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					if (method.getName().equals("transactionWithResult")
							&& Boolean.FALSE.equals(args[0])
							&& !racing.get().isSnapshotting()) {
						racing.get().beginSnapshot();
					}
					try {
						return method.invoke(db, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	@Test
	public void aCopyStartingJustBeforeAChangeCommitsStillRefusesIt()
			throws Exception {
		assertTrue(new RootKeyStore(db).storePending(contact, 0,
				getSecretKey(), false));
		AtomicReference<RootKeyStore> racing = new AtomicReference<>();
		RootKeyStore store = new RootKeyStore(copyStartsFirst(racing));
		racing.set(store);
		assertFalse(store.promote(contact, 0));
		store.endSnapshot();
		assertFalse(store.discardPending(contact, 0));
		store.endSnapshot();
		assertFalse(store.storePending(contact, 0, getSecretKey(), false));
		store.endSnapshot();
		ContactRootKeys keys = new RootKeyStore(db).load(contact);
		assertEquals(0, keys.getEpoch());
		assertNotNull(keys.getPending());
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
	public void theRenewalStateIsStoredAndClearedByARenewal()
			throws Exception {
		RootKeyStore store = new RootKeyStore(db);
		assertArrayEquals(new long[] {0, 0}, store.stalls(contact));
		assertFalse(store.isNotRenewing(contact));
		store.setStalls(contact, 4, 1234);
		store.markNotRenewing(contact, true);
		store.markOutOfSync(contact, true);
		db.close();
		db = HyperSqlDatabaseForTests.open(testDir, dbKey, new NoBus(),
				messageFactory);
		store = new RootKeyStore(db);
		assertArrayEquals(new long[] {4, 1234}, store.stalls(contact));
		assertTrue(store.isNotRenewing(contact));
		store.renewed(contact, 5678);
		assertArrayEquals(new long[] {0, 0}, store.stalls(contact));
		assertFalse(store.isNotRenewing(contact));
		assertFalse(store.isOutOfSync(contact));
		assertEquals(5678, store.evolvedAt(contact));
		store.markNotRenewing(contact, true);
		store.markNotRenewing(contact, false);
		assertFalse(store.isNotRenewing(contact));
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
