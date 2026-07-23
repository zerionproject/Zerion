package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration48_49 implements Migration<Connection> {
	@Override
	public int getStartVersion() {
		return 48;
	}

	@Override
	public int getEndVersion() {
		return 49;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE transports"
					+ " ALTER COLUMN maxLatency"
					+ " SET DATA TYPE BIGINT");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
