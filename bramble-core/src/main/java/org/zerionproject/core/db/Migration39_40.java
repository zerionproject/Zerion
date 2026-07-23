package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration39_40 implements Migration<Connection> {
	@Override
	public int getStartVersion() {
		return 39;
	}

	@Override
	public int getEndVersion() {
		return 40;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE statuses"
					+ " ADD eta BIGINT");
			s.execute("UPDATE statuses SET eta = 0");
			s.execute("ALTER TABLE statuses"
					+ " ALTER COLUMN eta"
					+ " SET NOT NULL");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
