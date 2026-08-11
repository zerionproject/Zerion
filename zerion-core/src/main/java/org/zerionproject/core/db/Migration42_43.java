package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration42_43 implements Migration<Connection> {
	private final DatabaseTypes dbTypes;

	Migration42_43(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 42;
	}

	@Override
	public int getEndVersion() {
		return 43;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute(dbTypes.replaceTypes("ALTER TABLE localAuthors"
					+ " ADD COLUMN handshakePublicKey _BINARY"));
			s.execute(dbTypes.replaceTypes("ALTER TABLE localAuthors"
					+ " ADD COLUMN handshakePrivateKey _BINARY"));
			s.execute(dbTypes.replaceTypes("ALTER TABLE contacts"
					+ " ADD COLUMN handshakePublicKey _BINARY"));
			s.execute("ALTER TABLE contacts"
					+ " DROP COLUMN active");
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
