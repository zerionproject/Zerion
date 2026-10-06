package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

class Migration69_70 implements Migration<Connection> {

	@Override
	public int getStartVersion() {
		return 69;
	}

	@Override
	public int getEndVersion() {
		return 70;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		PreparedStatement ps = null;
		Statement s = null;
		try {
			ps = txn.prepareStatement("UPDATE contacts SET verified = ?"
					+ " WHERE handshakePublicKey IS NOT NULL");
			ps.setBoolean(1, false);
			ps.executeUpdate();
			ps.close();
			ps = null;
			s = txn.createStatement();
			s.executeUpdate("DELETE FROM pcsSkippedKeys");
			s.executeUpdate("DELETE FROM pqRatchetState");
			s.executeUpdate("UPDATE pcsSessionState SET dhPrivateKey = NULL,"
					+ " dhPublicKey = NULL, dhRemotePublicKey = NULL,"
					+ " mode3FullStateBlob = NULL");
			s.close();
		} catch (SQLException e) {
			tryToClose(ps);
			tryToClose(s);
			throw new DbException(e);
		}
	}
}
