package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration57_58 implements Migration<Connection> {
	private final DatabaseTypes dbTypes;

	Migration57_58(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 57;
	}

	@Override
	public int getEndVersion() {
		return 58;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();

			s.execute(dbTypes.replaceTypes(
					"CREATE TABLE pqRatchetState ("
							+ " contactId INT NOT NULL,"
							+ " currentEpoch BIGINT NOT NULL DEFAULT 0,"
							+ " epochStartTime BIGINT NOT NULL,"
							+ " messagesSinceEpoch INT NOT NULL DEFAULT 0,"
							+ " state INT NOT NULL DEFAULT 0,"
							+ " isInitiator BOOLEAN NOT NULL DEFAULT FALSE,"
							+ " chunksSent INT NOT NULL DEFAULT 0,"
							+ " chunksReceived INT NOT NULL DEFAULT 0,"
							+ " ourEkSeed _BINARY,"
							+ " ourEkVector _BINARY,"
							+ " ourDecapsKey _SECRET,"
							+ " theirEkSeed _BINARY,"
							+ " theirEkHash _BINARY,"
							+ " theirEkVector _BINARY,"
							+ " ciphertext _BINARY,"
							+ " pendingChunks _BINARY,"
							+ " PRIMARY KEY (contactId),"
							+ " FOREIGN KEY (contactId)"
							+ " REFERENCES contacts (contactId)"
							+ " ON DELETE CASCADE)"));

			s.execute("ALTER TABLE pcsSessionState"
					+ " ADD COLUMN mode3Enabled BOOLEAN NOT NULL DEFAULT FALSE");

			s.execute("ALTER TABLE pcsSessionState"
					+ " ADD COLUMN pqEpoch BIGINT NOT NULL DEFAULT 0");

		} catch (SQLException e) {
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
