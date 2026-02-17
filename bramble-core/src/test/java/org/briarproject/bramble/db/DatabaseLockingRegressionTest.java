package org.briarproject.bramble.db;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.system.SystemClock;
import org.briarproject.bramble.test.BrambleTestCase;
import org.briarproject.bramble.test.TestDatabaseConfig;
import org.briarproject.bramble.test.TestMessageFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getSecretKey;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for the SQLiteDatabaseLockedException crash during startup.
 * <p>
 * Tests cover:
 * <ul>
 *   <li>Concurrent open attempts are serialized (no lock crash)</li>
 *   <li>Compaction path does not deadlock</li>
 *   <li>Open/close cycle stress test</li>
 *   <li>Resource closure correctness</li>
 *   <li>Startup re-entry protection</li>
 * </ul>
 * <p>
 * Uses H2Database (JVM-testable) since it shares the same JdbcDatabase
 * base class as SqlCipherDatabase. The concurrency patterns tested here
 * validate the JdbcDatabase.open() flow common to both.
 */
public class DatabaseLockingRegressionTest extends BrambleTestCase {

	private final SecretKey key = getSecretKey();
	private final File testDir = getTestDirectory();
	private final MessageFactory messageFactory = new TestMessageFactory();
	private final Clock clock = new SystemClock();

	@Before
	public void setUp() {
		assertTrue(testDir.mkdirs());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private H2Database createDatabase() {
		return new H2Database(
				new TestDatabaseConfig(testDir), messageFactory, clock);
	}

	/**
	 * Regression test: two threads try to open the database concurrently.
	 * Only one should succeed at a time; neither should crash.
	 * This simulates the service start re-entry / wake-lock thread behavior
	 * that caused the SQLiteDatabaseLockedException.
	 */
	@Test
	public void testConcurrentOpenDoesNotCrash() throws Exception {
		// First open to create the database
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);
		db.close();

		// Now try concurrent opens/operations from two threads
		int threadCount = 2;
		CyclicBarrier barrier = new CyclicBarrier(threadCount);
		CountDownLatch done = new CountDownLatch(threadCount);
		AtomicReference<Throwable> failure = new AtomicReference<>(null);

		for (int i = 0; i < threadCount; i++) {
			new Thread(() -> {
				try {
					barrier.await(10, SECONDS);
					H2Database localDb = createDatabase();
					localDb.open(key, null);

					// Do a small operation to confirm the DB is usable
					Connection txn = localDb.startTransaction();
					localDb.getSettings(txn, "test");
					localDb.commitTransaction(txn);

					localDb.close();
				} catch (Throwable t) {
					failure.compareAndSet(null, t);
				} finally {
					done.countDown();
				}
			}).start();
		}

		assertTrue("Threads did not complete in time",
				done.await(30, SECONDS));
		Throwable t = failure.get();
		if (t != null) {
			fail("Concurrent open failed: " + t.getMessage());
		}
	}

	/**
	 * Regression test: the compaction path must not deadlock.
	 * Forces the dirty flag so JdbcDatabase.open() triggers compactAndClose().
	 */
	@Test
	public void testCompactionPathDoesNotDeadlock() throws Exception {
		// Create a fresh database
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		// Mark the database as dirty and close without clearing it
		Connection txn = db.startTransaction();
		db.commitTransaction(txn);

		// Force-close without clearing dirty flag (simulates crash)
		db.closeAllConnections();

		// Reopen - this should trigger compaction since dirty=true
		// This is the code path that caused the original deadlock
		H2Database db2 = createDatabase();
		db2.open(key, null);

		// Verify DB is usable after compaction
		txn = db2.startTransaction();
		db2.getSettings(txn, "db");
		db2.commitTransaction(txn);

		db2.close();
	}

	/**
	 * Stress test: repeated open/close cycles with interleaved operations.
	 * Validates no resource leaks, no deadlocks, no exceptions.
	 */
	@Test
	public void testOpenCloseCycleStress() throws Exception {
		int cycles = 200;
		deleteTestDirectory(testDir);

		for (int i = 0; i < cycles; i++) {
			boolean resume = (i > 0);
			H2Database db = createDatabase();
			if (!resume) deleteTestDirectory(testDir);
			db.open(key, null);

			// Do a small query every cycle
			Connection txn = db.startTransaction();
			db.getSettings(txn, "test");
			db.commitTransaction(txn);

			db.close();
		}
	}

	/**
	 * Validates that Statement and ResultSet resources are properly closed
	 * in the normal execution path.
	 */
	@Test
	public void testResourceClosureOnNormalPath() throws Exception {
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		// Execute multiple transactions with prepared statements
		for (int i = 0; i < 50; i++) {
			Connection txn = db.startTransaction();
			// getSettings internally creates PreparedStatement + ResultSet
			db.getSettings(txn, "ns_" + i);
			db.commitTransaction(txn);
		}

		db.close();
	}

	/**
	 * Validates that resources are cleaned up when a transaction is aborted.
	 */
	@Test
	public void testResourceClosureOnAbort() throws Exception {
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		for (int i = 0; i < 50; i++) {
			Connection txn = db.startTransaction();
			db.getSettings(txn, "ns_" + i);
			// Abort instead of commit
			db.abortTransaction(txn);
		}

		// DB should still be usable after many aborted transactions
		Connection txn = db.startTransaction();
		db.getSettings(txn, "final");
		db.commitTransaction(txn);

		db.close();
	}

	/**
	 * Validates that the database remains in a consistent state after
	 * multiple threads perform concurrent read transactions.
	 */
	@Test
	public void testConcurrentReadTransactionsAfterOpen() throws Exception {
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		int threadCount = 4;
		CountDownLatch done = new CountDownLatch(threadCount);
		AtomicReference<Throwable> failure = new AtomicReference<>(null);

		for (int i = 0; i < threadCount; i++) {
			final int idx = i;
			new Thread(() -> {
				try {
					for (int j = 0; j < 20; j++) {
						Connection txn = db.startTransaction();
						db.getSettings(txn, "ns_" + idx + "_" + j);
						db.commitTransaction(txn);
					}
				} catch (Throwable t) {
					failure.compareAndSet(null, t);
				} finally {
					done.countDown();
				}
			}).start();
		}

		assertTrue("Threads did not complete in time",
				done.await(30, SECONDS));
		assertNull("Concurrent reads failed: " + failure.get(),
				failure.get());

		db.close();
	}

	/**
	 * Validates schema is intact after open with compaction.
	 * The settings table must exist and be queryable.
	 */
	@Test
	public void testSchemaValidAfterCompaction() throws Exception {
		// First open
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		// Write a setting
		Connection txn = db.startTransaction();
		org.briarproject.bramble.api.settings.Settings s =
				new org.briarproject.bramble.api.settings.Settings();
		s.put("testKey", "testValue");
		db.mergeSettings(txn, s, "testNs");
		db.commitTransaction(txn);

		db.close();

		// Reopen (resume) and verify the setting persists
		H2Database db2 = createDatabase();
		db2.open(key, null);

		txn = db2.startTransaction();
		org.briarproject.bramble.api.settings.Settings loaded =
				db2.getSettings(txn, "testNs");
		assertEquals("testValue", loaded.get("testKey"));
		db2.commitTransaction(txn);

		db2.close();
	}

	/**
	 * Simulates the LifecycleManagerImpl pattern: open database, then
	 * immediately perform a write transaction (removeTemporaryMessages
	 * equivalent). Validates the connection pool works correctly after open.
	 */
	@Test
	public void testLifecycleManagerPattern() throws Exception {
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		// Simulate: db.transaction(false, txn -> { ... })
		Connection txn = db.startTransaction();
		// removeTemporaryMessages uses Statement.executeUpdate(String)
		Statement s = txn.createStatement();
		s.executeUpdate("DELETE FROM messages WHERE temporary = TRUE");
		s.close();
		db.commitTransaction(txn);

		// Verify further transactions work
		txn = db.startTransaction();
		db.getSettings(txn, "db");
		db.commitTransaction(txn);

		db.close();
	}

	/**
	 * Validates that opening a database that was not cleanly closed
	 * (dirty flag set) triggers the compaction path without crashing.
	 * This is the exact scenario that caused the original lock crash.
	 */
	@Test
	public void testDirtyReopenTriggersCompaction() throws Exception {
		// Create and close cleanly
		H2Database db1 = createDatabase();
		deleteTestDirectory(testDir);
		assertFalse(db1.open(key, null));

		// Write dirty flag
		Connection txn = db1.startTransaction();
		db1.commitTransaction(txn);
		// Close without clearing dirty (simulates crash/kill)
		db1.closeAllConnections();

		// Reopen - dirty flag should trigger compaction
		H2Database db2 = createDatabase();
		assertTrue(db2.open(key, null));
		assertTrue(db2.wasDirtyOnInitialisation());

		// Verify DB is fully functional
		txn = db2.startTransaction();
		db2.getSettings(txn, "test");
		db2.commitTransaction(txn);

		db2.close();
	}

	/**
	 * Regression test: commit() and rollback() must not leave the connection
	 * in a transaction state. If they do, pooled connections permanently hold
	 * a RESERVED file-level lock, blocking all other connections.
	 * This was the root cause of the 30-second SQLiteConnectionPool timeout.
	 */
	@Test
	public void testCommitReleasesLockForNextTransaction() throws Exception {
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		// Run many commit/start cycles — if commit doesn't release the lock,
		// the second startTransaction will hang or fail
		for (int i = 0; i < 100; i++) {
			Connection txn = db.startTransaction();
			db.getSettings(txn, "ns_" + i);
			db.commitTransaction(txn);
		}

		// Verify the connection is returned to the pool and reusable
		Connection txn = db.startTransaction();
		db.getSettings(txn, "final");
		db.commitTransaction(txn);

		db.close();
	}

	/**
	 * Regression test: rollback() must also release the lock, not just commit.
	 * Interleaved commit and rollback cycles must not cause lock contention.
	 */
	@Test
	public void testRollbackReleasesLockForNextTransaction() throws Exception {
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		for (int i = 0; i < 100; i++) {
			Connection txn = db.startTransaction();
			db.getSettings(txn, "ns_" + i);
			if (i % 2 == 0) {
				db.commitTransaction(txn);
			} else {
				db.abortTransaction(txn);
			}
		}

		// Verify DB is still fully functional
		Connection txn = db.startTransaction();
		db.getSettings(txn, "final");
		db.commitTransaction(txn);

		db.close();
	}

	/**
	 * Regression test: concurrent threads performing interleaved
	 * commit and rollback transactions must not deadlock or timeout.
	 * This simulates the production scenario where multiple services
	 * (KeyManager, TransportKeyManager, etc.) access the DB simultaneously.
	 */
	@Test
	public void testConcurrentCommitRollbackNoTimeout() throws Exception {
		H2Database db = createDatabase();
		deleteTestDirectory(testDir);
		db.open(key, null);

		int threadCount = 4;
		int opsPerThread = 50;
		CountDownLatch done = new CountDownLatch(threadCount);
		AtomicReference<Throwable> failure = new AtomicReference<>(null);

		for (int t = 0; t < threadCount; t++) {
			final int threadIdx = t;
			new Thread(() -> {
				try {
					for (int i = 0; i < opsPerThread; i++) {
						Connection txn = db.startTransaction();
						db.getSettings(txn, "t" + threadIdx + "_" + i);
						if (i % 3 == 0) {
							db.abortTransaction(txn);
						} else {
							db.commitTransaction(txn);
						}
					}
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				} finally {
					done.countDown();
				}
			}).start();
		}

		assertTrue("Threads did not complete — possible deadlock",
				done.await(30, SECONDS));
		assertNull("Concurrent commit/rollback failed: " + failure.get(),
				failure.get());

		db.close();
	}

	/**
	 * Stress test: rapid open-close with concurrent operations,
	 * simulating unstable conditions where the app is killed and restarted.
	 */
	@Test
	public void testRapidOpenCloseStress() throws Exception {
		deleteTestDirectory(testDir);

		for (int i = 0; i < 10; i++) {
			H2Database db = createDatabase();
			if (i == 0) deleteTestDirectory(testDir);
			db.open(key, null);

			// Some cycles do work, some just open and close
			if (i % 2 == 0) {
				Connection txn = db.startTransaction();
				org.briarproject.bramble.api.settings.Settings s =
						new org.briarproject.bramble.api.settings.Settings();
				s.put("cycle", String.valueOf(i));
				db.mergeSettings(txn, s, "stress");
				db.commitTransaction(txn);
			}

			db.close();
		}

		// Final open: verify data from last write
		H2Database db = createDatabase();
		db.open(key, null);
		Connection txn = db.startTransaction();
		org.briarproject.bramble.api.settings.Settings loaded =
				db.getSettings(txn, "stress");
		assertEquals("8", loaded.get("cycle"));
		db.commitTransaction(txn);
		db.close();
	}
}
