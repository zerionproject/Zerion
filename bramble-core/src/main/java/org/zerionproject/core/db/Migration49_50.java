package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration49_50 implements Migration<Connection> {
	@Override
	public int getStartVersion() {
		return 49;
	}

	@Override
	public int getEndVersion() {
		return 50;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE statuses"
					+ " ALTER COLUMN eta"
					+ " RENAME TO maxLatency");
			s.execute("ALTER TABLE statuses"
					+ " ALTER COLUMN maxLatency"
					+ " SET NULL");
			s.execute("UPDATE statuses SET maxLatency = NULL");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
