package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration41_42 implements Migration<Connection> {
	private final DatabaseTypes dbTypes;

	Migration41_42(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 41;
	}

	@Override
	public int getEndVersion() {
		return 42;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE outgoingKeys"
					+ " ALTER COLUMN rotationPeriod"
					+ " RENAME TO timePeriod");
			s.execute("ALTER TABLE incomingKeys"
					+ " ALTER COLUMN rotationPeriod"
					+ " RENAME TO timePeriod");
			s.execute("ALTER TABLE incomingKeys"
					+ " DROP COLUMN contactId");
			s.execute(dbTypes.replaceTypes("CREATE TABLE pendingContacts"
					+ " (pendingContactId _HASH NOT NULL,"
					+ " publicKey _BINARY NOT NULL,"
					+ " alias _STRING NOT NULL,"
					+ " state INT NOT NULL,"
					+ " timestamp BIGINT NOT NULL,"
					+ " PRIMARY KEY (pendingContactId))"));
			s.execute(dbTypes.replaceTypes("CREATE TABLE outgoingHandshakeKeys"
					+ " (transportId _STRING NOT NULL,"
					+ " keySetId _COUNTER,"
					+ " timePeriod BIGINT NOT NULL,"
					+ " contactId INT,"
					+ " pendingContactId _HASH,"
					+ " rootKey _SECRET NOT NULL,"
					+ " alice BOOLEAN NOT NULL,"
					+ " tagKey _SECRET NOT NULL,"
					+ " headerKey _SECRET NOT NULL,"
					+ " stream BIGINT NOT NULL,"
					+ " PRIMARY KEY (transportId, keySetId),"
					+ " FOREIGN KEY (transportId)"
					+ " REFERENCES transports (transportId)"
					+ " ON DELETE CASCADE,"
					+ " UNIQUE (keySetId),"
					+ " FOREIGN KEY (contactId)"
					+ " REFERENCES contacts (contactId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (pendingContactId)"
					+ " REFERENCES pendingContacts (pendingContactId)"
					+ " ON DELETE CASCADE)"));
			s.execute(dbTypes.replaceTypes("CREATE TABLE incomingHandshakeKeys"
					+ " (transportId _STRING NOT NULL,"
					+ " keySetId INT NOT NULL,"
					+ " timePeriod BIGINT NOT NULL,"
					+ " tagKey _SECRET NOT NULL,"
					+ " headerKey _SECRET NOT NULL,"
					+ " base BIGINT NOT NULL,"
					+ " bitmap _BINARY NOT NULL,"
					+ " periodOffset INT NOT NULL,"
					+ " PRIMARY KEY (transportId, keySetId, periodOffset),"
					+ " FOREIGN KEY (transportId)"
					+ " REFERENCES transports (transportId)"
					+ " ON DELETE CASCADE,"
					+ " FOREIGN KEY (keySetId)"
					+ " REFERENCES outgoingHandshakeKeys (keySetId)"
					+ " ON DELETE CASCADE)"));
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
