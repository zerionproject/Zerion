package org.briarproject.bramble.api.settings;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface SettingsManager {

	Settings getSettings(String namespace) throws DbException;

	Settings getSettings(Transaction txn, String namespace) throws DbException;

	void mergeSettings(Settings s, String namespace) throws DbException;

	void mergeSettings(Transaction txn, Settings s, String namespace)
			throws DbException;
}
