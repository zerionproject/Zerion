package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.db.JdbcUtils.tryToClose;

/**
 * Migration to add formatVersion column to pendingContacts table.
 * This enables tracking whether a pending contact uses classical (v0)
 * or hybrid post-quantum (v1) handshake protocol.
 */
class Migration50_51 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration50_51.class.getName());

	@Override
	public int getStartVersion() {
		return 50;
	}

	@Override
	public int getEndVersion() {
		return 51;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			// Add formatVersion column with default 0 (classical) for existing records
			s.execute("ALTER TABLE pendingContacts"
					+ " ADD COLUMN formatVersion INT NOT NULL DEFAULT 0");
		} catch (SQLException e) {
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
