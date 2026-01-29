package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.briarproject.bramble.db.JdbcUtils.tryToClose;

class Migration44_45 implements Migration<Connection> {
	@Override
	public int getStartVersion() {
		return 44;
	}

	@Override
	public int getEndVersion() {
		return 45;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE pendingContacts DROP COLUMN state");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}