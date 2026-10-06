package org.zerionproject.core.db;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.transport.IncomingKeys;
import org.zerionproject.core.api.transport.OutgoingKeys;
import org.zerionproject.core.api.transport.TransportKeys;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import static org.zerionproject.core.api.sync.validation.MessageState.DELIVERED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.zerionproject.core.test.TestUtils.isCryptoStrengthUnlimited;

public class ForeignKeyCleanupMigrationTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final SecretKey key = getSecretKey();
	private final Identity identity = getIdentity();
	private final Group group = getGroup(getClientId(), 1);
	private final Message message = getMessage(group.getId());
	private final TransportId transportId = getTransportId();

	@Before
	public void setUp() {
		assumeTrue(isCryptoStrengthUnlimited());
		assertTrue(testDir.mkdirs());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void rowsLeftByARemovalWithoutCascadeAreRemovedOnUpgrade()
			throws Exception {
		Database<Connection> db = open();
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		ContactId kept = db.addContact(txn, getAuthor(),
				identity.getLocalAuthor().getId(), null, true);
		ContactId removed = db.addContact(txn, getAuthor(),
				identity.getLocalAuthor().getId(), null, true);
		db.addGroup(txn, group);
		db.addGroupVisibility(txn, kept, group.getId(), true);
		db.addGroupVisibility(txn, removed, group.getId(), true);
		db.addMessage(txn, message, DELIVERED, true, false, null);
		db.addOfferedMessage(txn, removed, message.getId());
		db.addTransport(txn, transportId, 60_000);
		KeySetId removedKeys = db.addTransportKeys(txn, removed,
				createTransportKeys(1000));
		db.commitTransaction(txn);

		txn = db.startTransaction();
		assertEquals(1, count(txn, "SELECT COUNT(*) FROM statuses"
				+ " WHERE contactId = " + removed.getInt()));
		execute(txn, "SET DATABASE REFERENTIAL INTEGRITY FALSE");
		execute(txn, "DELETE FROM contacts WHERE contactId = "
				+ removed.getInt());
		execute(txn, "INSERT INTO pqRatchetState (contactId,"
				+ " epochStartTime) VALUES (77, 0)");
		execute(txn, "SET DATABASE REFERENTIAL INTEGRITY TRUE");
		execute(txn, "UPDATE settings SET value = '67'"
				+ " WHERE namespace = 'db' AND settingKey = 'schemaVersion'");
		execute(txn, "DELETE FROM settings WHERE namespace = 'db'"
				+ " AND (settingKey = 'contactIdHighWater'"
				+ " OR settingKey = 'keySetIdHighWater')");
		for (String index : INDEXES) execute(txn, "DROP INDEX " + index);
		for (String index : INDEXES) {
			assertEquals("index " + index + " is gone before the migration",
					0, countIndex(txn, index));
		}
		new Migration67_68().migrate(txn);
		for (String index : INDEXES) {
			assertEquals("index " + index + " is created by the migration",
					1, countIndex(txn, index));
		}
		db.commitTransaction(txn);
		db.close();

		db = open();
		txn = db.startTransaction();
		assertEquals("the removed contact's key sets are gone", 0,
				count(txn, "SELECT COUNT(*) FROM outgoingKeys"
						+ " WHERE contactId = " + removed.getInt()));
		KeySetId nextKeys = db.addTransportKeys(txn, kept,
				createTransportKeys(1000));
		assertTrue("the next key set is numbered above the removed one",
				nextKeys.getInt() > removedKeys.getInt());
		assertEquals("the removed contact's statuses are gone", 0,
				count(txn, "SELECT COUNT(*) FROM statuses WHERE contactId = "
						+ removed.getInt()));
		assertEquals("its group visibility is gone", 0,
				count(txn, "SELECT COUNT(*) FROM groupVisibilities"
						+ " WHERE contactId = " + removed.getInt()));
		assertEquals("its offers are gone", 0,
				count(txn, "SELECT COUNT(*) FROM offers WHERE contactId = "
						+ removed.getInt()));
		assertEquals("ratchet state without a contact is gone", 0,
				count(txn, "SELECT COUNT(*) FROM pqRatchetState"
						+ " WHERE contactId = 77"));
		assertEquals("the remaining contact's status is kept", 1,
				count(txn, "SELECT COUNT(*) FROM statuses WHERE contactId = "
						+ kept.getInt()));
		assertEquals("the remaining contact's visibility is kept", 1,
				count(txn, "SELECT COUNT(*) FROM groupVisibilities"
						+ " WHERE contactId = " + kept.getInt()));
		ContactId next = db.addContact(txn, getAuthor(),
				identity.getLocalAuthor().getId(), null, true);
		db.commitTransaction(txn);
		db.close();
		assertTrue("the next contact is numbered above every id the old"
				+ " rows used, got " + next.getInt(), next.getInt() > 77);
	}

	@Test
	public void aRemovedContactsIdIsNotGivenOutAgainAfterAReopen()
			throws Exception {
		Database<Connection> db = open();
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		db.addContact(txn, getAuthor(), identity.getLocalAuthor().getId(),
				null, true);
		ContactId last = db.addContact(txn, getAuthor(),
				identity.getLocalAuthor().getId(), null, true);
		db.removeContact(txn, last);
		db.commitTransaction(txn);
		db.close();

		db = open();
		txn = db.startTransaction();
		ContactId next = db.addContact(txn, getAuthor(),
				identity.getLocalAuthor().getId(), null, true);
		db.commitTransaction(txn);
		db.close();
		assertTrue("got " + next.getInt() + " after removing "
				+ last.getInt(), next.getInt() > last.getInt());
	}

	@Test
	public void aRemovedKeySetsIdIsNotGivenOutAgain() throws Exception {
		Database<Connection> db = open();
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		ContactId c = db.addContact(txn, getAuthor(),
				identity.getLocalAuthor().getId(), null, true);
		db.addTransport(txn, transportId, 60_000);
		db.addTransportKeys(txn, c, createTransportKeys(1000));
		KeySetId last = db.addTransportKeys(txn, c, createTransportKeys(1001));
		db.removeTransportKeys(txn, transportId, last);
		db.commitTransaction(txn);
		db.close();

		db = open();
		txn = db.startTransaction();
		KeySetId next = db.addTransportKeys(txn, c, createTransportKeys(1002));
		db.commitTransaction(txn);
		db.close();
		assertTrue("got " + next.getInt() + " after removing "
				+ last.getInt(), next.getInt() > last.getInt());
	}

	private static final String[] INDEXES = {
			"CONTACTSBYLOCALAUTHORID", "GROUPVISIBILITIESBYGROUPID",
			"MESSAGESBYGROUPID", "MESSAGEDEPENDENCIESBYMESSAGEID",
			"MESSAGEDEPENDENCIESBYGROUPID", "OFFERSBYCONTACTID",
			"STATUSESBYGROUPID", "OUTGOINGKEYSBYCONTACTID",
			"OUTGOINGKEYSBYPENDINGCONTACTID", "INCOMINGKEYSBYKEYSETID"
	};

	private TransportKeys createTransportKeys(long timePeriod) {
		IncomingKeys inPrev = new IncomingKeys(getSecretKey(), getSecretKey(),
				timePeriod - 1, 123, new byte[4]);
		IncomingKeys inCurr = new IncomingKeys(getSecretKey(), getSecretKey(),
				timePeriod, 234, new byte[4]);
		IncomingKeys inNext = new IncomingKeys(getSecretKey(), getSecretKey(),
				timePeriod + 1, 345, new byte[4]);
		OutgoingKeys outCurr = new OutgoingKeys(getSecretKey(), getSecretKey(),
				timePeriod, 456, true);
		return new TransportKeys(transportId, inPrev, inCurr, inNext, outCurr);
	}

	private Database<Connection> open() throws Exception {
		Database<Connection> db = new HyperSqlDatabase(
				new TestDatabaseConfig(testDir), new TestMessageFactory(),
				new SystemClock());
		db.open(key, null);
		return db;
	}

	private static void execute(Connection txn, String sql) throws Exception {
		try (Statement s = txn.createStatement()) {
			s.execute(sql);
		}
	}

	private static long countIndex(Connection txn, String index)
			throws Exception {
		return count(txn, "SELECT COUNT(DISTINCT INDEX_NAME)"
				+ " FROM INFORMATION_SCHEMA.SYSTEM_INDEXINFO"
				+ " WHERE INDEX_NAME = '" + index + "'");
	}

	private static long count(Connection txn, String sql) throws Exception {
		try (Statement s = txn.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : -1;
		}
	}
}
