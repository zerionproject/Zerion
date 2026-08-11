package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration54_55 implements Migration<Connection> {
	private final DatabaseTypes dbTypes;

	Migration54_55(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 54;
	}

	@Override
	public int getEndVersion() {
		return 55;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute(dbTypes.replaceTypes(
					"CREATE TABLE pcsSessionState"
							+ " (contactId INT NOT NULL,"
							+ " direction SMALLINT NOT NULL,"
							+ " chainKey _SECRET NOT NULL,"
							+ " messageNumber INT NOT NULL,"
							+ " previousChainLength INT NOT NULL,"
							+ " PRIMARY KEY (contactId, direction),"
							+ " FOREIGN KEY (contactId)"
							+ " REFERENCES contacts (contactId)"
							+ " ON DELETE CASCADE)"));
			s.execute(dbTypes.replaceTypes(
					"CREATE TABLE pcsSkippedKeys"
							+ " (contactId INT NOT NULL,"
							+ " direction SMALLINT NOT NULL,"
							+ " messageNumber INT NOT NULL,"
							+ " messageKey _SECRET NOT NULL,"
							+ " timestamp BIGINT NOT NULL,"
							+ " PRIMARY KEY (contactId, direction, messageNumber),"
							+ " FOREIGN KEY (contactId)"
							+ " REFERENCES contacts (contactId)"
							+ " ON DELETE CASCADE)"));
			s.execute("CREATE INDEX pcsSkippedKeysByTimestamp"
					+ " ON pcsSkippedKeys (contactId, timestamp)");

		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
