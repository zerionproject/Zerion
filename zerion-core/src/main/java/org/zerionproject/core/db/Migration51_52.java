package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration51_52 implements Migration<Connection> {
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
			s.execute("ALTER TABLE contacts"
					+ " ADD COLUMN postQuantum BOOLEAN NOT NULL DEFAULT FALSE");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
