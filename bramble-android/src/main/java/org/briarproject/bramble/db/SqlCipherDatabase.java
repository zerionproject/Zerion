package org.briarproject.bramble.db;

import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbClosedException;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.MigrationListener;
import org.briarproject.bramble.api.sync.MessageFactory;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.briarproject.bramble.util.IoUtils.isNonEmptyDirectory;

@NotNullByDefault
class SqlCipherDatabase extends JdbcDatabase {

	private static final String HASH_TYPE = "BLOB";
	private static final String SECRET_TYPE = "BLOB";
	private static final String BINARY_TYPE = "BLOB";
	private static final String COUNTER_TYPE = "INTEGER";
	private static final String STRING_TYPE = "TEXT";
	private static final DatabaseTypes dbTypes = new DatabaseTypes(HASH_TYPE,
			SECRET_TYPE, BINARY_TYPE, COUNTER_TYPE, STRING_TYPE);

	private static final String DRIVER_CLASS =
			"org.briarproject.bramble.db.SqlCipherDriver";

	// Process-wide lock: only one thread may open/migrate/compact at a time
	private static final Object DB_OPEN_LOCK = new Object();

	private static final String SQLCIPHER_FILE = "db.sqlite";
	private static final String SQLCIPHER_STAGING_FILE = "db.sqlite.new";
	private static final String MIGRATION_MARKER = "migrated-to-sqlcipher";

	private static final int BUSY_TIMEOUT_MS = 5000;
	private static final int OPEN_RETRY_MAX = 5;
	private static final long OPEN_RETRY_BASE_MS = 100;

	private final DatabaseConfig config;

	// Flag: true once open() has completed; compaction deferred until later
	private volatile boolean needsCompaction = false;

	@Nullable
	private volatile SecretKey key = null;

	@Inject
	SqlCipherDatabase(DatabaseConfig config, MessageFactory messageFactory,
			Clock clock) {
		super(dbTypes, messageFactory, clock);
		this.config = config;
	}

	@Override
	public boolean open(SecretKey key, @Nullable MigrationListener listener)
			throws DbException {
		synchronized (DB_OPEN_LOCK) {
			return openInternal(key, listener);
		}
	}

	private boolean openInternal(SecretKey key,
			@Nullable MigrationListener listener) throws DbException {
		this.key = key;
		try {
			System.loadLibrary("sqlcipher");
		} catch (UnsatisfiedLinkError e) {
			throw new DbException(e);
		}

		File dir = config.getDatabaseDirectory();
		boolean reopen = isNonEmptyDirectory(dir);
		// Track whether the directory originally existed with content.
		// If it did but no valid SQLCipher database remains after cleanup,
		// the account key files reference a database that no longer exists.
		boolean originallyExpectedReopen = reopen;

		if (reopen) {
			reopen = cleanupLegacyFiles(dir);
		}

		if (reopen) {
			File dbFile = new File(dir, SQLCIPHER_FILE);
			if (!dbFile.exists()) {
				reopen = false;
			} else if (!hasValidSchema(dbFile, key)) {
				dbFile.delete();
				reopen = false;
			}
		}

		// If the database directory existed but no valid database remains
		// (legacy H2 files cleaned up, SQLCipher file missing/corrupt,
		// or empty database with no identity from a previous failed run),
		// delete the account key files so the app returns to the "create
		// account" screen on next launch, then abort this startup.
		if (originallyExpectedReopen && !reopen) {
			deleteAccountKeyFiles();
			throw new DbException();
		}

		if (!reopen) dir.mkdirs();
		super.open(DRIVER_CLASS, reopen, key, listener);

		// If compaction was deferred, run VACUUM now while we still hold
		// DB_OPEN_LOCK and no other thread has accessed the database.
		// At this point the connection pool is empty (compactAndClose
		// closed all connections and the parent reset closed = false).
		if (needsCompaction) {
			needsCompaction = false;
			Connection vc = null;
			try {
				vc = createConnection();
				((SqlCipherConnection) vc).getDatabase().execSQL("VACUUM");
				vc.close();
			} catch (SQLException e) {
				if (vc != null) {
					try { vc.close(); } catch (SQLException ignored) {}
				}
			}
		}

		return reopen;
	}

	/**
	 * Removes legacy H2 database files and migration staging files from the
	 * given directory. Returns true if the directory still contains files
	 * after cleanup (i.e. a SQLCipher database may exist).
	 * <p>
	 * Package-private for testing.
	 */
	static boolean cleanupLegacyFiles(File dir) {
		// Clean up any leftover migration staging files
		File staging = new File(dir, SQLCIPHER_STAGING_FILE);
		if (staging.exists()) staging.delete();
		// Remove any legacy H2 database files (H2 cannot run on Android,
		// so migration is not possible — start fresh instead)
		File[] files = dir.listFiles();
		if (files != null) {
			for (File f : files) {
				String name = f.getName();
				if (name.endsWith(".h2.db") || name.endsWith(".mv.db")
						|| name.endsWith(".trace.db")
						|| name.endsWith(".lock.db")
						|| name.equals(MIGRATION_MARKER)) {
					f.delete();
				}
			}
		}
		return isNonEmptyDirectory(dir);
	}

	/**
	 * Deletes account key files (db.key, db.key.bak, login.lockout) from
	 * the key directory so that AccountManager.accountExists() returns false
	 * on the next app launch. Called when the database cannot be reopened
	 * (e.g. after H2-to-SQLCipher upgrade where migration is not possible).
	 * <p>
	 * Package-private for testing.
	 */
	void deleteAccountKeyFiles() {
		File keyDir = config.getDatabaseKeyDirectory();
		if (keyDir.exists()) {
			new File(keyDir, "db.key").delete();
			new File(keyDir, "db.key.bak").delete();
			new File(keyDir, "login.lockout").delete();
		}
	}

	/**
	 * Checks whether the database file has a valid schema AND contains at
	 * least one identity row. A database with correct schema but no identity
	 * is unusable (e.g. created by a previous failed startup) and must be
	 * treated as invalid to avoid an infinite error loop.
	 * <p>
	 * Package-private for testing.
	 */
	static boolean hasValidSchema(File dbFile, SecretKey key) {
		String hexKey = StringUtils.toHexString(key.getBytes());
		SQLiteDatabase db = null;
		try {
			db = SQLiteDatabase.openDatabase(
					dbFile.getAbsolutePath(), hexKey, null,
					SQLiteDatabase.OPEN_READONLY, null);
			// Use rawQuery (not execSQL) because busy_timeout returns a row
			Cursor bc = db.rawQuery(
					"PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS, null);
			bc.close();
			// Check that the schema exists (settings table present)
			Cursor schema = db.rawQuery(
					"SELECT count(*) FROM sqlite_master"
							+ " WHERE type='table' AND name='settings'",
					null);
			try {
				if (!schema.moveToFirst() || schema.getInt(0) == 0) {
					return false;
				}
			} finally {
				schema.close();
			}
			// Check that the database has at least one identity row.
			// A database with valid schema but no identity was created by
			// a previous failed startup and cannot be reopened safely —
			// IdentityManager will throw DbException on 0 identities.
			Cursor identity = db.rawQuery(
					"SELECT count(*) FROM localAuthors", null);
			try {
				return identity.moveToFirst() && identity.getInt(0) > 0;
			} finally {
				identity.close();
			}
		} catch (Exception e) {
			return false;
		} finally {
			if (db != null) {
				try {
					db.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	@Override
	public void close() throws DbException {
		synchronized (DB_OPEN_LOCK) {
			closeAllConnections();
			Connection c = null;
			try {
				c = createConnection();
				setDirty(c, false);
				c.close();
			} catch (SQLException e) {
				if (c != null) {
					try { c.close(); } catch (SQLException ignored) {}
				}
				throw new DbException(e);
			}
		}
	}

	@Override
	protected Connection createConnection() throws DbException, SQLException {
		return createConnectionWithRetry();
	}

	private Connection createConnectionWithRetry()
			throws DbException, SQLException {
		SecretKey key = this.key;
		if (key == null) throw new DbClosedException();
		File dbFile = new File(config.getDatabaseDirectory(),
				SQLCIPHER_FILE);
		String hexKey = StringUtils.toHexString(key.getBytes());

		for (int attempt = 1; attempt <= OPEN_RETRY_MAX; attempt++) {
			SQLiteDatabase db = null;
			try {
				db = SQLiteDatabase.openOrCreateDatabase(
								dbFile.getAbsolutePath(), hexKey,
								null, null, null);
				db.execSQL("PRAGMA cipher_memory_security = OFF");
				// Use rawQuery (not execSQL) because busy_timeout returns a row
				Cursor bc = db.rawQuery(
						"PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS, null);
				bc.close();
				return new SqlCipherConnection(db);
			} catch (android.database.sqlite.SQLiteDatabaseLockedException e) {
				if (db != null) {
					try { db.close(); } catch (Exception ignored) {}
				}
				if (attempt == OPEN_RETRY_MAX) {
					throw new SQLException(
							"Database locked after " + OPEN_RETRY_MAX
									+ " attempts", e);
				}
				long delay = OPEN_RETRY_BASE_MS * (1L << (attempt - 1));
				try {
					Thread.sleep(delay);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new SQLException("Interrupted waiting for lock", ie);
				}
			} catch (Exception e) {
				if (db != null) {
					try { db.close(); } catch (Exception ignored) {}
				}
				throw new SQLException("Failed to configure database", e);
			}
		}
		throw new SQLException("Failed to open database");
	}

	/**
	 * Called by JdbcDatabase.open() when the database was dirty or migrated.
	 * Defers the actual VACUUM to avoid lock contention during startup.
	 * The deferred VACUUM runs at the end of openInternal() while still
	 * holding DB_OPEN_LOCK with an empty connection pool.
	 */
	@Override
	protected void compactAndClose() throws DbException {
		needsCompaction = true;
		// Close all pooled connections but skip VACUUM.
		// The caller (JdbcDatabase.open) will reset closed = false.
		closeAllConnections();
	}

}
