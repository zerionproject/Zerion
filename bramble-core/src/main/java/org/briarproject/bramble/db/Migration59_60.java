package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

import java.sql.Connection;

/**
 * No-op migration for schema version compatibility.
 * All required tables already exist from earlier migrations.
 */
class Migration59_60 implements Migration<Connection> {

	@Override
	public int getStartVersion() {
		return 59;
	}

	@Override
	public int getEndVersion() {
		return 60;
	}

	@Override
	public void migrate(Connection txn) throws DbException {
		// No-op: All PCS tables already exist from migrations 54-58
	}
}
