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

import static org.zerionproject.core.util.IoUtils.isNonEmptyDirectory;

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

		if (reopen) {
			File dbFile = new File(dir, SQLCIPHER_FILE);
			if (!dbFile.exists()) {
				reopen = false;
			} else {
				Connection c = null;
				boolean valid = false;
				try {
					c = createConnection();
					valid = hasValidSchema(c);
				} catch (SQLException | DbException e) {
					valid = false;
				}
				if (valid) {
					seedPooledConnection(c);
				} else {
					if (c != null) {
						try {
							c.close();
						} catch (SQLException ignored) {
						}
					}
					dbFile.delete();
					reopen = false;
				}
			}
		}

		if (!reopen) dir.mkdirs();
		super.open(DRIVER_CLASS, reopen, key, listener);

		if (needsCompaction) {
			needsCompaction = false;
			Connection vc = null;
			try {
				vc = createConnection();
				SQLiteDatabase vacuumDb = ((SqlCipherConnection) vc).getDatabase();
				vacuumDb.execSQL("VACUUM");
				File dbFile = new File(config.getDatabaseDirectory(), SQLCIPHER_FILE);
				try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dbFile, "rw")) {
					raf.getFD().sync();
				}
				vc.close();
			} catch (SQLException | java.io.IOException e) {
				if (vc != null) {
					try { vc.close(); } catch (SQLException ignored) {}
				}
			}
		}

		return reopen;
	}

	/**
	 * Validates an existing database on a real connection: it must have the
	 * settings table and at least one local identity, otherwise it is treated
	 * as incomplete (e.g. account setup was interrupted) and wiped. Running the
	 * check on the connection that {@link #open} will reuse avoids a second key
	 * derivation on cold start.
	 */
	private boolean hasValidSchema(Connection c) {
		try {
			try (java.sql.PreparedStatement ps = c.prepareStatement(
					"SELECT count(*) FROM sqlite_master"
							+ " WHERE type='table' AND name='settings'");
					java.sql.ResultSet rs = ps.executeQuery()) {
				if (!rs.next() || rs.getInt(1) == 0) return false;
			}
			try (java.sql.PreparedStatement ps = c.prepareStatement(
					"SELECT count(*) FROM localAuthors");
					java.sql.ResultSet rs = ps.executeQuery()) {
				return rs.next() && rs.getInt(1) > 0;
			}
		} catch (SQLException e) {
			return false;
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
			} finally {
				if (key != null) {
					key.clear();
					key = null;
				}
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
				runPragma(db, "PRAGMA cipher_log_level = NONE");
				runPragma(db, "PRAGMA cipher_memory_security = ON");
				runPragma(db, "PRAGMA secure_delete = ON");
				runPragma(db, "PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);
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
	protected void compactAndClose() throws DbException {
		needsCompaction = true;
		closeAllConnections();
	}

	private static void runPragma(SQLiteDatabase db, String sql) {
		Cursor c = db.rawQuery(sql, null);
		try { c.moveToFirst(); } finally { c.close(); }
	}

}
