package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DbException;

interface Migration<T> {

	int getStartVersion();

	int getEndVersion();

	void migrate(T txn) throws DbException;
}
