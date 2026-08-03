package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration38_39 implements Migration<Connection> {
	@Override
	public int getStartVersion() {
		return 38;
	}

	@Override
	public int getEndVersion() {
		return 39;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE outgoingKeys"
					+ " ALTER COLUMN contactId"
					+ " SET NOT NULL");
			s.execute("ALTER TABLE incomingKeys"
					+ " ALTER COLUMN contactId"
					+ " SET NOT NULL");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
