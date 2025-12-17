package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.db.JdbcUtils.tryToClose;

/**
 * Migration to add hybrid (post-quantum) handshake key columns to localAuthors table.
 * This enables persistent storage of hybrid X25519+ML-KEM-768 keys for
 * post-quantum secure Zerion-to-Zerion communication.
 */
class Migration52_53 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration52_53.class.getName());

	@Override
	public int getStartVersion() {
		return 52;
	}

	@Override
	public int getEndVersion() {
		return 53;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			// Add hybrid public key column (NULL for legacy identities)
			// Hybrid keys are ~1216 bytes (X25519 32B + ML-KEM-768 1184B)
			s.execute("ALTER TABLE localAuthors"
					+ " ADD COLUMN hybridHandshakePublicKey BINARY");
			// Add hybrid private key column (NULL for legacy identities)
			// Hybrid private keys are ~2432 bytes
			s.execute("ALTER TABLE localAuthors"
					+ " ADD COLUMN hybridHandshakePrivateKey BINARY");
		} catch (SQLException e) {
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
