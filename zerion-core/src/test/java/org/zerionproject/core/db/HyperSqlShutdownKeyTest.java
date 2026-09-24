package org.zerionproject.core.db;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestDatabaseConfig;
import org.zerionproject.core.test.TestMessageFactory;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;
import static org.zerionproject.core.test.TestUtils.isCryptoStrengthUnlimited;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * STO-02: the database must complete its close-time dirty-flag write while
 * valid key material is available, even though the account manager zeroes
 * its own copy of the key before the lifecycle manager closes the database,
 * and must clear only its own key copy afterwards.
 */
public class HyperSqlShutdownKeyTest extends BrambleTestCase {

	private final File testDir = getTestDirectory();

	@Before
	public void setUp() {
		assumeTrue(isCryptoStrengthUnlimited());
		assertTrue(testDir.mkdirs());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private HyperSqlDatabase database() {
		return new HyperSqlDatabase(new TestDatabaseConfig(testDir),
				new TestMessageFactory(), new SystemClock());
	}

	private static boolean allZero(byte[] b) {
		for (byte x : b) if (x != 0) return false;
		return true;
	}

	@Test
	public void closeSucceedsAndClearsDirtyAfterCallerZeroesItsKey()
			throws Exception {
		SecretKey callerKey = getSecretKey();
		byte[] original = callerKey.getBytes().clone();
		HyperSqlDatabase db = database();
		db.open(callerKey, null);
		assertFalse("the database must not zero the caller's key at open",
				allZero(callerKey.getBytes()));

		callerKey.clear();
		db.close();
		assertFalse("the database releases its own key copy after close",
				db.holdsKey());

		HyperSqlDatabase again = database();
		again.open(new SecretKey(original), null);
		assertFalse("a clean close leaves the database not dirty",
				again.wasDirtyOnInitialisation());
		again.close();
	}

	@Test
	public void closeIsIdempotent() throws Exception {
		HyperSqlDatabase db = database();
		db.open(getSecretKey(), null);
		db.close();
		db.close();
		assertFalse(db.holdsKey());
	}

	@Test
	public void closeAfterFailedOpenDoesNotThrow() throws Exception {
		SecretKey right = getSecretKey();
		HyperSqlDatabase db = database();
		db.open(right, null);
		db.close();

		HyperSqlDatabase wrong = database();
		try {
			wrong.open(getSecretKey(), null);
		} catch (DbException expected) {
		}
		wrong.close();
		assertFalse("a partial open leaves no key behind", wrong.holdsKey());
	}

	@Test
	public void closeAfterAbortedTransactionStillClearsDirty()
			throws Exception {
		SecretKey key = getSecretKey();
		byte[] original = key.getBytes().clone();
		HyperSqlDatabase db = database();
		db.open(key, null);
		Connection txn = db.startTransaction();
		db.abortTransaction(txn);
		key.clear();
		db.close();

		HyperSqlDatabase again = database();
		again.open(new SecretKey(original), null);
		assertFalse(again.wasDirtyOnInitialisation());
		again.close();
	}

	@Test
	public void concurrentCloseIsSafe() throws Exception {
		SecretKey key = getSecretKey();
		byte[] original = key.getBytes().clone();
		HyperSqlDatabase db = database();
		db.open(key, null);
		key.clear();

		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		for (int i = 0; i < threads; i++) {
			new Thread(() -> {
				try {
					start.await();
					db.close();
				} catch (Throwable t) {
					failure.compareAndSet(null, t);
				} finally {
					done.countDown();
				}
			}).start();
		}
		start.countDown();
		done.await();
		assertNull("no closer may fail", failure.get());
		assertFalse(db.holdsKey());

		HyperSqlDatabase again = database();
		again.open(new SecretKey(original), null);
		assertFalse(again.wasDirtyOnInitialisation());
		again.close();
	}
}
