package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.zerionproject.core.db.JdbcUtils.tryToClose;

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
		addColumnIfMissing(txn, "ourPublicKey");
		addColumnIfMissing(txn, "ourPrivateKey");
		backfillBestEffort(txn);
	}

	private void addColumnIfMissing(Connection txn, String column)
			throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			s.execute(dbTypes.replaceTypes("ALTER TABLE pendingContacts"
					+ " ADD COLUMN " + column + " _BINARY"));
			s.close();
		} catch (SQLException e) {
			tryToClose(s);
			if (columnExists(txn, column)) return;
			throw new DbException(e);
		}
	}

	private boolean columnExists(Connection txn, String column) {
		PreparedStatement ps = null;
		try {
			ps = txn.prepareStatement("SELECT " + column
					+ " FROM pendingContacts WHERE 1 = 0");
			ResultSet rs = ps.executeQuery();
			rs.close();
			ps.close();
			return true;
		} catch (SQLException e) {
			tryToClose(ps);
			return false;
		}
	}

	private void backfillBestEffort(Connection txn) {
		PreparedStatement query = null;
		PreparedStatement update = null;
		ResultSet rs = null;
		try {
			query = txn.prepareStatement("SELECT hybridHandshakePublicKey,"
					+ " hybridHandshakePrivateKey FROM localAuthors");
			rs = query.executeQuery();
			byte[] pub = null;
			byte[] priv = null;
			if (rs.next()) {
				pub = rs.getBytes(1);
				priv = rs.getBytes(2);
			}
			rs.close();
			query.close();
			if (pub == null || priv == null) return;
			update = txn.prepareStatement("UPDATE pendingContacts"
					+ " SET ourPublicKey = ?, ourPrivateKey = ?"
					+ " WHERE ourPublicKey IS NULL");
			update.setBytes(1, pub);
			update.setBytes(2, priv);
			update.executeUpdate();
			update.close();
		} catch (SQLException e) {
			tryToClose(rs);
			tryToClose(query);
			tryToClose(update);
		}
	}
}
