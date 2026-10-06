package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcDatabase.INDEX_MESSAGE_METADATA_BY_GROUP_ID_STATE_MESSAGE_ID;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration68_69 implements Migration<Connection> {

	@Override
	public int getStartVersion() {
		return 68;
	}

	@Override
	public int getEndVersion() {
		return 69;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute(INDEX_MESSAGE_METADATA_BY_GROUP_ID_STATE_MESSAGE_ID);
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
