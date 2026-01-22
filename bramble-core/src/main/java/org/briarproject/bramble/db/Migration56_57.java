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
 * Migration to add PCS Mode 2 (DH Ratchet) columns to pcsSessionState table.
 * <p>
 * Mode 2 requires storing:
 * <ul>
 *   <li>rootKey - The KDF_RK root key for DH ratchet</li>
 *   <li>dhPrivateKey - Our current DH private key (X25519)</li>
 *   <li>dhPublicKey - Our current DH public key</li>
 *   <li>dhRemotePublicKey - Their current DH public key (nullable)</li>
 *   <li>mode2Enabled - Flag indicating Mode 2 is active</li>
 * </ul>
 * Existing Mode 1 sessions will have mode2Enabled=false and null DH columns.
 */
class Migration56_57 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration56_57.class.getName());

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

			// Add Mode 2 flag - existing sessions are Mode 1 only
			s.execute("ALTER TABLE pcsSessionState"
					+ " ADD COLUMN mode2Enabled BOOLEAN NOT NULL DEFAULT FALSE");

			// Add root key for KDF_RK (nullable for Mode 1 sessions)
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSessionState"
							+ " ADD COLUMN rootKey _SECRET"));

			// Add our DH private key (nullable for Mode 1 sessions)
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSessionState"
							+ " ADD COLUMN dhPrivateKey _SECRET"));

			// Add our DH public key (nullable for Mode 1 sessions)
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSessionState"
							+ " ADD COLUMN dhPublicKey _BINARY"));

			// Add their DH public key (nullable - may not have received yet)
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSessionState"
							+ " ADD COLUMN dhRemotePublicKey _BINARY"));

			// Update pcsSkippedKeys to include chainId for Mode 2
			// In Mode 2, skipped keys are identified by (chainId, messageNumber)
			// where chainId incorporates the DH public key
			s.execute(dbTypes.replaceTypes(
					"ALTER TABLE pcsSkippedKeys"
							+ " ADD COLUMN chainId _HASH"));

			// Create index for chainId-based lookups
			s.execute("CREATE INDEX pcsSkippedKeysByChainId"
					+ " ON pcsSkippedKeys (chainId, messageNumber)");

		} catch (SQLException e) {
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
