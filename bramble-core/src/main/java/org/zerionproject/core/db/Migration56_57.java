package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration56_57 implements Migration<Connection> {
	private final DatabaseTypes dbTypes;

	Migration56_57(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 56;
	}

	@Override
	public int getEndVersion() {
		return 57;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute("ALTER TABLE pcsSessionState"
					+ " ADD COLUMN mode2Enabled BOOLEAN NOT NULL DEFAULT FALSE");
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSessionState"
							+ " ADD COLUMN rootKey _SECRET"));
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSessionState"
							+ " ADD COLUMN dhPrivateKey _SECRET"));
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSessionState"
							+ " ADD COLUMN dhPublicKey _BINARY"));
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSessionState"
							+ " ADD COLUMN dhRemotePublicKey _BINARY"));
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSkippedKeys"
							+ " ADD COLUMN chainId _HASH"));
			s.execute("CREATE INDEX pcsSkippedKeysByChainId"
					+ " ON pcsSkippedKeys (chainId, messageNumber)");

		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
