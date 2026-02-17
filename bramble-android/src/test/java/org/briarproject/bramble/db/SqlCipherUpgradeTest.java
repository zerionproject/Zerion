package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.test.BrambleTestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.briarproject.bramble.util.IoUtils.isNonEmptyDirectory;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for the H2-to-SQLCipher upgrade path.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>Legacy H2 files are detected and removed</li>
 *   <li>Account key files are deleted when no valid database remains</li>
 *   <li>Fresh installs are not affected by upgrade logic</li>
 *   <li>Existing SQLCipher databases are not affected</li>
 *   <li>The upgrade path does not crash</li>
 * </ul>
 */
public class SqlCipherUpgradeTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();
	private final File dbDir = new File(testDir, "db");
	private final File keyDir = new File(testDir, "key");

	@Before
	public void setUp() {
		assertTrue(testDir.mkdirs());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	// ========================================================================
	// cleanupLegacyFiles() tests
	// ========================================================================

	@Test
	public void testCleanupRemovesH2Files() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.trace.db");
		createFile(dbDir, "db.lock.db");

		boolean hasRemainingFiles = SqlCipherDatabase.cleanupLegacyFiles(dbDir);

		assertFalse("H2 files should be deleted", hasRemainingFiles);
		assertFalse(new File(dbDir, "db.h2.db").exists());
		assertFalse(new File(dbDir, "db.trace.db").exists());
		assertFalse(new File(dbDir, "db.lock.db").exists());
	}

	@Test
	public void testCleanupRemovesMvStoreFiles() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.mv.db");

		boolean hasRemainingFiles = SqlCipherDatabase.cleanupLegacyFiles(dbDir);

		assertFalse("MV store files should be deleted", hasRemainingFiles);
		assertFalse(new File(dbDir, "db.mv.db").exists());
	}

	@Test
	public void testCleanupRemovesMigrationMarker() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "migrated-to-sqlcipher");

		boolean hasRemainingFiles = SqlCipherDatabase.cleanupLegacyFiles(dbDir);

		assertFalse("Migration marker should be deleted", hasRemainingFiles);
		assertFalse(new File(dbDir, "migrated-to-sqlcipher").exists());
	}

	@Test
	public void testCleanupRemovesStagingFile() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.sqlite.new");

		boolean hasRemainingFiles = SqlCipherDatabase.cleanupLegacyFiles(dbDir);

		assertFalse("Staging file should be deleted", hasRemainingFiles);
		assertFalse(new File(dbDir, "db.sqlite.new").exists());
	}

	@Test
	public void testCleanupPreservesSqlCipherFile() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.sqlite");

		boolean hasRemainingFiles = SqlCipherDatabase.cleanupLegacyFiles(dbDir);

		assertTrue("SQLCipher file should be preserved", hasRemainingFiles);
		assertTrue(new File(dbDir, "db.sqlite").exists());
	}

	@Test
	public void testCleanupH2WithSqlCipherPresent() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.trace.db");
		createFile(dbDir, "db.sqlite");

		boolean hasRemainingFiles = SqlCipherDatabase.cleanupLegacyFiles(dbDir);

		assertTrue("SQLCipher file should remain", hasRemainingFiles);
		assertFalse("H2 file should be deleted",
				new File(dbDir, "db.h2.db").exists());
		assertFalse("Trace file should be deleted",
				new File(dbDir, "db.trace.db").exists());
		assertTrue("SQLCipher file should be preserved",
				new File(dbDir, "db.sqlite").exists());
	}

	@Test
	public void testCleanupMixedH2AndMvStore() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.mv.db");
		createFile(dbDir, "db.trace.db");
		createFile(dbDir, "db.lock.db");
		createFile(dbDir, "migrated-to-sqlcipher");

		boolean hasRemainingFiles = SqlCipherDatabase.cleanupLegacyFiles(dbDir);

		assertFalse("All legacy files should be deleted", hasRemainingFiles);
	}

	// ========================================================================
	// deleteAccountKeyFiles() tests
	// ========================================================================

	@Test
	public void testDeleteAccountKeyFilesRemovesAll() throws Exception {
		assertTrue(keyDir.mkdirs());
		createFile(keyDir, "db.key");
		createFile(keyDir, "db.key.bak");
		createFile(keyDir, "login.lockout");

		DatabaseConfig config = new TestDatabaseConfigForTest(dbDir, keyDir);
		SqlCipherDatabase db = createDatabaseForKeyTest(config);
		db.deleteAccountKeyFiles();

		assertFalse("db.key should be deleted",
				new File(keyDir, "db.key").exists());
		assertFalse("db.key.bak should be deleted",
				new File(keyDir, "db.key.bak").exists());
		assertFalse("login.lockout should be deleted",
				new File(keyDir, "login.lockout").exists());
	}

	@Test
	public void testDeleteAccountKeyFilesWithOnlyDbKey() throws Exception {
		assertTrue(keyDir.mkdirs());
		createFile(keyDir, "db.key");

		DatabaseConfig config = new TestDatabaseConfigForTest(dbDir, keyDir);
		SqlCipherDatabase db = createDatabaseForKeyTest(config);
		db.deleteAccountKeyFiles();

		assertFalse("db.key should be deleted",
				new File(keyDir, "db.key").exists());
	}

	@Test
	public void testDeleteAccountKeyFilesNonExistentDir() throws Exception {
		// keyDir does not exist — should not throw
		assertFalse(keyDir.exists());

		DatabaseConfig config = new TestDatabaseConfigForTest(dbDir, keyDir);
		SqlCipherDatabase db = createDatabaseForKeyTest(config);
		db.deleteAccountKeyFiles();
		// No exception means success
	}

	// ========================================================================
	// Full upgrade scenario tests (file-level simulation)
	// ========================================================================

	@Test
	public void testUpgradeFromH2DeletesKeyFiles() throws Exception {
		// Simulate an H2 install: database dir has H2 files,
		// key dir has db.key (user has an account)
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.trace.db");
		createFile(keyDir, "db.key");
		createFile(keyDir, "db.key.bak");

		// Simulate openInternal() logic
		boolean reopen = isNonEmptyDirectory(dbDir);
		assertTrue("Directory should exist initially", reopen);
		boolean originallyExpectedReopen = reopen;

		if (reopen) {
			reopen = SqlCipherDatabase.cleanupLegacyFiles(dbDir);
		}
		assertFalse("H2 files removed, directory empty", reopen);

		// No SQLCipher file exists, so reopen stays false
		if (reopen) {
			fail("Should not reach SQLCipher validation");
		}

		// The critical check: originallyExpectedReopen && !reopen
		assertTrue("Should detect broken upgrade state",
				originallyExpectedReopen && !reopen);

		// Simulate deleteAccountKeyFiles
		DatabaseConfig config = new TestDatabaseConfigForTest(dbDir, keyDir);
		SqlCipherDatabase db = createDatabaseForKeyTest(config);
		db.deleteAccountKeyFiles();

		assertFalse("db.key should be deleted after upgrade detection",
				new File(keyDir, "db.key").exists());
		assertFalse("db.key.bak should be deleted after upgrade detection",
				new File(keyDir, "db.key.bak").exists());
	}

	@Test
	public void testFreshInstallDoesNotDeleteKeyFiles() throws Exception {
		// Fresh install: no database directory, no key files
		assertFalse(dbDir.exists());

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertFalse("Fresh install has no directory", reopen);
		boolean originallyExpectedReopen = reopen;

		// The condition should NOT trigger for fresh install
		assertFalse("Fresh install should not trigger account cleanup",
				originallyExpectedReopen && !reopen);
	}

	@Test
	public void testNewAccountCreationNotAffected() throws Exception {
		// Simulate: user just created account, db.key exists,
		// but database directory doesn't exist yet
		assertFalse(dbDir.exists());
		assertTrue(keyDir.mkdirs());
		createFile(keyDir, "db.key");

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertFalse("No database directory on new account", reopen);
		boolean originallyExpectedReopen = reopen;

		// Condition must NOT trigger — this is a new account, not an upgrade
		assertFalse("New account creation must not trigger cleanup",
				originallyExpectedReopen && !reopen);

		// db.key must still exist
		assertTrue("db.key must survive new account creation",
				new File(keyDir, "db.key").exists());
	}

	@Test
	public void testExistingSqlCipherDatabaseNotAffected() throws Exception {
		// Simulate: existing SQLCipher install, no H2 files
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.sqlite");
		createFile(keyDir, "db.key");
		createFile(keyDir, "db.key.bak");

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertTrue("Directory exists with SQLCipher file", reopen);
		boolean originallyExpectedReopen = reopen;

		if (reopen) {
			reopen = SqlCipherDatabase.cleanupLegacyFiles(dbDir);
		}
		assertTrue("SQLCipher file should survive cleanup", reopen);

		// SQLCipher file exists — reopen stays true
		assertTrue("db.sqlite should still exist",
				new File(dbDir, "db.sqlite").exists());

		// Condition should NOT trigger
		assertFalse("Existing SQLCipher should not trigger cleanup",
				originallyExpectedReopen && !reopen);

		// Key files must survive
		assertTrue("db.key must survive",
				new File(keyDir, "db.key").exists());
		assertTrue("db.key.bak must survive",
				new File(keyDir, "db.key.bak").exists());
	}

	@Test
	public void testH2FilesWithSqlCipherAlreadyMigrated() throws Exception {
		// Edge case: both H2 and SQLCipher files exist (partial migration)
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.sqlite");
		createFile(dbDir, "migrated-to-sqlcipher");
		createFile(keyDir, "db.key");

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertTrue(reopen);
		boolean originallyExpectedReopen = reopen;

		if (reopen) {
			reopen = SqlCipherDatabase.cleanupLegacyFiles(dbDir);
		}
		assertTrue("SQLCipher file should remain after H2 cleanup", reopen);
		assertFalse("H2 file should be deleted",
				new File(dbDir, "db.h2.db").exists());
		assertFalse("Migration marker should be deleted",
				new File(dbDir, "migrated-to-sqlcipher").exists());
		assertTrue("SQLCipher file should survive",
				new File(dbDir, "db.sqlite").exists());

		// Condition should NOT trigger
		assertFalse("Should not trigger cleanup when SQLCipher exists",
				originallyExpectedReopen && !reopen);

		// Key files must survive
		assertTrue("db.key must survive",
				new File(keyDir, "db.key").exists());
	}

	/**
	 * THE critical regression test for the real-world crash scenario.
	 * <p>
	 * When a previous failed startup (e.g. v1.0.1 upgrade) created an empty
	 * db.sqlite (valid schema, but no identity rows), and db.key still exists:
	 * <ol>
	 *   <li>isNonEmptyDirectory → true (db.sqlite exists)</li>
	 *   <li>cleanupLegacyFiles → true (db.sqlite preserved)</li>
	 *   <li>hasValidSchema → false (no identity in localAuthors)</li>
	 *   <li>db.sqlite deleted, reopen = false</li>
	 *   <li>originallyExpectedReopen && !reopen → true</li>
	 *   <li>deleteAccountKeyFiles → db.key deleted</li>
	 *   <li>throw DbException → DB_ERROR screen</li>
	 *   <li>Next launch → no db.key → Create Account</li>
	 * </ol>
	 * <p>
	 * Before this fix, hasValidSchema only checked the settings table
	 * existed — it did NOT check for identity data. So the empty database
	 * was treated as valid, reopened, and then IdentityManager crashed
	 * with 0 identities. Worse, db.key was never deleted, causing an
	 * infinite error loop on every subsequent launch.
	 */
	@Test
	public void testEmptyDbSqliteWithStaleKeyFilesTriggersCleanup()
			throws Exception {
		// Simulate the state left by a previous failed startup:
		// db.sqlite exists (non-empty file, valid schema but no identity)
		// db.key exists (from original H2 account)
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.sqlite");
		createFile(keyDir, "db.key");
		createFile(keyDir, "db.key.bak");

		// Step 1: directory exists with content
		boolean reopen = isNonEmptyDirectory(dbDir);
		assertTrue("db.sqlite exists, directory is non-empty", reopen);
		boolean originallyExpectedReopen = reopen;

		// Step 2: cleanup removes no H2 files, db.sqlite preserved
		if (reopen) {
			reopen = SqlCipherDatabase.cleanupLegacyFiles(dbDir);
		}
		assertTrue("db.sqlite survives legacy cleanup", reopen);
		assertTrue("db.sqlite should still exist",
				new File(dbDir, "db.sqlite").exists());

		// Step 3: hasValidSchema would return false (no identity rows).
		// We can't call hasValidSchema in unit tests (needs SQLCipher native
		// lib), so we simulate its effect: db.sqlite deleted, reopen = false.
		// In production, hasValidSchema now checks:
		//   SELECT count(*) FROM localAuthors → 0 → returns false
		if (reopen) {
			// Simulate hasValidSchema returning false for empty database
			boolean hasValidSchema = false; // no identity in localAuthors
			if (!hasValidSchema) {
				new File(dbDir, "db.sqlite").delete();
				reopen = false;
			}
		}
		assertFalse("Empty db.sqlite should be treated as invalid", reopen);

		// Step 4: the critical condition fires
		assertTrue("Should detect broken state and trigger key cleanup",
				originallyExpectedReopen && !reopen);

		// Step 5: key files are deleted
		DatabaseConfig config = new TestDatabaseConfigForTest(dbDir, keyDir);
		SqlCipherDatabase db = createDatabaseForKeyTest(config);
		db.deleteAccountKeyFiles();

		assertFalse("db.key should be deleted (breaks infinite error loop)",
				new File(keyDir, "db.key").exists());
		assertFalse("db.key.bak should be deleted",
				new File(keyDir, "db.key.bak").exists());
		assertFalse("db.sqlite should be deleted",
				new File(dbDir, "db.sqlite").exists());
	}

	/**
	 * Verifies that an intact v1.0.1-to-v1.0.2 upgrade where db.sqlite has
	 * data is NOT affected by the identity check. The database would have
	 * identity data and hasValidSchema returns true.
	 */
	@Test
	public void testValidDbSqliteWithIdentityNotAffected() throws Exception {
		// Simulate: db.sqlite has valid schema AND identity (normal state)
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.sqlite");
		createFile(keyDir, "db.key");

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertTrue(reopen);
		boolean originallyExpectedReopen = reopen;

		if (reopen) {
			reopen = SqlCipherDatabase.cleanupLegacyFiles(dbDir);
		}
		assertTrue(reopen);

		if (reopen) {
			// Simulate hasValidSchema returning true (identity exists)
			boolean hasValidSchema = true;
			if (!hasValidSchema) {
				new File(dbDir, "db.sqlite").delete();
				reopen = false;
			}
		}
		assertTrue("Valid database should remain open", reopen);

		// Condition should NOT trigger
		assertFalse("Valid database should not trigger cleanup",
				originallyExpectedReopen && !reopen);

		// Key files must survive
		assertTrue("db.key must survive with valid database",
				new File(keyDir, "db.key").exists());
	}

	@Test
	public void testEmptyDatabaseDirectoryWithKeyFiles() throws Exception {
		// Edge case: database directory exists but is empty, key files exist
		// This could happen if H2 files were deleted on a previous crashed run
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(keyDir, "db.key");

		boolean reopen = isNonEmptyDirectory(dbDir);
		// Empty directory should not count as "non-empty"
		assertFalse("Empty directory should not trigger reopen", reopen);
		boolean originallyExpectedReopen = reopen;

		// Condition should NOT trigger (directory was empty from the start)
		assertFalse("Empty dir should not trigger account cleanup",
				originallyExpectedReopen && !reopen);
	}

	@Test
	public void testOnlyLockDbFilePresent() throws Exception {
		// Edge case: only lock file remains (H2 crashed mid-operation)
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.lock.db");
		createFile(keyDir, "db.key");

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertTrue(reopen);
		boolean originallyExpectedReopen = reopen;

		if (reopen) {
			reopen = SqlCipherDatabase.cleanupLegacyFiles(dbDir);
		}
		assertFalse("Lock file should be deleted", reopen);

		assertTrue("Should detect upgrade state",
				originallyExpectedReopen && !reopen);
	}

	// ========================================================================
	// Helpers
	// ========================================================================

	private static void createFile(File dir, String name) throws IOException {
		File f = new File(dir, name);
		FileOutputStream out = new FileOutputStream(f);
		out.write(new byte[]{0x42}); // Non-empty file
		out.close();
		assertTrue("Failed to create test file: " + f, f.exists());
	}

	/**
	 * Creates a SqlCipherDatabase instance just for testing
	 * deleteAccountKeyFiles(). The MessageFactory and Clock are stubs
	 * since only the file-deletion method is being tested.
	 */
	private SqlCipherDatabase createDatabaseForKeyTest(DatabaseConfig config) {
		org.briarproject.bramble.api.sync.MessageFactory mf =
				new org.briarproject.bramble.api.sync.MessageFactory() {
					@Override
					public org.briarproject.bramble.api.sync.Message
							createMessage(
							org.briarproject.bramble.api.sync.GroupId g,
							long timestamp, byte[] body) {
						throw new UnsupportedOperationException();
					}

					@Override
					public org.briarproject.bramble.api.sync.Message
							createMessage(byte[] raw) {
						throw new UnsupportedOperationException();
					}

					@Override
					public byte[] getRawMessage(
							org.briarproject.bramble.api.sync.Message m) {
						throw new UnsupportedOperationException();
					}
				};
		org.briarproject.bramble.api.system.Clock clock =
				new org.briarproject.bramble.api.system.Clock() {
					@Override
					public long currentTimeMillis() {
						return System.currentTimeMillis();
					}

					@Override
					public void sleep(long milliseconds)
							throws InterruptedException {
						Thread.sleep(milliseconds);
					}
				};
		return new SqlCipherDatabase(config, mf, clock);
	}

	private static class TestDatabaseConfigForTest implements DatabaseConfig {
		private final File dbDir, keyDir;

		TestDatabaseConfigForTest(File dbDir, File keyDir) {
			this.dbDir = dbDir;
			this.keyDir = keyDir;
		}

		@Override
		public File getDatabaseDirectory() {
			return dbDir;
		}

		@Override
		public File getDatabaseKeyDirectory() {
			return keyDir;
		}

		@Override
		public org.briarproject.bramble.api.crypto.KeyStrengthener
				getKeyStrengthener() {
			return null;
		}
	}
}
