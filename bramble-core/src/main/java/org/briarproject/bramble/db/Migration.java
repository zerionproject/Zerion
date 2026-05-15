package org.briarproject.bramble.db;

import org.briarproject.bramble.api.db.DbException;

interface Migration<T> {

	int getStartVersion();

	int getEndVersion();

	void migrate(T txn) throws DbException;
}
