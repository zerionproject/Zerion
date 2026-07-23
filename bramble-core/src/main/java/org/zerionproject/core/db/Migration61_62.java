package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration61_62 implements Migration<Connection> {

	@Override
	public int getStartVersion() {
		return 61;
	}

	@Override
	public int getEndVersion() {
		return 62;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();

			s.execute("CREATE TABLE contactCapabilities ("
					+ " contactId INT NOT NULL PRIMARY KEY,"
					+ " capability INTEGER NOT NULL,"
					+ " advertisedAt BIGINT NOT NULL"
					+ ")");

			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
