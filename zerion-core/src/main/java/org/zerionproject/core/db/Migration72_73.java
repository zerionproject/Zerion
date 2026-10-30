package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration72_73 implements Migration<Connection> {

	static final String CREATE_CONTACT_LINKS =
			"CREATE TABLE contactLinks"
					+ " (linkId _HASH NOT NULL,"
					+ " localAuthorId _HASH NOT NULL,"
					+ " kind INT NOT NULL,"
					+ " state INT NOT NULL,"
					+ " label _STRING,"
					+ " hybridPublicKey _BINARY,"
					+ " hybridPrivateKey _BINARY,"
					+ " inboxKey _SECRET,"
					+ " createdAt BIGINT NOT NULL,"
					+ " lastSharedAt BIGINT,"
					+ " retiredAt BIGINT,"
					+ " withdrawnAt BIGINT,"
					+ " PRIMARY KEY (linkId))";

	static final String CREATE_REQUEST_ACCOUNT =
			"CREATE TABLE requestAccount"
					+ " (localAuthorId _HASH NOT NULL,"
					+ " accountTagSecret _SECRET NOT NULL,"
					+ " PRIMARY KEY (localAuthorId))";

	static final String CREATE_OUTGOING_REQUESTS =
			"CREATE TABLE outgoingRequests"
					+ " (requestId _BINARY NOT NULL,"
					+ " localAuthorId _HASH NOT NULL,"
					+ " targetLink _STRING NOT NULL,"
					+ " alias _STRING NOT NULL,"
					+ " shownName _STRING NOT NULL,"
					+ " note _STRING,"
					+ " oneTimePublicKey _BINARY,"
					+ " oneTimePrivateKey _BINARY,"
					+ " createdAt BIGINT NOT NULL,"
					+ " state INT NOT NULL,"
					+ " attempts INT NOT NULL,"
					+ " nextAttemptAt BIGINT NOT NULL,"
					+ " deliveredAt BIGINT,"
					+ " pendingContactId _HASH,"
					+ " cancelledAt BIGINT,"
					+ " PRIMARY KEY (requestId))";

	static final String CREATE_INCOMING_REQUESTS =
			"CREATE TABLE incomingRequests"
					+ " (requestId _BINARY NOT NULL,"
					+ " linkId _HASH NOT NULL,"
					+ " oneTimeLink _BINARY NOT NULL,"
					+ " name _STRING,"
					+ " note _STRING,"
					+ " senderTag _HASH NOT NULL,"
					+ " senderTimestamp BIGINT NOT NULL,"
					+ " receivedAt BIGINT NOT NULL,"
					+ " state INT NOT NULL,"
					+ " acceptedAt BIGINT,"
					+ " pendingContactId _HASH,"
					+ " PRIMARY KEY (requestId))";

	static final String CREATE_REQUEST_REPLAY =
			"CREATE TABLE requestReplay"
					+ " (requestIdHash _HASH NOT NULL,"
					+ " kind INT NOT NULL,"
					+ " oneTimeKeyHash _HASH,"
					+ " linkId _HASH NOT NULL,"
					+ " expiresAt BIGINT NOT NULL,"
					+ " PRIMARY KEY (requestIdHash, kind))";

	static final String CREATE_REQUEST_BLOCKS =
			"CREATE TABLE requestBlocks"
					+ " (linkId _HASH NOT NULL,"
					+ " senderTag _HASH NOT NULL,"
					+ " createdAt BIGINT NOT NULL,"
					+ " PRIMARY KEY (linkId, senderTag))";

	static final String[] INDEXES = {
			"CREATE INDEX IF NOT EXISTS requestReplayByOneTimeKey"
					+ " ON requestReplay (oneTimeKeyHash)",
			"CREATE INDEX IF NOT EXISTS requestReplayByLink"
					+ " ON requestReplay (linkId)"
	};

	static final String[][] TABLES = {
			{"contactLinks", CREATE_CONTACT_LINKS},
			{"requestAccount", CREATE_REQUEST_ACCOUNT},
			{"outgoingRequests", CREATE_OUTGOING_REQUESTS},
			{"incomingRequests", CREATE_INCOMING_REQUESTS},
			{"requestReplay", CREATE_REQUEST_REPLAY},
			{"requestBlocks", CREATE_REQUEST_BLOCKS}
	};

	private final DatabaseTypes dbTypes;

	Migration72_73(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 72;
	}

	@Override
	public int getEndVersion() {
		return 73;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		addColumnIfMissing(txn, "pendingContacts", "expiry",
				"ALTER TABLE pendingContacts ADD COLUMN expiry BIGINT");
		addColumnIfMissing(txn, "pendingContacts", "rendezvousMode",
				"ALTER TABLE pendingContacts ADD COLUMN rendezvousMode"
						+ " INT DEFAULT 0 NOT NULL");
		createTablesIfMissing(txn, dbTypes);
		createIndexes(txn);
	}

	static void createIndexes(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			for (String sql : INDEXES) s.executeUpdate(sql);
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}

	static void createTablesIfMissing(Connection txn, DatabaseTypes dbTypes)
			throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			for (String[] table : TABLES) {
				if (!exists(txn, "SELECT 1 FROM " + table[0]
						+ " WHERE 1 = 0")) {
					s.executeUpdate(dbTypes.replaceTypes(table[1]));
				}
			}
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}

	private static void addColumnIfMissing(Connection txn, String table,
			String column, String sql) throws DbException {
		if (exists(txn, "SELECT " + column + " FROM " + table
				+ " WHERE 1 = 0")) {
			return;
		}
		Statement s = null;
		try {
			s = txn.createStatement();
			s.executeUpdate(sql);
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}

	private static boolean exists(Connection txn, String probe) {
		Statement s = null;
		ResultSet rs = null;
		try {
			s = txn.createStatement();
			rs = s.executeQuery(probe);
			rs.close();
			s.close();
			return true;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(s);
			return false;
		}
	}
}
