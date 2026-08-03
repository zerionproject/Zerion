package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration64_65 implements Migration<Connection> {

	private final DatabaseTypes dbTypes;

	Migration64_65(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 64;
	}

	@Override
	public int getEndVersion() {
		return 65;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE pcsSessionState"
					+ " ADD COLUMN mode3FullStateBlob "
					+ dbTypes.replaceTypes("_BINARY"));
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
