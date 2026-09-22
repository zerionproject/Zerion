package org.zerionproject.core.db;

import org.zerionproject.core.db.SqlCipherOpenPolicy.Action;
import org.zerionproject.core.db.SqlCipherOpenPolicy.FailureClass;
import org.zerionproject.core.db.SqlCipherOpenPolicy.Probe;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.sql.SQLException;

import static org.zerionproject.core.db.SqlCipherOpenPolicy.classify;
import static org.zerionproject.core.db.SqlCipherOpenPolicy.decide;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Fault injection for every failure class an existing database can present
 * at open time. The invariant under test: a failed probe never yields a
 * destructive action unless the setup marker proves that no identity was
 * ever stored, and even then the files are moved, not deleted.
 */
public class SqlCipherOpenPolicyTest {

	private static Throwable chain(String innerClassName, String message) {
		Exception inner = new Exception(message) {
			@Override
			public String toString() {
				return innerClassName;
			}
		};
		return new SQLException("Failed to configure database", inner);
	}

	/**
	 * A file missing one of the expected tables is not provably empty: it
	 * is a failed probe, which never deletes, instead of an empty database
	 * that would be reset.
	 */
	@Test
	public void aMissingTableIsAFailedProbeNotAnEmptyDatabase() {
		assertEquals(Probe.FAILED, SqlCipherOpenPolicy.probe(false, true, 0));
		assertEquals(Probe.FAILED, SqlCipherOpenPolicy.probe(true, false, 0));
		assertEquals(Probe.FAILED, SqlCipherOpenPolicy.probe(true, false, 5));
		assertEquals(Probe.OPENED_WITHOUT_IDENTITY,
				SqlCipherOpenPolicy.probe(true, true, 0));
		assertEquals(Probe.OPENED_WITH_IDENTITY,
				SqlCipherOpenPolicy.probe(true, true, 1));
		assertEquals("never a reset for a missing table",
				Action.FAIL_CLOSED, decide(false,
						SqlCipherOpenPolicy.probe(true, false, 0)));
		assertEquals(Action.QUARANTINE_INCOMPLETE, decide(true,
				SqlCipherOpenPolicy.probe(false, false, 0)));
	}

	@Test
	public void testOpenedWithIdentityIsReusedWhateverTheMarkerSays() {
		assertEquals(Action.REOPEN, decide(false, Probe.OPENED_WITH_IDENTITY));
		assertEquals(Action.REOPEN, decide(true, Probe.OPENED_WITH_IDENTITY));
	}

	@Test
	public void testOpenedWithoutIdentityIsResetOnlyBecauseItIsProvablyEmpty() {
		assertEquals(Action.RESET_EMPTY,
				decide(false, Probe.OPENED_WITHOUT_IDENTITY));
		assertEquals(Action.RESET_EMPTY,
				decide(true, Probe.OPENED_WITHOUT_IDENTITY));
	}

	@Test
	public void testFailedProbeWithoutMarkerFailsClosed() {
		assertEquals(Action.FAIL_CLOSED, decide(false, Probe.FAILED));
	}

	@Test
	public void testFailedProbeWithMarkerQuarantinesInsteadOfDeleting() {
		assertEquals(Action.QUARANTINE_INCOMPLETE, decide(true, Probe.FAILED));
	}

	@Test
	public void testNoFailureClassEverProducesDeletion() {
		for (FailureClass ignored : FailureClass.values()) {
			assertEquals(Action.FAIL_CLOSED, decide(false, Probe.FAILED));
			assertEquals(Action.QUARANTINE_INCOMPLETE,
					decide(true, Probe.FAILED));
		}
	}

	@Test
	public void testClassifiesTransientLock() {
		assertEquals(FailureClass.LOCKED, classify(new SQLException(
				"Database locked after 5 attempts")));
		assertEquals(FailureClass.LOCKED, classify(chain(
				"android.database.sqlite.SQLiteDatabaseLockedException",
				"database is locked (code 5)")));
	}

	@Test
	public void testClassifiesWrongKeyOrHeader() {
		assertEquals(FailureClass.KEY_OR_HEADER, classify(chain(
				"net.sqlcipher.database.SQLiteException",
				"file is not a database: , while compiling: select count(*) from sqlite_master")));
	}

	@Test
	public void testClassifiesCorruption() {
		assertEquals(FailureClass.CORRUPT, classify(chain(
				"android.database.sqlite.SQLiteDatabaseCorruptException",
				"database disk image is malformed (code 11 SQLITE_CORRUPT)")));
	}

	@Test
	public void testClassifiesStorageFull() {
		assertEquals(FailureClass.STORAGE_FULL, classify(chain(
				"android.database.sqlite.SQLiteFullException",
				"database or disk is full (code 13 SQLITE_FULL)")));
	}

	@Test
	public void testClassifiesDiskIo() {
		assertEquals(FailureClass.DISK_IO, classify(chain(
				"android.database.sqlite.SQLiteDiskIOException",
				"disk I/O error (code 10 SQLITE_IOERR)")));
	}

	@Test
	public void testClassifiesMigrationFailures() {
		assertEquals(FailureClass.MIGRATION, classify(chain(
				"org.zerionproject.core.api.db.DataTooNewException", "")));
		assertEquals(FailureClass.MIGRATION, classify(chain(
				"org.zerionproject.core.api.db.DataTooOldException", "")));
	}

	@Test
	public void testUnknownFailuresAreStillNotDestructive() {
		assertEquals(FailureClass.UNKNOWN,
				classify(new SQLException("Failed to open database")));
		assertEquals(FailureClass.UNKNOWN, classify(null));
		assertEquals(Action.FAIL_CLOSED, decide(false, Probe.FAILED));
	}

	@Test
	public void testQuarantineMovesEveryFileAndLeavesNothingBehind()
			throws Exception {
		File dir = Files.createTempDirectory("zerion-db").toFile();
		File db = new File(dir, "db.sqlite");
		for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
			try (FileOutputStream out = new FileOutputStream(
					new File(db.getPath() + suffix))) {
				out.write(suffix.getBytes());
			}
		}
		assertTrue(SqlCipherRecoveryFiles.quarantine(db, 1234L));
		assertFalse(db.exists());
		assertFalse(new File(db.getPath() + "-wal").exists());
		for (String suffix : new String[] {"", "-wal", "-shm", "-journal"}) {
			File moved = new File(db.getPath() + ".incomplete-1234" + suffix);
			assertTrue(moved.getName(), moved.exists());
			assertEquals(suffix,
					new String(Files.readAllBytes(moved.toPath())));
		}
	}

	@Test
	public void testDeleteEmptyRemovesOnlyTheDatabaseFiles() throws Exception {
		File dir = Files.createTempDirectory("zerion-db").toFile();
		File db = new File(dir, "db.sqlite");
		assertTrue(db.createNewFile());
		assertTrue(new File(db.getPath() + "-wal").createNewFile());
		File other = new File(dir, "keep.txt");
		assertTrue(other.createNewFile());
		SqlCipherRecoveryFiles.deleteEmpty(db);
		assertFalse(db.exists());
		assertFalse(new File(db.getPath() + "-wal").exists());
		assertTrue(other.exists());
	}

	@Test
	public void testSetupMarkerLifecycle() throws Exception {
		File dir = Files.createTempDirectory("zerion-db").toFile();
		assertFalse(SqlCipherRecoveryFiles.setupMarker(dir).exists());
		assertTrue(SqlCipherRecoveryFiles.markSetupIncomplete(dir));
		assertTrue(SqlCipherRecoveryFiles.setupMarker(dir).exists());
		assertTrue(SqlCipherRecoveryFiles.markSetupIncomplete(dir));
		SqlCipherRecoveryFiles.markSetupComplete(dir);
		assertFalse(SqlCipherRecoveryFiles.setupMarker(dir).exists());
	}
}
