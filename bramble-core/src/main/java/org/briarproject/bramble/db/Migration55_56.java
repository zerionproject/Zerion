package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.briarproject.bramble.db.JdbcUtils.tryToClose;


class Migration55_56 implements Migration<Connection> {
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
			s.execute("ALTER TABLE contacts"
					+ " ADD COLUMN pcsEnabled BOOLEAN NOT NULL DEFAULT FALSE");

		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
