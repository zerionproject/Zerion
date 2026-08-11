package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration50_51 implements Migration<Connection> {
	@Override
	public int getStartVersion() {
		return 50;
	}

	@Override
	public int getEndVersion() {
		return 51;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE pendingContacts"
					+ " ADD COLUMN formatVersion INT NOT NULL DEFAULT 0");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
