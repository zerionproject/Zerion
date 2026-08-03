package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration46_47 implements Migration<Connection> {
	private final DatabaseTypes dbTypes;

	Migration46_47(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 46;
	}

	@Override
	public int getEndVersion() {
		return 47;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute(dbTypes.replaceTypes("ALTER TABLE contacts"
					+ " ADD COLUMN syncVersions"
					+ " _BINARY DEFAULT '00' NOT NULL"));
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
