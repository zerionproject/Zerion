package org.zerionproject.core.settings;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
class SettingsManagerImpl implements SettingsManager {

	private final DatabaseComponent db;

	@Inject
	SettingsManagerImpl(DatabaseComponent db) {
		this.db = db;
	}

	@Override
	public Settings getSettings(String namespace) throws DbException {
		return db.transactionWithResult(true, txn ->
				db.getSettings(txn, namespace));
	}

	@Override
	public Settings getSettings(Transaction txn, String namespace)
			throws DbException {
		return db.getSettings(txn, namespace);
	}

	@Override
	public void mergeSettings(Settings s, String namespace) throws DbException {
		db.transaction(false, txn -> db.mergeSettings(txn, s, namespace));
	}

	@Override
	public void mergeSettings(Transaction txn, Settings s, String namespace)
			throws DbException {
		db.mergeSettings(txn, s, namespace);
	}
}
