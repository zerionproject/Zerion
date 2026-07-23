package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration43_44 implements Migration<Connection> {
	private final DatabaseTypes dbTypes;

	Migration43_44(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 43;
	}

	@Override
	public int getEndVersion() {
		return 44;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("DROP TABLE outgoingHandshakeKeys");
			s.execute("DROP TABLE incomingHandshakeKeys");
			s.execute("ALTER TABLE outgoingKeys"
					+ " ALTER COLUMN contactId DROP NOT NULL");
			s.execute(dbTypes.replaceTypes("ALTER TABLE outgoingKeys"
					+ " ADD COLUMN pendingContactId _HASH"));
			s.execute("ALTER TABLE outgoingKeys"
					+ " ADD FOREIGN KEY (pendingContactId)"
					+ " REFERENCES pendingContacts (pendingContactId)"
					+ " ON DELETE CASCADE");
			s.execute(dbTypes.replaceTypes("ALTER TABLE outgoingKeys"
					+ " ADD COLUMN rootKey _SECRET"));
			s.execute("ALTER TABLE outgoingKeys"
					+ " ADD COLUMN alice BOOLEAN");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
