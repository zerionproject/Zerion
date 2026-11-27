package org.zerionproject.transport;

import org.bouncycastle.util.encoders.Base64;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.tor.TorStateStore;

import java.util.Arrays;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class TorStateSettingsStore implements TorStateStore {

	public static final String NAMESPACE = "tor-state";
	static final String KEY_STATE = "state";
	static final String KEY_MODIFIED = "modified-ms";

	private final SettingsManager settingsManager;

	@Inject
	public TorStateSettingsStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	@Override
	@Nullable
	public StoredState load() {
		Settings s;
		try {
			s = settingsManager.getSettings(NAMESPACE);
		} catch (DbException e) {
			return null;
		}
		String encoded = s.get(KEY_STATE);
		if (encoded == null || encoded.isEmpty()) return null;
		byte[] bytes;
		try {
			bytes = Base64.decode(encoded);
		} catch (RuntimeException e) {
			return null;
		}
		return new StoredState(bytes, s.getLong(KEY_MODIFIED, 0L));
	}

	@Override
	public boolean save(StoredState state) {
		Settings s = new Settings();
		s.put(KEY_STATE, Base64.toBase64String(state.bytes));
		s.putLong(KEY_MODIFIED, state.modifiedMs);
		try {
			settingsManager.mergeSettings(s, NAMESPACE);
			return true;
		} catch (DbException e) {
			return false;
		}
	}

	@Override
	public void clear() {
		try {
			settingsManager.deleteSettings(NAMESPACE,
					Arrays.asList(KEY_STATE, KEY_MODIFIED));
		} catch (DbException ignored) {
		}
	}
}
