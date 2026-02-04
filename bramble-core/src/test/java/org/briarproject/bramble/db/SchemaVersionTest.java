package org.briarproject.bramble.db;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.system.SystemClock;
import org.briarproject.bramble.test.TestDatabaseConfig;
import org.briarproject.bramble.test.TestMessageFactory;
import org.briarproject.bramble.test.TestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.util.List;

import static org.briarproject.bramble.db.DatabaseConstants.DB_SETTINGS_NAMESPACE;
import static org.briarproject.bramble.db.DatabaseConstants.SCHEMA_VERSION_KEY;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Schema version validation tests.
 * <p>
 * These tests ensure:
 * - CODE_SCHEMA_VERSION matches the latest migration
 * - Migration chain has no gaps
 * - Fresh installs create correct schema version
 * <p>
 * IMPORTANT: When adding a new migration, update EXPECTED_SCHEMA_VERSION.
 */
public class SchemaVersionTest {

	/**
	 * Expected schema version after all migrations.
	 * UPDATE THIS when adding new migrations.
	 */
	private static final int EXPECTED_SCHEMA_VERSION = 62;

	/**
	 * First migration version (start of chain).
	 */
	private static final int FIRST_MIGRATION_START = 38;

	private final File testDir = TestUtils.getTestDirectory();
	private final DatabaseConfig config = new TestDatabaseConfig(testDir);
	private final MessageFactory messageFactory = new TestMessageFactory();
	private final SecretKey key = getSecretKey();
	private final Clock clock = new SystemClock();

	private Database<Connection> db;

	@Before
	public void setUp() {
		assertTrue(testDir.mkdirs());
	}

	@After
	public void tearDown() {
		if (db != null) {
			try {
				db.close();
			} catch (DbException e) {
				// Ignore
			}
		}
		TestUtils.deleteTestDirectory(testDir);
	}

	/**
	 * Test 1: CODE_SCHEMA_VERSION must match EXPECTED_SCHEMA_VERSION.
	 * <p>
	 * This catches the bug where CODE_SCHEMA_VERSION is not updated
	 * after adding a new migration.
	 */
	@Test
	public void testCodeSchemaVersionMatchesExpected() {
		assertEquals(
				"CODE_SCHEMA_VERSION must be updated to match latest migration. "
						+ "If you added Migration" + EXPECTED_SCHEMA_VERSION + "_"
						+ (EXPECTED_SCHEMA_VERSION + 1) + ", update EXPECTED_SCHEMA_VERSION in this test.",
				EXPECTED_SCHEMA_VERSION,
				JdbcDatabase.CODE_SCHEMA_VERSION);
	}

	/**
	 * Test 2: Migration chain has no gaps.
	 * <p>
	 * Each migration's end version must equal the next migration's start version.
	 */
	@Test
	public void testMigrationChainHasNoGaps() {
		H2Database h2 = new H2Database(config, messageFactory, clock);
		List<Migration<Connection>> migrations = h2.getMigrations();

		assertTrue("Should have at least one migration", migrations.size() > 0);

		// First migration should start at FIRST_MIGRATION_START
		Migration<Connection> first = migrations.get(0);
		assertEquals("First migration should start at " + FIRST_MIGRATION_START,
				FIRST_MIGRATION_START, first.getStartVersion());

		// Check chain continuity
		for (int i = 0; i < migrations.size() - 1; i++) {
			Migration<Connection> current = migrations.get(i);
			Migration<Connection> next = migrations.get(i + 1);

			assertEquals(
					"Migration chain gap detected: Migration"
							+ current.getStartVersion() + "_" + current.getEndVersion()
							+ " ends at " + current.getEndVersion()
							+ " but next migration starts at " + next.getStartVersion(),
					current.getEndVersion(),
					next.getStartVersion());
		}

		// Last migration should end at CODE_SCHEMA_VERSION
		Migration<Connection> last = migrations.get(migrations.size() - 1);
		assertEquals(
				"Last migration should end at CODE_SCHEMA_VERSION. "
						+ "Either add the missing migration or update CODE_SCHEMA_VERSION.",
				JdbcDatabase.CODE_SCHEMA_VERSION,
				last.getEndVersion());
	}

	/**
	 * Test 3: Fresh database has correct schema version.
	 */
	@Test
	public void testFreshDatabaseHasCorrectSchemaVersion() throws Exception {
		db = createDatabase();
		db.open(key, null);

		int schemaVersion = getDataSchemaVersion(db);
		assertEquals(
				"Fresh database should have schema version " + EXPECTED_SCHEMA_VERSION,
				EXPECTED_SCHEMA_VERSION,
				schemaVersion);
	}

	/**
	 * Test 4: Verify all required tables exist in fresh install.
	 * <p>
	 * This catches the bug where migration adds a table but
	 * createTables() doesn't include it for fresh installs.
	 */
	@Test
	public void testFreshDatabaseHasAllRequiredTables() throws Exception {
		db = createDatabase();
		db.open(key, null);

		// Tables that must exist - add new tables here when adding migrations
		String[] requiredTables = {
				"settings",
				"localAuthors",
				"contacts",
				"contactCapabilities",  // Added in Migration61_62
				"groups",
				"groupMetadata",
				"groupVisibilities",
				"messages",
				"messageMetadata",
				"messageDependencies",
				"offers",
				"statuses",
				"transports",
				"pendingContacts",
				"outgoingKeys",
				"incomingKeys",
				"pcsSessionState",
				"pcsSkippedKeys",
				"pqRatchetState",
				"groupSenderKeys",      // Added in Migration60_61
				"groupKeyHistory",      // Added in Migration60_61
				"groupCryptoState"      // Added in Migration60_61
		};

		Connection txn = db.startTransaction();
		try {
			for (String table : requiredTables) {
				assertTrue(
						"Table '" + table + "' should exist in fresh install. "
								+ "Add it to createTables() in JdbcDatabase.",
						tableExists(txn, table));
			}
		} finally {
			db.commitTransaction(txn);
		}
	}

	/**
	 * Test 5: Verify database can reopen after creation.
	 */
	@Test
	public void testDatabaseReopensCorrectly() throws Exception {
		db = createDatabase();
		db.open(key, null);

		int versionBefore = getDataSchemaVersion(db);

		db.close();
		db = createDatabase();
		assertTrue("Database should reopen", db.open(key, null));

		int versionAfter = getDataSchemaVersion(db);

		assertEquals("Schema version should be preserved after reopen",
				versionBefore, versionAfter);
	}

	private Database<Connection> createDatabase() {
		return new H2Database(config, messageFactory, clock);
	}

	private int getDataSchemaVersion(Database<Connection> db) throws Exception {
		Connection txn = db.startTransaction();
		Settings s = db.getSettings(txn, DB_SETTINGS_NAMESPACE);
		db.commitTransaction(txn);
		return s.getInt(SCHEMA_VERSION_KEY, -1);
	}

	private boolean tableExists(Connection txn, String tableName) {
		try {
			// H2 stores table names in uppercase
			java.sql.ResultSet rs = txn.getMetaData().getTables(null, null,
					tableName.toUpperCase(), new String[]{"TABLE"});
			boolean exists = rs.next();
			rs.close();
			return exists;
		} catch (Exception e) {
			return false;
		}
	}
}
