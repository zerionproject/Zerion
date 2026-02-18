package org.briarproject.bramble.db;

import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteStatement;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.MigrationFailedException;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Properties;

import javax.annotation.Nullable;

/**
 * One-time atomic migration from H2 to SQLCipher for existing users.
 * <p>
 * Flow:
 * 1. Open H2 with existing key
 * 2. Create SQLCipher DB at db.sqlite.new (staging file)
 * 3. Copy all data with row-count verification
 * 4. Verify migrated identity exists
 * 5. Close both databases
 * 6. Back up any existing db.sqlite to db.sqlite.bak
 * 7. Rename db.sqlite.new → db.sqlite (atomic on same filesystem)
 * 8. Write marker file (migrated-to-sqlcipher)
 * 9. Delete H2 files
 * <p>
 * Crash recovery: On next start, if db.sqlite.new exists it is deleted
 * (partial migration). If marker file exists, H2 files are cleaned up
 * if they still remain. If neither exists and H2 files are present,
 * migration runs from scratch.
 */
@NotNullByDefault
class H2ToSqlCipherMigration {

	static final String SQLCIPHER_FILE = "db.sqlite";
	static final String SQLCIPHER_STAGING_FILE = "db.sqlite.new";
	static final String SQLCIPHER_BACKUP_FILE = "db.sqlite.bak";
	static final String MIGRATION_MARKER = "migrated-to-sqlcipher";

	private static final String[] TABLES = {
			"settings",
			"localAuthors",
			"contacts",
			"contactCapabilities",
			"groups",
			"groupMetadata",
			"groupVisibilities",
			"messages",
			"messageMetadata",
			"messageDependencies",
			"offers",
			"statuses",
			"transports",
			"incomingKeys",
			"outgoingKeys",
			"pendingContacts",
			"pcsSessionState",
			"pcsSkippedKeys",
			"pqRatchetState",
			"groupSenderKeys",
			"groupKeyHistory",
			"groupCryptoState",
	};

	private final DatabaseConfig config;

	/**
	 * Holds a description of the last migration error for diagnostics.
	 * Contains no sensitive data (keys, personal info).
	 */
	@Nullable
	private volatile String lastErrorDescription = null;

	H2ToSqlCipherMigration(DatabaseConfig config) {
		this.config = config;
	}

	/**
	 * Returns a non-sensitive description of the last migration error,
	 * or null if no error occurred.
	 */
	@Nullable
	String getLastErrorDescription() {
		return lastErrorDescription;
	}

	/**
	 * Runs the full migration. On success, the SQLCipher database is ready
	 * and H2 files are deleted. On failure, H2 files are preserved and
	 * {@link MigrationFailedException} is thrown.
	 */
	void migrate(SecretKey key) throws MigrationFailedException {
		File dir = config.getDatabaseDirectory();

		cleanupStagingFile(dir);

		File stagingFile = new File(dir, SQLCIPHER_STAGING_FILE);
		Connection h2Conn = null;
		SQLiteDatabase sqlCipherDb = null;
		try {
			h2Conn = openH2(key, dir);

			String hexKey = StringUtils.toHexString(key.getBytes());
			sqlCipherDb = SQLiteDatabase.openOrCreateDatabase(
					stagingFile.getAbsolutePath(), hexKey, null, null, null);
			sqlCipherDb.execSQL("PRAGMA cipher_memory_security = OFF");

			createSqlCipherSchema(sqlCipherDb);

			copyAndVerifyAllTables(h2Conn, sqlCipherDb);

			// Verify that at least one identity was migrated
			int identityCount = countRows(sqlCipherDb, "localAuthors");
			if (identityCount == 0) {
				lastErrorDescription = "Migration completed but no identity " +
						"rows found in localAuthors table";
				throw new MigrationFailedException();
			}

			h2Conn.close();
			h2Conn = null;
			sqlCipherDb.close();
			sqlCipherDb = null;

			// Back up any existing db.sqlite before replacing
			File finalFile = new File(dir, SQLCIPHER_FILE);
			if (finalFile.exists()) {
				File backupFile = new File(dir, SQLCIPHER_BACKUP_FILE);
				if (backupFile.exists()) backupFile.delete();
				finalFile.renameTo(backupFile);
			}

			// Atomic rename: staging → final
			if (!stagingFile.renameTo(finalFile)) {
				lastErrorDescription = "Failed to rename staging file " +
						"db.sqlite.new to db.sqlite";
				throw new MigrationFailedException();
			}

			// Write marker so next startup knows migration succeeded
			File marker = new File(dir, MIGRATION_MARKER);
			marker.createNewFile();

			// Clean up H2 files — migration is complete
			deleteH2Files(dir);

		} catch (MigrationFailedException e) {
			closeQuietly(h2Conn, sqlCipherDb);
			if (stagingFile.exists()) stagingFile.delete();
			throw e;
		} catch (Exception e) {
			closeQuietly(h2Conn, sqlCipherDb);
			if (stagingFile.exists()) stagingFile.delete();
			lastErrorDescription = "Migration failed: " +
					e.getClass().getSimpleName() + ": " + e.getMessage();
			throw new MigrationFailedException(e);
		}
	}

	static void cleanupStagingFile(File dir) {
		File staging = new File(dir, SQLCIPHER_STAGING_FILE);
		if (staging.exists()) {
			staging.delete();
		}
	}

	static boolean hasMarker(File dir) {
		return new File(dir, MIGRATION_MARKER).exists();
	}

	static boolean hasH2Files(File dir) {
		File[] files = dir.listFiles();
		if (files == null) return false;
		for (File f : files) {
			String name = f.getName();
			if (name.endsWith(".h2.db") || name.endsWith(".mv.db")) {
				return true;
			}
		}
		return false;
	}

	static void deleteH2Files(File dir) {
		File[] files = dir.listFiles();
		if (files == null) return;
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

	/**
	 * Finds the latest backup file (db.sqlite.bak) in the directory.
	 * Returns null if no backup exists.
	 */
	@Nullable
	static File findBackup(File dir) {
		File bak = new File(dir, SQLCIPHER_BACKUP_FILE);
		return bak.exists() ? bak : null;
	}

	/**
	 * Writes a non-sensitive error description to migration-error.txt
	 * so the diagnostics export can include it.
	 */
	static void writeErrorFile(File dir, @Nullable String description,
			@Nullable Throwable cause) {
		try {
			File errorFile = new File(dir, "migration-error.txt");
			java.io.FileWriter writer = new java.io.FileWriter(errorFile);
			if (description != null) {
				writer.write(description + "\n");
			}
			if (cause != null) {
				writer.write(cause.getClass().getName());
				if (cause.getMessage() != null) {
					writer.write(": " + cause.getMessage());
				}
				writer.write("\n");
				if (cause.getCause() != null) {
					Throwable root = cause.getCause();
					writer.write("Caused by: " + root.getClass().getName());
					if (root.getMessage() != null) {
						writer.write(": " + root.getMessage());
					}
					writer.write("\n");
				}
			}
			writer.close();
		} catch (Exception ignored) {
		}
	}

	private void closeQuietly(@Nullable Connection h2Conn,
			@Nullable SQLiteDatabase sqlCipherDb) {
		if (h2Conn != null) {
			try {
				h2Conn.close();
			} catch (Exception ignored) {
			}
		}
		if (sqlCipherDb != null) {
			try {
				sqlCipherDb.close();
			} catch (Exception ignored) {
			}
		}
	}

	/**
	 * Opens the legacy H2 database. Tries the split: URL format first
	 * (for .h2.db PageStore files), then falls back to non-split URL
	 * (for .mv.db MVStore files).
	 */
	private Connection openH2(SecretKey key, File dir) throws Exception {
		Class.forName("org.h2.Driver");
		String path = new File(dir, "db").getAbsolutePath();
		String hex = StringUtils.toHexString(key.getBytes());
		Properties props = new Properties();
		props.setProperty("user", "user");
		props.put("password", hex + " password");

		// Try split: URL first (PageStore format — .h2.db files)
		try {
			String splitUrl = "jdbc:h2:split:" + path
					+ ";CIPHER=AES;WRITE_DELAY=0;MULTI_THREADED=1";
			return DriverManager.getConnection(splitUrl, props);
		} catch (SQLException e) {
			// Fall through to non-split URL
		}

		// Try non-split URL (MVStore format — .mv.db files)
		String mvUrl = "jdbc:h2:" + path
				+ ";CIPHER=AES;WRITE_DELAY=0;MULTI_THREADED=1";
		return DriverManager.getConnection(mvUrl, props);
	}

	private void copyAndVerifyAllTables(Connection h2Conn,
			SQLiteDatabase sqlCipherDb) throws SQLException,
			MigrationFailedException {
		for (String table : TABLES) {
			int copied = copyTable(h2Conn, sqlCipherDb, table);
			if (copied >= 0) {
				int verified = countRows(sqlCipherDb, table);
				if (verified != copied) {
					lastErrorDescription = "Row count mismatch for table '"
							+ table + "': copied=" + copied
							+ " verified=" + verified;
					throw new MigrationFailedException();
				}
			}
		}
	}

	private int countRows(SQLiteDatabase db, String table) {
		Cursor c = db.rawQuery(
				"SELECT COUNT(*) FROM " + table, null);
		try {
			if (c.moveToFirst()) return c.getInt(0);
			return 0;
		} finally {
			c.close();
		}
	}

	private int copyTable(Connection h2Conn, SQLiteDatabase sqlCipherDb,
			String table) throws SQLException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = h2Conn.prepareStatement("SELECT * FROM " + table);
			rs = ps.executeQuery();
			ResultSetMetaData meta = rs.getMetaData();
			int colCount = meta.getColumnCount();

			if (colCount == 0) return 0;

			StringBuilder insertSql = new StringBuilder();
			insertSql.append("INSERT INTO ").append(table).append(" VALUES (");
			for (int i = 0; i < colCount; i++) {
				if (i > 0) insertSql.append(", ");
				insertSql.append("?");
			}
			insertSql.append(")");

			int rowCount = 0;
			sqlCipherDb.beginTransaction();
			try {
				while (rs.next()) {
					SQLiteStatement stmt = sqlCipherDb.compileStatement(
							insertSql.toString());
					try {
						for (int i = 1; i <= colCount; i++) {
							Object val = rs.getObject(i);
							if (val == null) {
								stmt.bindNull(i);
							} else if (val instanceof byte[]) {
								stmt.bindBlob(i, (byte[]) val);
							} else if (val instanceof Number) {
								stmt.bindLong(i,
										((Number) val).longValue());
							} else if (val instanceof Boolean) {
								stmt.bindLong(i,
										(Boolean) val ? 1 : 0);
							} else {
								stmt.bindString(i, val.toString());
							}
						}
						stmt.executeInsert();
					} finally {
						stmt.close();
					}
					rowCount++;
				}
				sqlCipherDb.setTransactionSuccessful();
			} finally {
				sqlCipherDb.endTransaction();
			}
			return rowCount;
		} catch (SQLException e) {
			// Table might not exist in old schema — skip gracefully
			if (e.getMessage() != null
					&& e.getMessage().contains("Table")
					&& e.getMessage().contains("not found")) {
				return -1;
			} else {
				throw e;
			}
		} finally {
			if (rs != null) {
				try {
					rs.close();
				} catch (SQLException ignored) {
				}
			}
			if (ps != null) {
				try {
					ps.close();
				} catch (SQLException ignored) {
				}
			}
		}
	}

	private void createSqlCipherSchema(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE IF NOT EXISTS settings"
				+ " (namespace TEXT NOT NULL,"
				+ " settingKey TEXT NOT NULL,"
				+ " value TEXT NOT NULL,"
				+ " PRIMARY KEY (namespace, settingKey))");

		db.execSQL("CREATE TABLE IF NOT EXISTS localAuthors"
				+ " (authorId BLOB NOT NULL,"
				+ " formatVersion INT NOT NULL,"
				+ " name TEXT NOT NULL,"
				+ " publicKey BLOB NOT NULL,"
				+ " privateKey BLOB NOT NULL,"
				+ " handshakePublicKey BLOB,"
				+ " handshakePrivateKey BLOB,"
				+ " hybridHandshakePublicKey BLOB,"
				+ " hybridHandshakePrivateKey BLOB,"
				+ " created BIGINT NOT NULL,"
				+ " PRIMARY KEY (authorId))");

		db.execSQL("CREATE TABLE IF NOT EXISTS contacts"
				+ " (contactId INTEGER,"
				+ " authorId BLOB NOT NULL,"
				+ " formatVersion INT NOT NULL,"
				+ " name TEXT NOT NULL,"
				+ " alias TEXT,"
				+ " publicKey BLOB NOT NULL,"
				+ " handshakePublicKey BLOB,"
				+ " localAuthorId BLOB NOT NULL,"
				+ " verified BOOLEAN NOT NULL,"
				+ " postQuantum BOOLEAN DEFAULT FALSE NOT NULL,"
				+ " pcsEnabled BOOLEAN DEFAULT FALSE NOT NULL,"
				+ " mode3Capable BOOLEAN DEFAULT FALSE NOT NULL,"
				+ " syncVersions BLOB DEFAULT X'3030' NOT NULL,"
				+ " PRIMARY KEY (contactId),"
				+ " FOREIGN KEY (localAuthorId)"
				+ " REFERENCES localAuthors (authorId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS contactCapabilities"
				+ " (contactId INT NOT NULL PRIMARY KEY,"
				+ " capability INTEGER NOT NULL,"
				+ " advertisedAt BIGINT NOT NULL)");

		db.execSQL("CREATE TABLE IF NOT EXISTS groups"
				+ " (groupId BLOB NOT NULL,"
				+ " clientId TEXT NOT NULL,"
				+ " majorVersion INT NOT NULL,"
				+ " descriptor BLOB NOT NULL,"
				+ " PRIMARY KEY (groupId))");

		db.execSQL("CREATE TABLE IF NOT EXISTS groupMetadata"
				+ " (groupId BLOB NOT NULL,"
				+ " metaKey TEXT NOT NULL,"
				+ " value BLOB NOT NULL,"
				+ " PRIMARY KEY (groupId, metaKey),"
				+ " FOREIGN KEY (groupId)"
				+ " REFERENCES groups (groupId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS groupVisibilities"
				+ " (contactId INT NOT NULL,"
				+ " groupId BLOB NOT NULL,"
				+ " shared BOOLEAN NOT NULL,"
				+ " PRIMARY KEY (contactId, groupId),"
				+ " FOREIGN KEY (contactId)"
				+ " REFERENCES contacts (contactId)"
				+ " ON DELETE CASCADE,"
				+ " FOREIGN KEY (groupId)"
				+ " REFERENCES groups (groupId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS messages"
				+ " (messageId BLOB NOT NULL,"
				+ " groupId BLOB NOT NULL,"
				+ " timestamp BIGINT NOT NULL,"
				+ " state INT NOT NULL,"
				+ " shared BOOLEAN NOT NULL,"
				+ " temporary BOOLEAN NOT NULL,"
				+ " cleanupTimerDuration BIGINT,"
				+ " cleanupDeadline BIGINT,"
				+ " length INT NOT NULL,"
				+ " raw BLOB,"
				+ " PRIMARY KEY (messageId),"
				+ " FOREIGN KEY (groupId)"
				+ " REFERENCES groups (groupId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS messageMetadata"
				+ " (messageId BLOB NOT NULL,"
				+ " groupId BLOB NOT NULL,"
				+ " state INT NOT NULL,"
				+ " metaKey TEXT NOT NULL,"
				+ " value BLOB NOT NULL,"
				+ " PRIMARY KEY (messageId, metaKey),"
				+ " FOREIGN KEY (messageId)"
				+ " REFERENCES messages (messageId)"
				+ " ON DELETE CASCADE,"
				+ " FOREIGN KEY (groupId)"
				+ " REFERENCES groups (groupId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS messageDependencies"
				+ " (groupId BLOB NOT NULL,"
				+ " messageId BLOB NOT NULL,"
				+ " dependencyId BLOB NOT NULL,"
				+ " messageState INT NOT NULL,"
				+ " dependencyState INT,"
				+ " FOREIGN KEY (groupId)"
				+ " REFERENCES groups (groupId)"
				+ " ON DELETE CASCADE,"
				+ " FOREIGN KEY (messageId)"
				+ " REFERENCES messages (messageId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS offers"
				+ " (messageId BLOB NOT NULL,"
				+ " contactId INT NOT NULL,"
				+ " FOREIGN KEY (messageId)"
				+ " REFERENCES messages (messageId)"
				+ " ON DELETE CASCADE,"
				+ " FOREIGN KEY (contactId)"
				+ " REFERENCES contacts (contactId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS statuses"
				+ " (messageId BLOB NOT NULL,"
				+ " contactId INT NOT NULL,"
				+ " groupId BLOB NOT NULL,"
				+ " timestamp BIGINT NOT NULL,"
				+ " length INT NOT NULL,"
				+ " state INT NOT NULL,"
				+ " groupShared BOOLEAN NOT NULL,"
				+ " messageShared BOOLEAN NOT NULL,"
				+ " deleted BOOLEAN NOT NULL,"
				+ " seen BOOLEAN NOT NULL,"
				+ " ack BOOLEAN NOT NULL,"
				+ " requested BOOLEAN NOT NULL,"
				+ " expiry BIGINT NOT NULL,"
				+ " txCount INT NOT NULL,"
				+ " PRIMARY KEY (messageId, contactId),"
				+ " FOREIGN KEY (messageId)"
				+ " REFERENCES messages (messageId)"
				+ " ON DELETE CASCADE,"
				+ " FOREIGN KEY (contactId)"
				+ " REFERENCES contacts (contactId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS transports"
				+ " (transportId TEXT NOT NULL,"
				+ " maxLatency BIGINT NOT NULL,"
				+ " PRIMARY KEY (transportId))");

		db.execSQL("CREATE TABLE IF NOT EXISTS incomingKeys"
				+ " (transportId TEXT NOT NULL,"
				+ " keySetId INT NOT NULL,"
				+ " rotationPeriod BIGINT NOT NULL,"
				+ " tagKey BLOB NOT NULL,"
				+ " headerKey BLOB NOT NULL,"
				+ " base BIGINT NOT NULL,"
				+ " bitmap BLOB NOT NULL,"
				+ " periodOffset INT NOT NULL,"
				+ " FOREIGN KEY (transportId)"
				+ " REFERENCES transports (transportId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS outgoingKeys"
				+ " (keySetId INTEGER,"
				+ " contactId INT NOT NULL,"
				+ " transportId TEXT NOT NULL,"
				+ " rotationPeriod BIGINT NOT NULL,"
				+ " tagKey BLOB NOT NULL,"
				+ " headerKey BLOB NOT NULL,"
				+ " stream BIGINT NOT NULL,"
				+ " active BOOLEAN NOT NULL,"
				+ " rootKey BLOB,"
				+ " alice BOOLEAN,"
				+ " PRIMARY KEY (keySetId),"
				+ " FOREIGN KEY (contactId)"
				+ " REFERENCES contacts (contactId)"
				+ " ON DELETE CASCADE,"
				+ " FOREIGN KEY (transportId)"
				+ " REFERENCES transports (transportId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS pendingContacts"
				+ " (pendingContactId BLOB NOT NULL,"
				+ " publicKey BLOB NOT NULL,"
				+ " alias TEXT NOT NULL,"
				+ " timestamp BIGINT NOT NULL,"
				+ " formatVersion INT DEFAULT 0 NOT NULL,"
				+ " PRIMARY KEY (pendingContactId))");

		db.execSQL("CREATE TABLE IF NOT EXISTS pcsSessionState"
				+ " (contactId INT NOT NULL,"
				+ " direction INT NOT NULL,"
				+ " chainKey BLOB NOT NULL,"
				+ " messageNumber INT NOT NULL,"
				+ " previousChainLength INT NOT NULL,"
				+ " mode2Enabled BOOLEAN,"
				+ " rootKey BLOB,"
				+ " dhPrivateKey BLOB,"
				+ " dhPublicKey BLOB,"
				+ " dhRemotePublicKey BLOB,"
				+ " PRIMARY KEY (contactId, direction),"
				+ " FOREIGN KEY (contactId)"
				+ " REFERENCES contacts (contactId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS pcsSkippedKeys"
				+ " (contactId INT NOT NULL,"
				+ " direction SMALLINT NOT NULL,"
				+ " messageNumber INT NOT NULL,"
				+ " messageKey BLOB NOT NULL,"
				+ " timestamp BIGINT NOT NULL,"
				+ " chainId BLOB,"
				+ " PRIMARY KEY (contactId, direction, messageNumber),"
				+ " FOREIGN KEY (contactId)"
				+ " REFERENCES contacts (contactId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS pqRatchetState"
				+ " (contactId INT NOT NULL,"
				+ " currentEpoch BIGINT NOT NULL,"
				+ " epochStartTime BIGINT NOT NULL,"
				+ " messagesSinceEpoch INT NOT NULL,"
				+ " state INT NOT NULL,"
				+ " isInitiator BOOLEAN NOT NULL,"
				+ " chunksSent INT NOT NULL,"
				+ " chunksReceived INT NOT NULL,"
				+ " ourEkSeed BLOB,"
				+ " ourEkVector BLOB,"
				+ " ourDecapsKey BLOB,"
				+ " theirEkSeed BLOB,"
				+ " theirEkHash BLOB,"
				+ " theirEkVector BLOB,"
				+ " ciphertext BLOB,"
				+ " pendingChunks BLOB,"
				+ " PRIMARY KEY (contactId),"
				+ " FOREIGN KEY (contactId)"
				+ " REFERENCES contacts (contactId)"
				+ " ON DELETE CASCADE)");

		db.execSQL("CREATE TABLE IF NOT EXISTS groupSenderKeys"
				+ " (groupId BLOB NOT NULL,"
				+ " authorId BLOB NOT NULL,"
				+ " chainKey BLOB NOT NULL,"
				+ " epoch INTEGER NOT NULL,"
				+ " messageIndex INTEGER NOT NULL,"
				+ " createdAt BIGINT NOT NULL,"
				+ " isLocal INTEGER NOT NULL,"
				+ " state INTEGER NOT NULL,"
				+ " PRIMARY KEY (groupId, authorId))");

		db.execSQL("CREATE TABLE IF NOT EXISTS groupKeyHistory"
				+ " (groupId BLOB NOT NULL,"
				+ " authorId BLOB NOT NULL,"
				+ " epoch INTEGER NOT NULL,"
				+ " messageIndex INTEGER NOT NULL,"
				+ " messageKey BLOB NOT NULL,"
				+ " expiresAt BIGINT NOT NULL,"
				+ " PRIMARY KEY (groupId, authorId, epoch, messageIndex))");

		db.execSQL("CREATE TABLE IF NOT EXISTS groupCryptoState"
				+ " (groupId BLOB NOT NULL PRIMARY KEY,"
				+ " cryptoMode INTEGER NOT NULL,"
				+ " lastRekeyTime BIGINT NOT NULL,"
				+ " rekeyReason INTEGER,"
				+ " minCapability INTEGER NOT NULL)");
	}
}
