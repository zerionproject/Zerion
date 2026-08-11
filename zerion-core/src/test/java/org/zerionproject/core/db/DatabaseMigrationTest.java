package org.zerionproject.core.db;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DataTooNewException;
import org.zerionproject.core.api.db.DataTooOldException;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;
import org.zerionproject.core.test.TestUtils;
import org.briarproject.nullsafety.NotNullByDefault;
import org.jmock.Expectations;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.zerionproject.core.db.DatabaseConstants.DB_SETTINGS_NAMESPACE;
import static org.zerionproject.core.db.DatabaseConstants.SCHEMA_VERSION_KEY;
import static org.zerionproject.core.db.JdbcDatabase.CODE_SCHEMA_VERSION;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@NotNullByDefault
public abstract class DatabaseMigrationTest extends BrambleMockTestCase {

	private final File testDir = TestUtils.getTestDirectory();
	@SuppressWarnings("unchecked")
	private final Migration<Connection> migration =
			context.mock(Migration.class, "migration");
	@SuppressWarnings("unchecked")
	private final Migration<Connection> migration1 =
			context.mock(Migration.class, "migration1");

	protected final DatabaseConfig config = new TestDatabaseConfig(testDir);
	protected final MessageFactory messageFactory = new TestMessageFactory();
	protected final SecretKey key = getSecretKey();
	protected final Clock clock = new SystemClock();

	abstract Database<Connection> createDatabase(
			List<Migration<Connection>> migrations) throws Exception;

	@Before
	public void setUp() {
		assertTrue(testDir.mkdirs());
	}

	@After
	public void tearDown() {
		TestUtils.deleteTestDirectory(testDir);
	}

	@Test
	public void testDoesNotRunMigrationsWhenCreatingDatabase()
			throws Exception {
		Database<Connection> db = createDatabase(singletonList(migration));
		assertFalse(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));
		db.close();
	}

	@Test(expected = DbException.class)
	public void testThrowsExceptionIfDataSchemaVersionIsMissing()
			throws Exception {

		Database<Connection> db = createDatabase(asList(migration, migration1));
		assertFalse(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));

		setDataSchemaVersion(db, -1);
		db.close();

		db = createDatabase(asList(migration, migration1));
		db.open(key, null);
	}

	@Test
	public void testDoesNotRunMigrationsIfSchemaVersionsMatch()
			throws Exception {

		Database<Connection> db = createDatabase(asList(migration, migration1));
		assertFalse(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));
		db.close();

		db = createDatabase(asList(migration, migration1));
		assertTrue(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));
		db.close();
	}

	@Test(expected = DataTooNewException.class)
	public void testThrowsExceptionIfDataIsNewerThanCode() throws Exception {

		Database<Connection> db = createDatabase(asList(migration, migration1));
		assertFalse(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));

		setDataSchemaVersion(db, CODE_SCHEMA_VERSION + 1);
		db.close();

		db = createDatabase(asList(migration, migration1));
		db.open(key, null);
	}

	@Test(expected = DataTooOldException.class)
	public void testThrowsExceptionIfCodeIsNewerThanDataAndNoMigrations()
			throws Exception {

		Database<Connection> db = createDatabase(emptyList());
		assertFalse(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));
		setDataSchemaVersion(db, CODE_SCHEMA_VERSION - 1);
		db.close();

		db = createDatabase(emptyList());
		db.open(key, null);
	}

	@Test(expected = DataTooOldException.class)
	public void testThrowsExceptionIfCodeIsNewerThanDataAndNoSuitableMigration()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(migration).getStartVersion();
			will(returnValue(CODE_SCHEMA_VERSION - 2));
			oneOf(migration).getEndVersion();
			will(returnValue(CODE_SCHEMA_VERSION - 1));
			oneOf(migration1).getStartVersion();
			will(returnValue(CODE_SCHEMA_VERSION - 1));
			oneOf(migration1).getEndVersion();
			will(returnValue(CODE_SCHEMA_VERSION));
		}});

		Database<Connection> db = createDatabase(asList(migration, migration1));
		assertFalse(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));

		setDataSchemaVersion(db, CODE_SCHEMA_VERSION - 3);
		db.close();

		db = createDatabase(asList(migration, migration1));
		db.open(key, null);
	}

	@Test
	public void testRunsMigrationIfCodeIsNewerThanDataAndSuitableMigration()
			throws Exception {
		context.checking(new Expectations() {{

			oneOf(migration).getStartVersion();
			will(returnValue(CODE_SCHEMA_VERSION - 2));
			oneOf(migration).getEndVersion();
			will(returnValue(CODE_SCHEMA_VERSION));
			oneOf(migration).migrate(with(any(Connection.class)));

			oneOf(migration1).getStartVersion();
			will(returnValue(CODE_SCHEMA_VERSION - 1));
			oneOf(migration1).getEndVersion();
			will(returnValue(CODE_SCHEMA_VERSION));
		}});

		Database<Connection> db = createDatabase(asList(migration, migration1));
		assertFalse(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));

		setDataSchemaVersion(db, CODE_SCHEMA_VERSION - 2);
		db.close();

		db = createDatabase(asList(migration, migration1));
		assertTrue(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));
		db.close();
	}

	@Test
	public void testRunsMigrationsIfCodeIsNewerThanDataAndSuitableMigrations()
			throws Exception {
		context.checking(new Expectations() {{

			oneOf(migration).getStartVersion();
			will(returnValue(CODE_SCHEMA_VERSION - 2));
			oneOf(migration).getEndVersion();
			will(returnValue(CODE_SCHEMA_VERSION - 1));
			oneOf(migration).migrate(with(any(Connection.class)));

			oneOf(migration1).getStartVersion();
			will(returnValue(CODE_SCHEMA_VERSION - 1));
			oneOf(migration1).getEndVersion();
			will(returnValue(CODE_SCHEMA_VERSION));
			oneOf(migration1).migrate(with(any(Connection.class)));
		}});

		Database<Connection> db = createDatabase(asList(migration, migration1));
		assertFalse(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));

		setDataSchemaVersion(db, CODE_SCHEMA_VERSION - 2);
		db.close();

		db = createDatabase(asList(migration, migration1));
		assertTrue(db.open(key, null));
		assertEquals(CODE_SCHEMA_VERSION, getDataSchemaVersion(db));
		db.close();
	}

	private int getDataSchemaVersion(Database<Connection> db)
			throws Exception {
		Connection txn = db.startTransaction();
		Settings s = db.getSettings(txn, DB_SETTINGS_NAMESPACE);
		db.commitTransaction(txn);
		return s.getInt(SCHEMA_VERSION_KEY, -1);
	}

	private void setDataSchemaVersion(Database<Connection> db, int version)
			throws Exception {
		Settings s = new Settings();
		s.putInt(SCHEMA_VERSION_KEY, version);
		Connection txn = db.startTransaction();
		db.mergeSettings(txn, s, DB_SETTINGS_NAMESPACE);
		db.commitTransaction(txn);
	}
}
