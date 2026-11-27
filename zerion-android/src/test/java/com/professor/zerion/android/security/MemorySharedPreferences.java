package com.professor.zerion.android.security;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

public final class MemorySharedPreferences implements SharedPreferences {

	public final Map<String, Object> values = new HashMap<>();
	public boolean refuseCommits = false;
	public boolean reportCommitsAsFailed = false;
	@Nullable
	public Runnable afterCommit = null;
	public int commits = 0;

	@Override
	public Map<String, ?> getAll() {
		return new HashMap<>(values);
	}

	@Override
	@Nullable
	public String getString(String key, @Nullable String defValue) {
		Object v = values.get(key);
		return v instanceof String ? (String) v : defValue;
	}

	@Override
	@Nullable
	@SuppressWarnings("unchecked")
	public Set<String> getStringSet(String key,
			@Nullable Set<String> defValues) {
		Object v = values.get(key);
		return v instanceof Set ? new HashSet<>((Set<String>) v) : defValues;
	}

	@Override
	public int getInt(String key, int defValue) {
		Object v = values.get(key);
		return v instanceof Integer ? (Integer) v : defValue;
	}

	@Override
	public long getLong(String key, long defValue) {
		Object v = values.get(key);
		return v instanceof Long ? (Long) v : defValue;
	}

	@Override
	public float getFloat(String key, float defValue) {
		Object v = values.get(key);
		return v instanceof Float ? (Float) v : defValue;
	}

	@Override
	public boolean getBoolean(String key, boolean defValue) {
		Object v = values.get(key);
		return v instanceof Boolean ? (Boolean) v : defValue;
	}

	@Override
	public boolean contains(String key) {
		return values.containsKey(key);
	}

	@Override
	public Editor edit() {
		return new MemoryEditor();
	}

	@Override
	public void registerOnSharedPreferenceChangeListener(
			OnSharedPreferenceChangeListener listener) {
	}

	@Override
	public void unregisterOnSharedPreferenceChangeListener(
			OnSharedPreferenceChangeListener listener) {
	}

	private final class MemoryEditor implements Editor {

		private final Map<String, Object> puts = new HashMap<>();
		private final Set<String> removals = new HashSet<>();
		private boolean clear = false;

		private Editor put(String key, @Nullable Object value) {
			if (value == null) {
				puts.remove(key);
				removals.add(key);
			} else {
				removals.remove(key);
				puts.put(key, value);
			}
			return this;
		}

		@Override
		public Editor putString(String key, @Nullable String value) {
			return put(key, value);
		}

		@Override
		public Editor putStringSet(String key, @Nullable Set<String> value) {
			return put(key, value == null ? null : new HashSet<>(value));
		}

		@Override
		public Editor putInt(String key, int value) {
			return put(key, value);
		}

		@Override
		public Editor putLong(String key, long value) {
			return put(key, value);
		}

		@Override
		public Editor putFloat(String key, float value) {
			return put(key, value);
		}

		@Override
		public Editor putBoolean(String key, boolean value) {
			return put(key, value);
		}

		@Override
		public Editor remove(String key) {
			puts.remove(key);
			removals.add(key);
			return this;
		}

		@Override
		public Editor clear() {
			clear = true;
			return this;
		}

		@Override
		public boolean commit() {
			commits++;
			if (refuseCommits) return false;
			if (clear) values.clear();
			for (String k : removals) values.remove(k);
			values.putAll(puts);
			Runnable hook = afterCommit;
			if (hook != null) hook.run();
			return !reportCommitsAsFailed;
		}

		@Override
		public void apply() {
			commit();
		}
	}
}
