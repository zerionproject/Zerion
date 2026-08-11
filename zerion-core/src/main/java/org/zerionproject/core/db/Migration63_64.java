package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration63_64 implements Migration<Connection> {

	@Override
	public int getStartVersion() {
		return 63;
	}

	@Override
	public int getEndVersion() {
		return 64;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE contacts DROP COLUMN mode3Capable");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
