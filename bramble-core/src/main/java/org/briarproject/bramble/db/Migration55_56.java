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
 * Migration to add pcsEnabled column to contacts table.
 * <p>
 * This column tracks whether Post-Compromise Security (symmetric ratchet)
 * is enabled for each contact. Existing contacts default to PCS disabled.
 */
class Migration55_56 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration55_56.class.getName());

	@Override
	public int getStartVersion() {
		return 55;
	}

	@Override
	public int getEndVersion() {
		return 56;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();

			// Add pcsEnabled column with default false for existing contacts
			// New contacts will negotiate PCS support during handshake
			s.execute("ALTER TABLE contacts"
					+ " ADD COLUMN pcsEnabled BOOLEAN NOT NULL DEFAULT FALSE");

		} catch (SQLException e) {
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
