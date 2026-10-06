package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcDatabase.DROP_INDEX_MESSAGE_METADATA_BY_GROUP_ID_STATE;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration70_71 implements Migration<Connection> {

	@Override
	public int getStartVersion() {
		return 70;
	}

	@Override
	public int getEndVersion() {
		return 71;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute(DROP_INDEX_MESSAGE_METADATA_BY_GROUP_ID_STATE);
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
