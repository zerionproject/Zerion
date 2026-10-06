package org.zerionproject.core.settings;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;
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

	@Nullable
	@Override
	public String getSetting(String namespace, String key)
			throws DbException {
		return db.transactionWithNullableResult(true, txn ->
				db.getSetting(txn, namespace, key));
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

	@Override
	public void mergeSettings(Map<String, Settings> byNamespace)
			throws DbException {
		if (byNamespace.isEmpty()) return;
		db.transaction(false, txn -> {
			for (Map.Entry<String, Settings> e : byNamespace.entrySet()) {
				db.mergeSettings(txn, e.getValue(), e.getKey());
			}
		});
	}

	@Override
	public void deleteSettings(String namespace, Collection<String> keys)
			throws DbException {
		if (keys.isEmpty()) return;
		Collection<String> copy = new ArrayList<>(keys);
		db.transaction(false, txn ->
				db.deleteSettings(txn, namespace, copy));
	}

	@Override
	public void deleteNamespaces(Collection<String> namespaces)
			throws DbException {
		if (namespaces.isEmpty()) return;
		Collection<String> copy = new ArrayList<>(namespaces);
		db.transaction(false, txn ->
				db.deleteSettingsNamespaces(txn, copy));
	}
}
