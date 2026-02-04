package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.briarproject.bramble.db.JdbcUtils.tryToClose;

/**
 * Migration for contact capability tracking.
 * <p>
 * Adds table for storing per-contact crypto capabilities,
 * enabling dynamic capability negotiation for group encryption.
 */
class Migration61_62 implements Migration<Connection> {

	@Override
	public int getStartVersion() {
		return 61;
	}

	@Override
	public int getEndVersion() {
		return 62;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();

			// Per-contact capability state
			// Tracks which crypto features each contact supports
			s.execute("CREATE TABLE contactCapabilities ("
					+ " contactId INT NOT NULL PRIMARY KEY,"
					+ " capability INTEGER NOT NULL,"
					+ " advertisedAt BIGINT NOT NULL"
					+ ")");

		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
