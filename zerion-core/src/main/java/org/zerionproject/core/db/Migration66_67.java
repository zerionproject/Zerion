package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

/**
 * Adds the pairing key snapshot to pending contacts. Each pending contact
 * remembers the local handshake key pair that was current when it was
 * created, so the identity handshake keys can rotate after every successful
 * contact addition without breaking pairings that are still in flight.
 * Existing rows keep null and fall back to the current identity keys.
 */
class Migration66_67 implements Migration<Connection> {

	private final DatabaseTypes dbTypes;

	Migration66_67(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 66;
	}

	@Override
	public int getEndVersion() {
		return 67;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute(dbTypes.replaceTypes("ALTER TABLE pendingContacts"
					+ " ADD COLUMN ourPublicKey _BINARY"));
			s.execute(dbTypes.replaceTypes("ALTER TABLE pendingContacts"
					+ " ADD COLUMN ourPrivateKey _BINARY"));
		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
