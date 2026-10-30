package com.professor.zerion.android.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.tor.TorStateStore;

import javax.annotation.Nullable;

@NotNullByDefault
public final class DeviceTorStateStore implements TorStateStore {

	static final String FILE = "tor_state";
	static final String KEY_STATE = "state";
	static final String KEY_MODIFIED = "modified-ms";
	static final int MAX_STATE_BYTES = 256 * 1024;

	private final Context ctx;
	private final EncryptedLockoutStores.PrefsOpener opener;
	@Nullable
	private final TorStateStore profileStore;

	@Nullable
	private volatile SharedPreferences prefs;

	public DeviceTorStateStore(Context ctx,
			@Nullable TorStateStore profileStore) {
		this(ctx, c -> ZerionEncryptedPrefs.create(c, FILE), profileStore);
	}

	DeviceTorStateStore(Context ctx, EncryptedLockoutStores.PrefsOpener opener,
			@Nullable TorStateStore profileStore) {
		this.ctx = ctx.getApplicationContext() == null ? ctx
				: ctx.getApplicationContext();
		this.opener = opener;
		this.profileStore = profileStore;
	}

	@Override
	@Nullable
	public StoredState load() {
		SharedPreferences p = prefs();
		if (p == null) return null;
		StoredState stored = read(p);
		TorStateStore old = profileStore;
		if (old == null) return stored;
		StoredState left;
		try {
			left = old.load();
		} catch (RuntimeException e) {
			return stored;
		}
		if (left == null) return stored;
		if (stored == null && isStorable(left.bytes) && write(p, left)) {
			stored = left;
		}
		if (stored == null && isStorable(left.bytes)) return left;
		try {
			old.clear();
		} catch (RuntimeException ignored) {
		}
		return stored;
	}

	@Override
	public boolean save(StoredState state) {
		if (!isStorable(state.bytes)) return false;
		SharedPreferences p = prefs();
		return p != null && write(p, state);
	}

	@Override
	public void clear() {
		SharedPreferences p = prefs();
		if (p == null) return;
		try {
			p.edit().remove(KEY_STATE).remove(KEY_MODIFIED).commit();
		} catch (RuntimeException ignored) {
		}
	}

	private static boolean isStorable(byte[] bytes) {
		return bytes.length > 0 && bytes.length <= MAX_STATE_BYTES;
	}

	@Nullable
	private static StoredState read(SharedPreferences p) {
		String encoded;
		long modified;
		try {
			encoded = p.getString(KEY_STATE, null);
			modified = p.getLong(KEY_MODIFIED, 0L);
		} catch (RuntimeException e) {
			return null;
		}
		if (encoded == null || encoded.isEmpty()) return null;
		byte[] bytes;
		try {
			bytes = Base64.decode(encoded, Base64.NO_WRAP);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (!isStorable(bytes)) return null;
		return new StoredState(bytes, modified);
	}

	private static boolean write(SharedPreferences p, StoredState state) {
		try {
			return p.edit()
					.putString(KEY_STATE,
							Base64.encodeToString(state.bytes, Base64.NO_WRAP))
					.putLong(KEY_MODIFIED, state.modifiedMs)
					.commit();
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Nullable
	private SharedPreferences prefs() {
		SharedPreferences p = prefs;
		if (p != null) return p;
		synchronized (this) {
			p = prefs;
			if (p != null) return p;
			try {
				p = opener.open(ctx);
			} catch (RuntimeException keystoreUnavailable) {
				return null;
			}
			prefs = p;
			return p;
		}
	}
}
