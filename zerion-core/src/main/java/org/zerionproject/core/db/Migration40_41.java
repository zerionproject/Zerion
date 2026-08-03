package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration40_41 implements Migration<Connection> {
	private final DatabaseTypes dbTypes;

	Migration40_41(DatabaseTypes databaseTypes) {
		this.dbTypes = databaseTypes;
	}

	@Override
	public int getStartVersion() {
		return 40;
	}

	@Override
	public int getEndVersion() {
		return 41;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE contacts"
					+ dbTypes.replaceTypes(" ADD alias _STRING"));
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
