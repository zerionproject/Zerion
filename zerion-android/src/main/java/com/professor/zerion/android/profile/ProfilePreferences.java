package com.professor.zerion.android.profile;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
final class ProfilePreferences implements SharedPreferences {

	private final ProfileStorage storage;

	ProfilePreferences(ProfileStorage storage) {
		this.storage = storage;
	}

	@Nullable
	private SharedPreferences target() {
		return storage.settingsOrNull();
	}

	@Override
	public Map<String, ?> getAll() {
		SharedPreferences t = target();
		return t == null ? Collections.emptyMap() : t.getAll();
	}

	@Override
	@Nullable
	public String getString(String key, @Nullable String defValue) {
		SharedPreferences t = target();
		return t == null ? defValue : t.getString(key, defValue);
	}

	@Override
	@Nullable
	public Set<String> getStringSet(String key,
			@Nullable Set<String> defValues) {
		SharedPreferences t = target();
		return t == null ? defValues : t.getStringSet(key, defValues);
	}

	@Override
	public int getInt(String key, int defValue) {
		SharedPreferences t = target();
		return t == null ? defValue : t.getInt(key, defValue);
	}

	@Override
	public long getLong(String key, long defValue) {
		SharedPreferences t = target();
		return t == null ? defValue : t.getLong(key, defValue);
	}

	@Override
	public float getFloat(String key, float defValue) {
		SharedPreferences t = target();
		return t == null ? defValue : t.getFloat(key, defValue);
	}

	@Override
	public boolean getBoolean(String key, boolean defValue) {
		SharedPreferences t = target();
		return t == null ? defValue : t.getBoolean(key, defValue);
	}

	@Override
	public boolean contains(String key) {
		SharedPreferences t = target();
		return t != null && t.contains(key);
	}

	@Override
	public Editor edit() {
		return new DeferredEditor();
	}

	@Override
	public void registerOnSharedPreferenceChangeListener(
			OnSharedPreferenceChangeListener listener) {
		SharedPreferences t = target();
		if (t != null) t.registerOnSharedPreferenceChangeListener(listener);
	}

	@Override
	public void unregisterOnSharedPreferenceChangeListener(
			OnSharedPreferenceChangeListener listener) {
		SharedPreferences t = target();
		if (t != null) t.unregisterOnSharedPreferenceChangeListener(listener);
	}

	private final class DeferredEditor implements Editor {

		private final List<EditStep> steps = new ArrayList<>();

		private Editor add(EditStep step) {
			synchronized (steps) {
				steps.add(step);
			}
			return this;
		}

		@Override
		public Editor putString(String key, @Nullable String value) {
			return add(e -> e.putString(key, value));
		}

		@Override
		public Editor putStringSet(String key, @Nullable Set<String> values) {
			return add(e -> e.putStringSet(key, values));
		}

		@Override
		public Editor putInt(String key, int value) {
			return add(e -> e.putInt(key, value));
		}

		@Override
		public Editor putLong(String key, long value) {
			return add(e -> e.putLong(key, value));
		}

		@Override
		public Editor putFloat(String key, float value) {
			return add(e -> e.putFloat(key, value));
		}

		@Override
		public Editor putBoolean(String key, boolean value) {
			return add(e -> e.putBoolean(key, value));
		}

		@Override
		public Editor remove(String key) {
			return add(e -> e.remove(key));
		}

		@Override
		public Editor clear() {
			return add(Editor::clear);
		}

		@Nullable
		private Editor replay() {
			SharedPreferences t = target();
			if (t == null) return null;
			Editor e = t.edit();
			synchronized (steps) {
				for (EditStep s : steps) s.applyTo(e);
			}
			return e;
		}

		@Override
		public boolean commit() {
			Editor e = replay();
			return e != null && e.commit();
		}

		@Override
		public void apply() {
			Editor e = replay();
			if (e != null) e.apply();
		}
	}

	private interface EditStep {
		void applyTo(Editor e);
	}
}
