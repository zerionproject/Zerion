package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration47_48 implements Migration<Connection> {
	@Override
	public int getStartVersion() {
		return 47;
	}

	@Override
	public int getEndVersion() {
		return 48;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE messages"
					+ " ADD COLUMN cleanupTimerDuration BIGINT");
			s.execute("ALTER TABLE messages"
					+ " ADD COLUMN cleanupDeadline BIGINT");
			s.execute("CREATE INDEX IF NOT EXISTS messagesByCleanupDeadline"
					+ " ON messages (cleanupDeadline)");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
