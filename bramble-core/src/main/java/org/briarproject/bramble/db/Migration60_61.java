package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.briarproject.bramble.db.JdbcUtils.tryToClose;

/**
 * Migration for Sender Keys Group PCS tables.
 * <p>
 * Adds three new tables:
 * - groupSenderKeys: Per-sender key state (one per member per group)
 * - groupKeyHistory: Key history for out-of-order decryption
 * - groupCryptoState: Group crypto metadata
 */
class Migration60_61 implements Migration<Connection> {

	private final DatabaseTypes dbTypes;

	Migration60_61(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 60;
	}

	@Override
	public int getEndVersion() {
		return 61;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();

			// Per-sender key state (one per member per group)
			s.execute(dbTypes.replaceTypes("CREATE TABLE groupSenderKeys ("
					+ " groupId _HASH NOT NULL,"
					+ " authorId _HASH NOT NULL,"
					+ " chainKey _SECRET NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " createdAt BIGINT NOT NULL,"
					+ " isLocal INTEGER NOT NULL,"
					+ " state INTEGER NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId)"
					+ ")"));

			// Key history for out-of-order decryption
			s.execute(dbTypes.replaceTypes("CREATE TABLE groupKeyHistory ("
					+ " groupId _HASH NOT NULL,"
					+ " authorId _HASH NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " messageKey _SECRET NOT NULL,"
					+ " expiresAt BIGINT NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId, epoch, messageIndex)"
					+ ")"));

			// Group crypto metadata
			s.execute(dbTypes.replaceTypes("CREATE TABLE groupCryptoState ("
					+ " groupId _HASH NOT NULL PRIMARY KEY,"
					+ " cryptoMode INTEGER NOT NULL,"
					+ " lastRekeyTime BIGINT NOT NULL,"
					+ " rekeyReason INTEGER,"
					+ " minCapability INTEGER NOT NULL"
					+ ")"));

			// Index for cleaning up expired key history entries
			s.execute("CREATE INDEX groupKeyHistoryExpiry"
					+ " ON groupKeyHistory (expiresAt)");

			// Index for querying sender keys by group
			s.execute("CREATE INDEX groupSenderKeysByGroup"
					+ " ON groupSenderKeys (groupId)");

		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
