package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.db.JdbcUtils.tryToClose;

class Migration58_59 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration58_59.class.getName());

	@Override
	public int getStartVersion() {
		return 58;
	}

	@Override
	public int getEndVersion() {
		return 59;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();

			s.execute("ALTER TABLE contacts"
					+ " ADD COLUMN mode3Capable BOOLEAN DEFAULT FALSE NOT NULL");

		} catch (SQLException e) {
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
