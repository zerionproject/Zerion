package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration71_72 implements Migration<Connection> {

	private static final String[] GROUP_TABLES = {
			"groupSenderKeys", "groupKeyHistory", "groupCryptoState"
	};

	private static final String COPY_SUFFIX = "_fk";

	private static final String GROUP_FOREIGN_KEY =
			" FOREIGN KEY (groupId) REFERENCES groups (groupId)"
					+ " ON DELETE CASCADE";

	private static final String COLUMNS_CONTACT_CAPABILITIES =
			"contactId, capability, advertisedAt";
	private static final String CREATE_CONTACT_CAPABILITIES_COPY =
			"CREATE TABLE contactCapabilities" + COPY_SUFFIX
					+ " (contactId INT NOT NULL,"
					+ " capability INTEGER NOT NULL,"
					+ " advertisedAt BIGINT NOT NULL,"
					+ " PRIMARY KEY (contactId),"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE)";

	private static final String COLUMNS_GROUP_SENDER_KEYS =
			"groupId, authorId, chainKey, epoch, messageIndex, createdAt,"
					+ " isLocal, state";
	private static final String CREATE_GROUP_SENDER_KEYS_COPY =
			"CREATE TABLE groupSenderKeys" + COPY_SUFFIX
					+ " (groupId _HASH NOT NULL,"
					+ " authorId _HASH NOT NULL,"
					+ " chainKey _SECRET NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " createdAt BIGINT NOT NULL,"
					+ " isLocal INTEGER NOT NULL,"
					+ " state INTEGER NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId),"
					+ GROUP_FOREIGN_KEY + ")";

	private static final String COLUMNS_GROUP_KEY_HISTORY =
			"groupId, authorId, epoch, messageIndex, messageKey, expiresAt";
	private static final String CREATE_GROUP_KEY_HISTORY_COPY =
			"CREATE TABLE groupKeyHistory" + COPY_SUFFIX
					+ " (groupId _HASH NOT NULL,"
					+ " authorId _HASH NOT NULL,"
					+ " epoch INTEGER NOT NULL,"
					+ " messageIndex INTEGER NOT NULL,"
					+ " messageKey _SECRET NOT NULL,"
					+ " expiresAt BIGINT NOT NULL,"
					+ " PRIMARY KEY (groupId, authorId, epoch, messageIndex),"
					+ GROUP_FOREIGN_KEY + ")";

	private static final String COLUMNS_GROUP_CRYPTO_STATE =
			"groupId, cryptoMode, lastRekeyTime, rekeyReason, minCapability";
	private static final String CREATE_GROUP_CRYPTO_STATE_COPY =
			"CREATE TABLE groupCryptoState" + COPY_SUFFIX
					+ " (groupId _HASH NOT NULL,"
					+ " cryptoMode INTEGER NOT NULL,"
					+ " lastRekeyTime BIGINT NOT NULL,"
					+ " rekeyReason INTEGER,"
					+ " minCapability INTEGER NOT NULL,"
					+ " PRIMARY KEY (groupId),"
					+ GROUP_FOREIGN_KEY + ")";

	private static final String[] INDEXES = {
			"CREATE INDEX IF NOT EXISTS groupKeyHistoryExpiry"
					+ " ON groupKeyHistory (expiresAt)",
			"CREATE INDEX IF NOT EXISTS groupSenderKeysByGroup"
					+ " ON groupSenderKeys (groupId)"
	};

	private final DatabaseTypes dbTypes;

	Migration71_72(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 71;
	}

	@Override
	public int getEndVersion() {
		return 72;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		rebuild(txn, "contactCapabilities", COLUMNS_CONTACT_CAPABILITIES,
				CREATE_CONTACT_CAPABILITIES_COPY,
				" NOT EXISTS (SELECT 1 FROM contacts p"
						+ " WHERE p.contactId = contactCapabilities.contactId)");
		rebuild(txn, GROUP_TABLES[0], COLUMNS_GROUP_SENDER_KEYS,
				CREATE_GROUP_SENDER_KEYS_COPY, noGroup(GROUP_TABLES[0]));
		rebuild(txn, GROUP_TABLES[1], COLUMNS_GROUP_KEY_HISTORY,
				CREATE_GROUP_KEY_HISTORY_COPY, noGroup(GROUP_TABLES[1]));
		rebuild(txn, GROUP_TABLES[2], COLUMNS_GROUP_CRYPTO_STATE,
				CREATE_GROUP_CRYPTO_STATE_COPY, noGroup(GROUP_TABLES[2]));
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

	private static String noGroup(String table) {
		return " NOT EXISTS (SELECT 1 FROM groups p"
				+ " WHERE p.groupId = " + table + ".groupId)";
	}

	private void rebuild(Connection txn, String table, String columns,
			String createCopy, String orphanCondition) throws DbException {
		String copy = table + COPY_SUFFIX;
		Statement s = null;
		try {
			s = txn.createStatement();
			if (!tableExists(txn, table)) {
				if (!tableExists(txn, copy)) {
					throw new DbException();
				}
				s.executeUpdate("ALTER TABLE " + copy + " RENAME TO " + table);
			}
			s.executeUpdate("DROP TABLE IF EXISTS " + copy);
			s.executeUpdate("DELETE FROM " + table + " WHERE"
					+ orphanCondition);
			s.executeUpdate(dbTypes.replaceTypes(createCopy));
			s.executeUpdate("INSERT INTO " + copy + " (" + columns + ")"
					+ " SELECT " + columns + " FROM " + table);
			s.executeUpdate("DROP TABLE " + table);
			s.executeUpdate("ALTER TABLE " + copy + " RENAME TO " + table);
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}

	private boolean tableExists(Connection txn, String table) {
		Statement s = null;
		ResultSet rs = null;
		try {
			s = txn.createStatement();
			rs = s.executeQuery("SELECT 1 FROM " + table + " WHERE 1 = 0");
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
