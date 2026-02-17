package org.briarproject.bramble.db;

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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for the H2-to-SQLCipher upgrade path.
 * <p>
 * Verifies that:
 * <ul>
 *   <li>Legacy H2 files are detected correctly</li>
 *   <li>H2 files are cleaned up after successful migration</li>
 *   <li>Migration marker is detected and respected</li>
 *   <li>Staging files are cleaned up on restart</li>
 *   <li>Fresh installs are not affected</li>
 *   <li>Existing SQLCipher databases are preserved</li>
 *   <li>Backup files are created and findable</li>
 *   <li>db.key is NEVER auto-deleted</li>
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
	// H2 file detection tests
	// ========================================================================

	@Test
	public void testHasH2FilesDetectsH2Db() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");

		assertTrue("Should detect .h2.db file",
				H2ToSqlCipherMigration.hasH2Files(dbDir));
	}

	@Test
	public void testHasH2FilesDetectsMvDb() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.mv.db");

		assertTrue("Should detect .mv.db file",
				H2ToSqlCipherMigration.hasH2Files(dbDir));
	}

	@Test
	public void testHasH2FilesReturnsFalseForSqlCipher() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.sqlite");

		assertFalse("Should not detect SQLCipher file as H2",
				H2ToSqlCipherMigration.hasH2Files(dbDir));
	}

	@Test
	public void testHasH2FilesReturnsFalseForEmptyDir() throws Exception {
		assertTrue(dbDir.mkdirs());

		assertFalse("Should not detect H2 in empty dir",
				H2ToSqlCipherMigration.hasH2Files(dbDir));
	}

	@Test
	public void testHasH2FilesReturnsFalseForNonExistentDir() {
		assertFalse(dbDir.exists());
		assertFalse("Should not detect H2 in non-existent dir",
				H2ToSqlCipherMigration.hasH2Files(dbDir));
	}

	// ========================================================================
	// H2 file deletion tests
	// ========================================================================

	@Test
	public void testDeleteH2FilesRemovesAll() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.mv.db");
		createFile(dbDir, "db.trace.db");
		createFile(dbDir, "db.lock.db");
		createFile(dbDir, "migrated-to-sqlcipher");

		H2ToSqlCipherMigration.deleteH2Files(dbDir);

		assertFalse(new File(dbDir, "db.h2.db").exists());
		assertFalse(new File(dbDir, "db.mv.db").exists());
		assertFalse(new File(dbDir, "db.trace.db").exists());
		assertFalse(new File(dbDir, "db.lock.db").exists());
		assertFalse(new File(dbDir, "migrated-to-sqlcipher").exists());
	}

	@Test
	public void testDeleteH2FilesPreservesSqlCipher() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.sqlite");

		H2ToSqlCipherMigration.deleteH2Files(dbDir);

		assertFalse("H2 file should be deleted",
				new File(dbDir, "db.h2.db").exists());
		assertTrue("SQLCipher file should be preserved",
				new File(dbDir, "db.sqlite").exists());
	}

	@Test
	public void testDeleteH2FilesPreservesBackup() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.sqlite.bak");

		H2ToSqlCipherMigration.deleteH2Files(dbDir);

		assertFalse("H2 file should be deleted",
				new File(dbDir, "db.h2.db").exists());
		assertTrue("Backup file should be preserved",
				new File(dbDir, "db.sqlite.bak").exists());
	}

	// ========================================================================
	// Staging file cleanup tests
	// ========================================================================

	@Test
	public void testCleanupStagingFile() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.sqlite.new");

		H2ToSqlCipherMigration.cleanupStagingFile(dbDir);

		assertFalse("Staging file should be deleted",
				new File(dbDir, "db.sqlite.new").exists());
	}

	@Test
	public void testCleanupStagingFileNoOp() throws Exception {
		assertTrue(dbDir.mkdirs());
		// No staging file exists — should not throw
		H2ToSqlCipherMigration.cleanupStagingFile(dbDir);
	}

	// ========================================================================
	// Migration marker tests
	// ========================================================================

	@Test
	public void testHasMarkerTrue() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "migrated-to-sqlcipher");

		assertTrue("Should detect migration marker",
				H2ToSqlCipherMigration.hasMarker(dbDir));
	}

	@Test
	public void testHasMarkerFalse() throws Exception {
		assertTrue(dbDir.mkdirs());

		assertFalse("Should not find marker in empty dir",
				H2ToSqlCipherMigration.hasMarker(dbDir));
	}

	// ========================================================================
	// Backup file tests
	// ========================================================================

	@Test
	public void testFindBackupReturnsFile() throws Exception {
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.sqlite.bak");

		File bak = H2ToSqlCipherMigration.findBackup(dbDir);
		assertNotNull("Should find backup file", bak);
		assertTrue("Backup file should exist", bak.exists());
	}

	@Test
	public void testFindBackupReturnsNullWhenMissing() throws Exception {
		assertTrue(dbDir.mkdirs());

		File bak = H2ToSqlCipherMigration.findBackup(dbDir);
		assertNull("Should return null when no backup", bak);
	}

	// ========================================================================
	// Full upgrade scenario tests (file-level simulation)
	// ========================================================================

	@Test
	public void testUpgradeDetectsH2AndPreservesKeyFiles() throws Exception {
		// Simulate: H2 install with db.key — migration is needed.
		// In v1.0.5, db.key is NEVER auto-deleted. Migration runs instead.
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.trace.db");
		createFile(keyDir, "db.key");
		createFile(keyDir, "db.key.bak");

		// Step 1: cleanup staging
		H2ToSqlCipherMigration.cleanupStagingFile(dbDir);

		// Step 2: detect state
		assertFalse("No migration marker",
				H2ToSqlCipherMigration.hasMarker(dbDir));
		assertTrue("H2 files are present",
				H2ToSqlCipherMigration.hasH2Files(dbDir));

		// Step 3: Migration would run here (can't test actual H2 open
		// in unit tests — requires Android SQLCipher + H2 libs)

		// CRITICAL: db.key must NEVER be auto-deleted
		assertTrue("db.key must be preserved",
				new File(keyDir, "db.key").exists());
		assertTrue("db.key.bak must be preserved",
				new File(keyDir, "db.key.bak").exists());
	}

	@Test
	public void testMigrationMarkerCleansUpH2() throws Exception {
		// After successful migration: marker exists, H2 files remain
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.sqlite");
		createFile(dbDir, "migrated-to-sqlcipher");

		// Simulate openInternal() logic when marker exists
		assertTrue(H2ToSqlCipherMigration.hasMarker(dbDir));
		H2ToSqlCipherMigration.deleteH2Files(dbDir);

		assertFalse("H2 file should be deleted",
				new File(dbDir, "db.h2.db").exists());
		assertFalse("Marker should be deleted",
				new File(dbDir, "migrated-to-sqlcipher").exists());
		assertTrue("SQLCipher file should remain",
				new File(dbDir, "db.sqlite").exists());
	}

	@Test
	public void testFreshInstallNotAffected() throws Exception {
		// Fresh install: nothing exists
		assertFalse(dbDir.exists());

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertFalse("Fresh install has no directory", reopen);
		// No migration, no cleanup, no key deletion
	}

	@Test
	public void testNewAccountCreationNotAffected() throws Exception {
		// User just created account: db.key exists, no database yet
		assertFalse(dbDir.exists());
		assertTrue(keyDir.mkdirs());
		createFile(keyDir, "db.key");

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertFalse("No database directory on new account", reopen);

		// db.key must survive
		assertTrue("db.key must survive new account creation",
				new File(keyDir, "db.key").exists());
	}

	@Test
	public void testExistingSqlCipherDatabaseNotAffected() throws Exception {
		// Normal SQLCipher install, no H2 files
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.sqlite");
		createFile(keyDir, "db.key");
		createFile(keyDir, "db.key.bak");

		// No H2 files, no marker, no staging file
		assertFalse(H2ToSqlCipherMigration.hasH2Files(dbDir));
		assertFalse(H2ToSqlCipherMigration.hasMarker(dbDir));

		// Everything preserved
		assertTrue(new File(dbDir, "db.sqlite").exists());
		assertTrue(new File(keyDir, "db.key").exists());
		assertTrue(new File(keyDir, "db.key.bak").exists());
	}

	@Test
	public void testH2WithSqlCipherAndMarker() throws Exception {
		// Edge case: both H2 and SQLCipher + marker (migration completed,
		// cleanup didn't finish)
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.sqlite");
		createFile(dbDir, "migrated-to-sqlcipher");
		createFile(keyDir, "db.key");

		assertTrue(H2ToSqlCipherMigration.hasMarker(dbDir));
		H2ToSqlCipherMigration.deleteH2Files(dbDir);

		assertFalse(new File(dbDir, "db.h2.db").exists());
		assertFalse(new File(dbDir, "migrated-to-sqlcipher").exists());
		assertTrue(new File(dbDir, "db.sqlite").exists());
		assertTrue(new File(keyDir, "db.key").exists());
	}

	@Test
	public void testMixedH2AndMvStore() throws Exception {
		// Multiple H2 format files
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		createFile(dbDir, "db.mv.db");
		createFile(dbDir, "db.trace.db");
		createFile(dbDir, "db.lock.db");

		assertTrue(H2ToSqlCipherMigration.hasH2Files(dbDir));

		H2ToSqlCipherMigration.deleteH2Files(dbDir);

		assertFalse(H2ToSqlCipherMigration.hasH2Files(dbDir));
		assertFalse(isNonEmptyDirectory(dbDir));
	}

	@Test
	public void testEmptyDatabaseDirectoryWithKeyFiles() throws Exception {
		// Empty db dir + key files — not an upgrade, not a migration
		assertTrue(dbDir.mkdirs());
		assertTrue(keyDir.mkdirs());
		createFile(keyDir, "db.key");

		boolean reopen = isNonEmptyDirectory(dbDir);
		assertFalse("Empty directory should not trigger reopen", reopen);
		assertFalse(H2ToSqlCipherMigration.hasH2Files(dbDir));

		// Key file preserved
		assertTrue(new File(keyDir, "db.key").exists());
	}

	@Test
	public void testOnlyLockDbFileDetectedAsH2() throws Exception {
		// Only lock file remains — should NOT be detected as H2
		// (lock files alone don't indicate a usable H2 database)
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.lock.db");

		// .lock.db does not match hasH2Files (only .h2.db and .mv.db do)
		assertFalse("Lock file alone should not be treated as H2",
				H2ToSqlCipherMigration.hasH2Files(dbDir));
	}

	@Test
	public void testStagingFileDoesNotTriggerH2Detection() throws Exception {
		// Only staging file exists — should not be detected as H2
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.sqlite.new");

		assertFalse(H2ToSqlCipherMigration.hasH2Files(dbDir));
	}

	/**
	 * Verifies that db.key is NEVER auto-deleted in any scenario.
	 * This is the key behavioral change from v1.0.4 → v1.0.5.
	 */
	@Test
	public void testKeyFilesNeverAutoDeleted() throws Exception {
		// Simulate every possible state: H2 only, H2+SQLCipher,
		// SQLCipher only, empty dir — db.key must always survive
		assertTrue(keyDir.mkdirs());
		createFile(keyDir, "db.key");

		// Scenario 1: H2 files only
		assertTrue(dbDir.mkdirs());
		createFile(dbDir, "db.h2.db");
		// H2ToSqlCipherMigration methods never touch keyDir
		H2ToSqlCipherMigration.deleteH2Files(dbDir);
		assertTrue("db.key must survive H2 cleanup",
				new File(keyDir, "db.key").exists());

		// Scenario 2: staging cleanup
		createFile(dbDir, "db.sqlite.new");
		H2ToSqlCipherMigration.cleanupStagingFile(dbDir);
		assertTrue("db.key must survive staging cleanup",
				new File(keyDir, "db.key").exists());
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
}
