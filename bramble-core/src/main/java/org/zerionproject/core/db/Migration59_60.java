package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

import java.sql.Connection;

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
	}
}
