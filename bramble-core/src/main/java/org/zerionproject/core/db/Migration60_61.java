package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

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

			s.execute(dbTypes.replaceTypes("CREATE TABLE groupKeyHistory ("
					+ " groupId _HASH NOT NULL,"
					+ " authorId _HASH NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " messageKey _SECRET NOT NULL,"
					+ " expiresAt BIGINT NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId, epoch, messageIndex)"
					+ ")"));

			s.execute(dbTypes.replaceTypes("CREATE TABLE groupCryptoState ("
					+ " groupId _HASH NOT NULL PRIMARY KEY,"
					+ " cryptoMode INTEGER NOT NULL,"
					+ " lastRekeyTime BIGINT NOT NULL,"
					+ " rekeyReason INTEGER,"
					+ " minCapability INTEGER NOT NULL"
					+ ")"));

			s.execute("CREATE INDEX groupKeyHistoryExpiry"
					+ " ON groupKeyHistory (expiresAt)");

			s.execute("CREATE INDEX groupSenderKeysByGroup"
					+ " ON groupSenderKeys (groupId)");

			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
