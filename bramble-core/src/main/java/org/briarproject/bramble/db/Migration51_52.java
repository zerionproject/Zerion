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
 * Migration to add postQuantum column to contacts table.
 * This enables tracking whether a contact was established using hybrid
 * post-quantum cryptography, which is important for preventing downgrade
 * attacks.
 */
class Migration51_52 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration51_52.class.getName());

	@Override
	public int getStartVersion() {
		return 51;
	}

	@Override
	public int getEndVersion() {
		return 52;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			// Add postQuantum column with default false for existing contacts
			// (existing contacts are assumed to be classical/Briar-compatible)
			s.execute("ALTER TABLE contacts"
					+ " ADD COLUMN postQuantum BOOLEAN NOT NULL DEFAULT FALSE");
		} catch (SQLException e) {
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
