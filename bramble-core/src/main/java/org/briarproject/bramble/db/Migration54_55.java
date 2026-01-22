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
 * Migration to add Post-Compromise Security (PCS) tables.
 * <p>
 * Creates two tables:
 * <ul>
 *   <li>pcsSessionState - Stores per-contact, per-direction chain keys and counters</li>
 *   <li>pcsSkippedKeys - Stores skipped message keys for out-of-order delivery</li>
 * </ul>
 */
class Migration54_55 implements Migration<Connection> {

	private static final Logger LOG = getLogger(Migration54_55.class.getName());

	private final DatabaseTypes dbTypes;

	Migration54_55(DatabaseTypes dbTypes) {
		this.dbTypes = dbTypes;
	}

	@Override
	public int getStartVersion() {
		return 54;
	}

	@Override
	public int getEndVersion() {
		return 55;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		Statement s = null;
		try {
			s = txn.createStatement();

			// Create PCS session state table
			// Stores the current chain key and message counter per contact per direction
			s.execute(dbTypes.replaceTypes(
					"CREATE TABLE pcsSessionState"
							+ " (contactId INT NOT NULL,"
							+ " direction SMALLINT NOT NULL," // 0=send, 1=receive
							+ " chainKey _SECRET NOT NULL,"
							+ " messageNumber INT NOT NULL,"
							+ " previousChainLength INT NOT NULL,"
							+ " PRIMARY KEY (contactId, direction),"
							+ " FOREIGN KEY (contactId)"
							+ " REFERENCES contacts (contactId)"
							+ " ON DELETE CASCADE)"));

			// Create PCS skipped keys table
			// Stores message keys for out-of-order message delivery
			s.execute(dbTypes.replaceTypes(
					"CREATE TABLE pcsSkippedKeys"
							+ " (contactId INT NOT NULL,"
							+ " direction SMALLINT NOT NULL," // 0=send, 1=receive
							+ " messageNumber INT NOT NULL,"
							+ " messageKey _SECRET NOT NULL,"
							+ " timestamp BIGINT NOT NULL,"
							+ " PRIMARY KEY (contactId, direction, messageNumber),"
							+ " FOREIGN KEY (contactId)"
							+ " REFERENCES contacts (contactId)"
							+ " ON DELETE CASCADE)"));

			// Create index for efficient pruning of expired keys
			s.execute("CREATE INDEX pcsSkippedKeysByTimestamp"
					+ " ON pcsSkippedKeys (contactId, timestamp)");

		} catch (SQLException e) {
			tryToClose(s, LOG, WARNING);
			throw new DbException(e);
		}
	}
}
