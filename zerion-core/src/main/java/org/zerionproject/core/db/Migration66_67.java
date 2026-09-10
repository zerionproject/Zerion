package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

/**
 * Adds the pairing key snapshot to pending contacts. Each pending contact
 * remembers the local handshake key pair that was current when it was
 * created, so the identity handshake keys can rotate after every successful
 * contact addition without breaking pairings that are still in flight.
 * Existing rows are backfilled with the current identity keys, because they
 * were created under those keys: without the backfill, the first rotation
 * after the upgrade would strand every pre-upgrade pending pairing on keys
 * that no longer exist.
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
		PreparedStatement ps = null;
		ResultSet rs = null;
		try {
			s = txn.createStatement();
			s.execute(dbTypes.replaceTypes("ALTER TABLE pendingContacts"
					+ " ADD COLUMN ourPublicKey _BINARY"));
			s.execute(dbTypes.replaceTypes("ALTER TABLE pendingContacts"
					+ " ADD COLUMN ourPrivateKey _BINARY"));
			byte[] pub = null;
			byte[] priv = null;
			rs = s.executeQuery("SELECT hybridHandshakePublicKey,"
					+ " hybridHandshakePrivateKey FROM identities");
			if (rs.next()) {
				pub = rs.getBytes(1);
				priv = rs.getBytes(2);
			}
			rs.close();
			s.close();
			if (pub != null && priv != null) {
				ps = txn.prepareStatement("UPDATE pendingContacts"
						+ " SET ourPublicKey = ?, ourPrivateKey = ?");
				ps.setBytes(1, pub);
				ps.setBytes(2, priv);
				ps.executeUpdate();
				ps.close();
			}
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(ps);
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
