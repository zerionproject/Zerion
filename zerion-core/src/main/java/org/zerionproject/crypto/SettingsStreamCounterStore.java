package org.zerionproject.crypto;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.wire.StreamCounterStore;

import java.util.function.BooleanSupplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.zerionproject.wire.ZwfConstants.DIRECTION_RECV;
import static org.zerionproject.wire.ZwfConstants.DIRECTION_SEND;

@ThreadSafe
@NotNullByDefault
public class SettingsStreamCounterStore implements StreamCounterStore {

	private static final String NAMESPACE =
			"org.zerionproject.zwf.streamCounter";

	@Nullable
	private final DatabaseComponent db;
	private final SettingsManager settingsManager;

	@Inject
	SettingsStreamCounterStore(DatabaseComponent db,
			SettingsManager settingsManager) {
		this.db = db;
		this.settingsManager = settingsManager;
	}

	SettingsStreamCounterStore(SettingsManager settingsManager) {
		this.db = null;
		this.settingsManager = settingsManager;
	}

	@Override
	public long loadHighWater(int contactId, int direction) {
		try {
			Settings s = settingsManager.getSettings(NAMESPACE);
			return s.getLong(key(contactId, direction), 0);
		} catch (DbException e) {
			throw new StreamCounterPersistenceException(e);
		}
	}

	@Override
	public void storeHighWater(int contactId, int direction, long highWater) {
		try {
			Settings s = new Settings();
			s.putLong(key(contactId, direction), highWater);
			settingsManager.mergeSettings(s, NAMESPACE);
		} catch (DbException e) {
			throw new StreamCounterPersistenceException(e);
		}
	}

	@Override
	public boolean storeHighWaterIf(int contactId, int direction,
			long highWater, BooleanSupplier stillCurrent) {
		if (db == null) {
			return StreamCounterStore.super.storeHighWaterIf(contactId,
					direction, highWater, stillCurrent);
		}
		try {
			return db.transactionWithResult(false, txn -> {
				if (!stillCurrent.getAsBoolean()) return false;
				Settings s = new Settings();
				s.putLong(key(contactId, direction), highWater);
				settingsManager.mergeSettings(txn, s, NAMESPACE);
				return true;
			});
		} catch (DbException e) {
			throw new StreamCounterPersistenceException(e);
		}
	}

	void clearHighWater(Transaction txn, int contactId) throws DbException {
		Settings stored = settingsManager.getSettings(txn, NAMESPACE);
		Settings cleared = new Settings();
		for (int direction : new int[] {DIRECTION_SEND, DIRECTION_RECV}) {
			String k = key(contactId, direction);
			if (stored.get(k) != null) cleared.putLong(k, 0);
		}
		if (!cleared.isEmpty()) {
			settingsManager.mergeSettings(txn, cleared, NAMESPACE);
		}
	}

	private static String key(int contactId, int direction) {
		return contactId + "." + direction;
	}

	static class StreamCounterPersistenceException extends RuntimeException {
		StreamCounterPersistenceException(Throwable cause) {
			super(cause);
		}
	}
}
