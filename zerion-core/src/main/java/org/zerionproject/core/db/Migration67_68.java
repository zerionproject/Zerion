package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.DatabaseConstants.CONTACT_ID_HIGH_WATER_KEY;
import static org.zerionproject.core.db.DatabaseConstants.DB_SETTINGS_NAMESPACE;
import static org.zerionproject.core.db.DatabaseConstants.KEY_SET_ID_HIGH_WATER_KEY;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration67_68 implements Migration<Connection> {

	private static final String[] KEY_SET_ID_TABLES = {
			"outgoingKeys", "incomingKeys"
	};

	private static final String[] CONTACT_ID_COLUMNS = {
			"contacts", "groupVisibilities", "offers", "statuses",
			"outgoingKeys", "pcsSessionState", "pcsSkippedKeys",
			"pqRatchetState", "contactCapabilities"
	};

	private static final String NO_CONTACT =
			" NOT EXISTS (SELECT 1 FROM contacts p"
					+ " WHERE p.contactId = %1$s.contactId)";
	private static final String NO_GROUP =
			" NOT EXISTS (SELECT 1 FROM groups p"
					+ " WHERE p.groupId = %1$s.groupId)";
	private static final String NO_MESSAGE =
			" NOT EXISTS (SELECT 1 FROM messages p"
					+ " WHERE p.messageId = %1$s.messageId)";
	private static final String NO_TRANSPORT =
			" NOT EXISTS (SELECT 1 FROM transports p"
					+ " WHERE p.transportId = %1$s.transportId)";

	static final String[] ORPHAN_DELETES = {
			delete("groupMetadata", NO_GROUP),
			delete("groupVisibilities", NO_CONTACT + " OR" + NO_GROUP),
			delete("messages", NO_GROUP),
			delete("messageMetadata", NO_MESSAGE + " OR" + NO_GROUP),
			delete("messageDependencies", NO_MESSAGE + " OR" + NO_GROUP),
			delete("offers", NO_CONTACT),
			delete("statuses", NO_MESSAGE + " OR" + NO_CONTACT + " OR"
					+ NO_GROUP),
			delete("outgoingKeys", NO_TRANSPORT
					+ " OR (outgoingKeys.contactId IS NOT NULL AND"
					+ NO_CONTACT + ")"
					+ " OR (outgoingKeys.pendingContactId IS NOT NULL AND"
					+ " NOT EXISTS (SELECT 1 FROM pendingContacts p"
					+ " WHERE p.pendingContactId"
					+ " = outgoingKeys.pendingContactId))"),
			delete("incomingKeys", NO_TRANSPORT
					+ " OR NOT EXISTS (SELECT 1 FROM outgoingKeys p"
					+ " WHERE p.keySetId = incomingKeys.keySetId)"),
			delete("pcsSessionState", NO_CONTACT),
			delete("pcsSkippedKeys", NO_CONTACT),
			delete("pqRatchetState", NO_CONTACT),
			delete("contactCapabilities", NO_CONTACT)
	};

	private static String delete(String table, String condition) {
		return "DELETE FROM " + table + " WHERE"
				+ String.format(condition, table);
	}

	@Override
	public int getStartVersion() {
		return 67;
	}

	@Override
	public int getEndVersion() {
		return 68;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		recordHighWater(txn, CONTACT_ID_HIGH_WATER_KEY, CONTACT_ID_COLUMNS,
				"contactId");
		recordHighWater(txn, KEY_SET_ID_HIGH_WATER_KEY, KEY_SET_ID_TABLES,
				"keySetId");
		Statement s = null;
		try {
			s = txn.createStatement();
			for (String sql : ForeignKeyIndexes.STATEMENTS) s.executeUpdate(sql);
			for (String sql : ORPHAN_DELETES) s.executeUpdate(sql);
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}

	private void recordHighWater(Connection txn, String key, String[] tables,
			String column) throws DbException {
		long highWater = storedHighWater(txn, key);
		for (String table : tables) {
			highWater = Math.max(highWater, maxValue(txn, table, column));
		}
		PreparedStatement ps = null;
		try {
			ps = txn.prepareStatement("DELETE FROM settings"
					+ " WHERE namespace = ? AND settingKey = ?");
			ps.setString(1, DB_SETTINGS_NAMESPACE);
			ps.setString(2, key);
			ps.executeUpdate();
			ps.close();
			ps = txn.prepareStatement("INSERT INTO settings"
					+ " (namespace, settingKey, value) VALUES (?, ?, ?)");
			ps.setString(1, DB_SETTINGS_NAMESPACE);
			ps.setString(2, key);
			ps.setString(3, String.valueOf(highWater));
			ps.executeUpdate();
			ps.close();
		} catch (SQLException e) {
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	private long storedHighWater(Connection txn, String key)
			throws DbException {
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			ps = txn.prepareStatement("SELECT value FROM settings"
					+ " WHERE namespace = ? AND settingKey = ?");
			ps.setString(1, DB_SETTINGS_NAMESPACE);
			ps.setString(2, key);
			rs = ps.executeQuery();
			long value = 0;
			if (rs.next()) {
				try {
					value = Long.parseLong(rs.getString(1));
				} catch (NumberFormatException e) {
					value = 0;
				}
			}
			rs.close();
			ps.close();
			return value;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			throw new DbException(e);
		}
	}

	private long maxValue(Connection txn, String table, String column)
			throws DbException {
		Statement s = null;
		ResultSet rs = null;
		try {
			s = txn.createStatement();
			rs = s.executeQuery("SELECT MAX(" + column + ") FROM " + table);
			long max = 0;
			if (rs.next()) {
				max = rs.getLong(1);
				if (rs.wasNull()) max = 0;
			}
			rs.close();
			s.close();
			return max;
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
