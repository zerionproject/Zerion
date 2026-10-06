package org.zerionproject.core.db;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.identity.Identity;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getIdentity;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.test.TestUtils.isCryptoStrengthUnlimited;

public class GroupAndCapabilityForeignKeyMigrationTest
		extends BrambleTestCase {

	private static final String[] TABLES_WITHOUT_FOREIGN_KEYS = {
			"CREATE TABLE contactCapabilities"
					+ " (contactId INT NOT NULL PRIMARY KEY,"
					+ " capability INTEGER NOT NULL,"
					+ " advertisedAt BIGINT NOT NULL)",
			"CREATE TABLE groupSenderKeys"
					+ " (groupId BINARY(32) NOT NULL,"
					+ " authorId BINARY(32) NOT NULL,"
					+ " chainKey BINARY(32) NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " createdAt BIGINT NOT NULL,"
					+ " isLocal INTEGER NOT NULL,"
					+ " state INTEGER NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId))",
			"CREATE TABLE groupKeyHistory"
					+ " (groupId BINARY(32) NOT NULL,"
					+ " authorId BINARY(32) NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " messageKey BINARY(32) NOT NULL,"
					+ " expiresAt BIGINT NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId, epoch, messageIndex))",
			"CREATE TABLE groupCryptoState"
					+ " (groupId BINARY(32) NOT NULL PRIMARY KEY,"
					+ " cryptoMode INTEGER NOT NULL,"
					+ " lastRekeyTime BIGINT NOT NULL,"
					+ " rekeyReason INTEGER,"
					+ " minCapability INTEGER NOT NULL)",
			"CREATE INDEX groupKeyHistoryExpiry"
					+ " ON groupKeyHistory (expiresAt)",
			"CREATE INDEX groupSenderKeysByGroup"
					+ " ON groupSenderKeys (groupId)"
	};

	private static final String[] GROUP_TABLES = {
			"groupSenderKeys", "groupKeyHistory", "groupCryptoState"
	};

	private final File testDir = getTestDirectory();
	private final SecretKey key = getSecretKey();
	private final Identity identity = getIdentity();
	private final Group group = getGroup(getClientId(), 1);
	private final byte[] orphanGroupId = getRandomId();
	private final byte[] authorId = getRandomId();

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
	public void rowsWithoutAParentAreRemovedAndTheParentLinkIsEnforced()
			throws Exception {
		Database<Connection> db = open();
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		ContactId contact = db.addContact(txn, getAuthor(),
				identity.getLocalAuthor().getId(), null, true);
		db.addGroup(txn, group);
		execute(txn, "DROP TABLE groupSenderKeys");
		execute(txn, "DROP TABLE groupKeyHistory");
		execute(txn, "DROP TABLE groupCryptoState");
		execute(txn, "DROP TABLE contactCapabilities");
		for (String sql : TABLES_WITHOUT_FOREIGN_KEYS) execute(txn, sql);
		insertCapability(txn, contact.getInt());
		insertCapability(txn, 77);
		insertGroupRows(txn, group.getId().getBytes());
		insertGroupRows(txn, orphanGroupId);
		execute(txn, "UPDATE settings SET value = '71'"
				+ " WHERE namespace = 'db' AND settingKey = 'schemaVersion'");
		db.commitTransaction(txn);
		db.close();

		db = open();
		txn = db.startTransaction();
		assertEquals("the capability row without a contact is gone", 0,
				count(txn, "SELECT COUNT(*) FROM contactCapabilities"
						+ " WHERE contactId = 77"));
		assertEquals("the contact's capability row is kept", 1,
				count(txn, "SELECT COUNT(*) FROM contactCapabilities"
						+ " WHERE contactId = " + contact.getInt()));
		for (String table : GROUP_TABLES) {
			assertEquals(table + " rows without a group are gone", 0,
					countForGroup(txn, table, orphanGroupId));
			assertEquals(table + " rows of the group are kept", 1,
					countForGroup(txn, table, group.getId().getBytes()));
		}
		assertEquals("the expiry index exists after the upgrade", 1,
				count(txn, "SELECT COUNT(DISTINCT INDEX_NAME)"
						+ " FROM INFORMATION_SCHEMA.SYSTEM_INDEXINFO"
						+ " WHERE INDEX_NAME = 'GROUPKEYHISTORYEXPIRY'"));
		try {
			insertGroupRows(txn, orphanGroupId);
			fail("a row without a group was accepted");
		} catch (SQLException expected) {
		}
		try {
			insertCapability(txn, 78);
			fail("a capability row without a contact was accepted");
		} catch (SQLException expected) {
		}
		db.removeGroup(txn, group.getId());
		for (String table : GROUP_TABLES) {
			assertEquals(table + " rows go with the group", 0,
					countForGroup(txn, table, group.getId().getBytes()));
		}
		db.removeContact(txn, contact);
		assertEquals("the capability row goes with the contact", 0,
				count(txn, "SELECT COUNT(*) FROM contactCapabilities"));
		db.commitTransaction(txn);
		db.close();
	}

	@Test
	public void openingAgainAfterTheVersionIsPutBackChangesNothing()
			throws Exception {
		Database<Connection> db = open();
		Connection txn = db.startTransaction();
		db.addIdentity(txn, identity);
		ContactId contact = db.addContact(txn, getAuthor(),
				identity.getLocalAuthor().getId(), null, true);
		db.addGroup(txn, group);
		insertCapability(txn, contact.getInt());
		insertGroupRows(txn, group.getId().getBytes());
		execute(txn, "UPDATE settings SET value = '71'"
				+ " WHERE namespace = 'db' AND settingKey = 'schemaVersion'");
		db.commitTransaction(txn);
		db.close();

		db = open();
		txn = db.startTransaction();
		assertEquals(1, count(txn, "SELECT COUNT(*) FROM contactCapabilities"));
		for (String table : GROUP_TABLES) {
			assertEquals(1, countForGroup(txn, table,
					group.getId().getBytes()));
		}
		try {
			insertGroupRows(txn, orphanGroupId);
			fail("a row without a group was accepted");
		} catch (SQLException expected) {
		}
		db.commitTransaction(txn);
		db.close();
	}

	private void insertCapability(Connection txn, int contactId)
			throws SQLException {
		try (PreparedStatement ps = txn.prepareStatement(
				"INSERT INTO contactCapabilities"
						+ " (contactId, capability, advertisedAt)"
						+ " VALUES (?, 1, 0)")) {
			ps.setInt(1, contactId);
			ps.executeUpdate();
		}
	}

	private void insertGroupRows(Connection txn, byte[] groupId)
			throws SQLException {
		try (PreparedStatement ps = txn.prepareStatement(
				"INSERT INTO groupSenderKeys (groupId, authorId, chainKey,"
						+ " epoch, messageIndex, createdAt, isLocal, state)"
						+ " VALUES (?, ?, ?, 0, 0, 0, 1, 0)")) {
			ps.setBytes(1, groupId);
			ps.setBytes(2, authorId);
			ps.setBytes(3, getSecretKey().getBytes());
			ps.executeUpdate();
		}
		try (PreparedStatement ps = txn.prepareStatement(
				"INSERT INTO groupKeyHistory (groupId, authorId, epoch,"
						+ " messageIndex, messageKey, expiresAt)"
						+ " VALUES (?, ?, 0, 0, ?, 0)")) {
			ps.setBytes(1, groupId);
			ps.setBytes(2, authorId);
			ps.setBytes(3, getSecretKey().getBytes());
			ps.executeUpdate();
		}
		try (PreparedStatement ps = txn.prepareStatement(
				"INSERT INTO groupCryptoState (groupId, cryptoMode,"
						+ " lastRekeyTime, rekeyReason, minCapability)"
						+ " VALUES (?, 0, 0, NULL, 0)")) {
			ps.setBytes(1, groupId);
			ps.executeUpdate();
		}
	}

	private long countForGroup(Connection txn, String table, byte[] groupId)
			throws SQLException {
		try (PreparedStatement ps = txn.prepareStatement("SELECT COUNT(*)"
				+ " FROM " + table + " WHERE groupId = ?")) {
			ps.setBytes(1, groupId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : -1;
			}
		}
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

	private static long count(Connection txn, String sql) throws Exception {
		try (Statement s = txn.createStatement();
				ResultSet rs = s.executeQuery(sql)) {
			return rs.next() ? rs.getLong(1) : -1;
		}
	}
}
