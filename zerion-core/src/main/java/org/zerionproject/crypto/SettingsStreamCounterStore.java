package org.zerionproject.crypto;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.wire.StreamCounterStore;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

/**
 * Durable {@link StreamCounterStore} backed by the SQLCipher-encrypted
 * key-value settings store. Each per-(contact, direction) stream high-water mark
 * is one setting in a dedicated namespace. {@link SettingsManager#mergeSettings}
 * commits a database transaction before returning, which gives the
 * store-before-use durability the stream counter relies on: a freshly allocated
 * stream id is persisted before it is ever handed to an encrypter, so a crash
 * can never let the same {@code (rootKey, streamId)} nonce space repeat.
 */
@ThreadSafe
@NotNullByDefault
public class SettingsStreamCounterStore implements StreamCounterStore {

	private static final String NAMESPACE =
			"org.zerionproject.zwf.streamCounter";

	private final SettingsManager settingsManager;

	@Inject
	SettingsStreamCounterStore(SettingsManager settingsManager) {
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

	private static String key(int contactId, int direction) {
		return contactId + "." + direction;
	}

	/**
	 * Unchecked wrapper so a persistence failure aborts stream-id allocation
	 * rather than being silently swallowed — allocating a stream id whose
	 * high-water mark did not persist would be unsafe.
	 */
	static class StreamCounterPersistenceException extends RuntimeException {
		StreamCounterPersistenceException(Throwable cause) {
			super(cause);
		}
	}
}
