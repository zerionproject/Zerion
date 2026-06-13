package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.briarproject.bramble.db.JdbcUtils.tryToClose;

class Migration65_66 implements Migration<Connection> {

	Migration65_66() {
	}

	@Override
	public int getStartVersion() {
		return 65;
	}

	@Override
	public int getEndVersion() {
		return 66;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("CREATE INDEX IF NOT EXISTS messagesByTemporary"
					+ " ON messages (temporary)");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
