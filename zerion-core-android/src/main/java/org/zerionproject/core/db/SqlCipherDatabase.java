package org.zerionproject.core.db;

import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseConfig;
import org.zerionproject.core.api.db.DbClosedException;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.MigrationListener;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

import javax.annotation.Nullable;
import javax.inject.Inject;


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
			"org.zerionproject.core.db.SqlCipherDriver";

	private static final Object DB_OPEN_LOCK = new Object();

	private static final String SQLCIPHER_FILE = "db.sqlite";

	private static final int BUSY_TIMEOUT_MS = 5000;
	private static final int OPEN_RETRY_MAX = 5;
	private static final long OPEN_RETRY_BASE_MS = 100;

	private final DatabaseConfig config;

	private volatile boolean needsCompaction = false;

	@Nullable
	private volatile SecretKey key = null;
	private volatile boolean opened = false;

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
		this.key = new SecretKey(key.getBytes().clone());
		this.opened = false;
		try {
			System.loadLibrary("sqlcipher");
		} catch (UnsatisfiedLinkError e) {
			throw new DbException(e);
		}

		File dir = config.getDatabaseDirectory();
		File dbFile = new File(dir, SQLCIPHER_FILE);
		boolean reopen = false;

		if (dbFile.exists()) {
			Connection c = null;
			SqlCipherOpenPolicy.Probe probe;
			Throwable failure = null;
			try {
				c = createConnection();
				probe = probeSchema(c);
				if (probe == SqlCipherOpenPolicy.Probe.FAILED) {
					failure = new SQLException("expected tables are missing");
				}
			} catch (SQLException | DbException | RuntimeException e) {
				probe = SqlCipherOpenPolicy.Probe.FAILED;
				failure = e;
			}
			boolean marker = SqlCipherRecoveryFiles.setupMarker(dir).exists();
			SqlCipherOpenPolicy.Action action =
					SqlCipherOpenPolicy.decide(marker, probe);
			if (action == SqlCipherOpenPolicy.Action.REOPEN) {
				seedPooledConnection(c);
				SqlCipherRecoveryFiles.markSetupComplete(dir);
				reopen = true;
			} else {
				if (c != null) {
					try {
						c.close();
					} catch (SQLException ignored) {
					}
				}
				if (action == SqlCipherOpenPolicy.Action.RESET_EMPTY) {
					SqlCipherRecoveryFiles.deleteEmpty(dbFile);
				} else if (action ==
						SqlCipherOpenPolicy.Action.QUARANTINE_INCOMPLETE) {
					if (!SqlCipherRecoveryFiles.quarantine(dbFile,
							System.currentTimeMillis())) {
						throw new DbOpenFailureException(
								SqlCipherOpenPolicy.classify(failure),
								failure);
					}
					SqlCipherRecoveryFiles.markSetupComplete(dir);
				} else {
					throw new DbOpenFailureException(
							SqlCipherOpenPolicy.classify(failure), failure);
				}
			}
		}

		if (!reopen) {
			dir.mkdirs();
			SqlCipherRecoveryFiles.markSetupIncomplete(dir);
		}
		super.open(DRIVER_CLASS, reopen, this.key, listener);
		opened = true;

		boolean compactNow = needsCompaction;
		needsCompaction = false;
		Connection vc = null;
		try {
			vc = createConnection();
			SQLiteDatabase vacuumDb = ((SqlCipherConnection) vc).getDatabase();
			if (!compactNow) compactNow = freeSpaceExceedsThreshold(vacuumDb);
			if (compactNow) {
				vacuumDb.execSQL("VACUUM");
				File vacuumFile = new File(config.getDatabaseDirectory(), SQLCIPHER_FILE);
				try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(vacuumFile, "rw")) {
					raf.getFD().sync();
				}
			}
			vc.close();
		} catch (SQLException | java.io.IOException e) {
			if (vc != null) {
				try { vc.close(); } catch (SQLException ignored) {}
			}
		}

		return reopen;
	}

	private static final long VACUUM_FREE_BYTES_THRESHOLD = 10L * 1024 * 1024;
	private static final long VACUUM_FREE_BYTES_FLOOR = 1024L * 1024;
	private static final int VACUUM_FREE_PERCENT_THRESHOLD = 25;

	private boolean freeSpaceExceedsThreshold(SQLiteDatabase db) {
		long freelist = queryLong(db, "PRAGMA freelist_count");
		long pageSize = queryLong(db, "PRAGMA page_size");
		long pageCount = queryLong(db, "PRAGMA page_count");
		if (freelist <= 0 || pageSize <= 0 || pageCount <= 0) return false;
		long freeBytes = freelist * pageSize;
		if (freeBytes >= VACUUM_FREE_BYTES_THRESHOLD) return true;
		return freeBytes >= VACUUM_FREE_BYTES_FLOOR
				&& freelist * 100 >= pageCount * VACUUM_FREE_PERCENT_THRESHOLD;
	}

	private long queryLong(SQLiteDatabase db, String sql) {
		try {
			Cursor c = db.rawQuery(sql, null);
			try {
				if (c.moveToFirst()) return c.getLong(0);
				return -1;
			} finally {
				c.close();
			}
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/**
	 * Probes an existing database on a real connection: it must have the
	 * settings and identity tables, and is empty only when it has both and
	 * no identity row. What happens next is decided by
	 * {@link SqlCipherOpenPolicy}; a probe that throws or finds a table
	 * missing never leads to deletion. Running the check on the connection
	 * that {@link #open} will reuse avoids a second key derivation.
	 */
	private SqlCipherOpenPolicy.Probe probeSchema(Connection c)
			throws SQLException {
		boolean settings = tableExists(c, "settings");
		boolean identities = tableExists(c, "localAuthors");
		long rows = 0;
		if (settings && identities) {
			try (java.sql.PreparedStatement ps = c.prepareStatement(
					"SELECT count(*) FROM localAuthors");
					java.sql.ResultSet rs = ps.executeQuery()) {
				rows = rs.next() ? rs.getLong(1) : 0;
			}
		}
		return SqlCipherOpenPolicy.probe(settings, identities, rows);
	}

	private static boolean tableExists(Connection c, String table)
			throws SQLException {
		try (java.sql.PreparedStatement ps = c.prepareStatement(
				"SELECT count(*) FROM sqlite_master"
						+ " WHERE type='table' AND name=?")) {
			ps.setString(1, table);
			try (java.sql.ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getInt(1) > 0;
			}
		}
	}

	/**
	 * Closes the database and clears the clean-shutdown flag while the private
	 * key copy is still valid, then zeroes that copy. The key copied at open
	 * is owned here, so a caller clearing its own key object before close (the
	 * account manager's service stop runs before the database close) cannot
	 * prevent the final dirty-flag write. Idempotent: a second close, or a
	 * close after a failed open, only clears the key and returns.
	 */
	@Override
	public void close() throws DbException {
		synchronized (DB_OPEN_LOCK) {
			SecretKey k = key;
			if (k == null) return;
			if (!opened) {
				clearKey();
				return;
			}
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
			} finally {
				opened = false;
				clearKey();
			}
		}
	}

	private void clearKey() {
		SecretKey k = key;
		key = null;
		if (k != null) k.clear();
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
		byte[] passphrase = hexPassphrase(key.getBytes());
		try {
			return openWithPassphrase(dbFile, passphrase);
		} finally {
			java.util.Arrays.fill(passphrase, (byte) 0);
		}
	}

	private static final byte[] HEX_UPPER = {
			'0', '1', '2', '3', '4', '5', '6', '7',
			'8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

	/**
	 * The passphrase is the upper-case hex text of the key, as the string
	 * overload used to take it, encoded straight into a byte array: no
	 * String ever holds the key, and the bytes are wiped once the
	 * connection exists. Every existing database opens unchanged because
	 * the library derives the same key from the same UTF-8 bytes.
	 */
	static byte[] hexPassphrase(byte[] keyBytes) {
		byte[] hex = new byte[keyBytes.length * 2];
		for (int i = 0, j = 0; i < keyBytes.length; i++) {
			hex[j++] = HEX_UPPER[(keyBytes[i] >> 4) & 0xF];
			hex[j++] = HEX_UPPER[keyBytes[i] & 0xF];
		}
		return hex;
	}

	private Connection openWithPassphrase(File dbFile, byte[] passphrase)
			throws DbException, SQLException {
		for (int attempt = 1; attempt <= OPEN_RETRY_MAX; attempt++) {
			SQLiteDatabase db = null;
			try {
				db = SQLiteDatabase.openOrCreateDatabase(
								dbFile.getAbsolutePath(), passphrase,
								null, null, null);
				runPragma(db, "PRAGMA cipher_memory_security = ON");
				runPragma(db, "PRAGMA secure_delete = ON");
				runPragma(db, "PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
				runPragma(db, "PRAGMA journal_mode = WAL");
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

	@Override
	public void addIdentity(Connection txn, org.zerionproject.core.api.identity.Identity i)
			throws DbException {
		super.addIdentity(txn, i);
		SqlCipherRecoveryFiles.markSetupComplete(config.getDatabaseDirectory());
	}

	@Override
	protected void compactAndClose() throws DbException {
		needsCompaction = true;
		closeAllConnections();
	}

	private static void runPragma(SQLiteDatabase db, String sql) {
		Cursor c = db.rawQuery(sql, null);
		try { c.moveToFirst(); } finally { c.close(); }
	}

}
