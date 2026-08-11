package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration58_59 implements Migration<Connection> {
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
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
