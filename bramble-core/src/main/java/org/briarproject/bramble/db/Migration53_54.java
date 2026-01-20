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
 * Migration to auto-verify all existing contacts.
 * <p>
 * Since Zerion no longer uses QR code verification (all handshakes are done
 * securely over Tor), contacts should be automatically marked as verified
 * after successful key exchange.
 */
class Migration53_54 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration53_54.class.getName());

	@Override
	public int getStartVersion() {
		return 53;
	}

	@Override
	public int getEndVersion() {
		return 54;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();
			// Mark all existing contacts as verified since Zerion uses
			// secure Tor-based handshakes (no QR verification needed)
			s.execute("UPDATE contacts SET verified = TRUE");
		} catch (SQLException e) {
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
