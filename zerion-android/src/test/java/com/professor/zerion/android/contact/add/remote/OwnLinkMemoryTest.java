package com.professor.zerion.android.contact.add.remote;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class OwnLinkMemoryTest {

	private static final long NOW = 1_700_000_000_000L;

	@Test
	public void theLastSharedLinkIsBoundUntilItExpires() {
		OwnLinkMemory memory = new OwnLinkMemory(new MemPrefs());
		assertEquals("current", memory.linkToBind("current", NOW));
		memory.shared("shared", NOW);
		assertEquals("shared", memory.linkToBind("rotated", NOW + 60_000));
		assertEquals("shared", memory.lastShared(
				NOW + OwnLinkMemory.SHARED_LINK_LIFETIME_MS));
		assertNull(memory.lastShared(
				NOW + OwnLinkMemory.SHARED_LINK_LIFETIME_MS + 1));
		assertEquals("rotated", memory.linkToBind("rotated",
				NOW + OwnLinkMemory.SHARED_LINK_LIFETIME_MS + 1));
		memory.shared("shared", NOW);
		memory.forget();
		assertNull(memory.lastShared(NOW));
	}

	static final class MemPrefs implements SharedPreferences {
		final Map<String, Object> values = new HashMap<>();

		@Override
		public Map<String, ?> getAll() {
			return values;
		}

		@Override
		public String getString(String key, String defValue) {
			Object v = values.get(key);
			return v == null ? defValue : (String) v;
		}

		@Override
		public java.util.Set<String> getStringSet(String key,
				java.util.Set<String> defValues) {
			return defValues;
		}

		@Override
		public int getInt(String key, int defValue) {
			Object v = values.get(key);
			return v == null ? defValue : (Integer) v;
		}

		@Override
		public long getLong(String key, long defValue) {
			Object v = values.get(key);
			return v == null ? defValue : (Long) v;
		}

		@Override
		public float getFloat(String key, float defValue) {
			return defValue;
		}

		@Override
		public boolean getBoolean(String key, boolean defValue) {
			Object v = values.get(key);
			return v == null ? defValue : (Boolean) v;
		}

		@Override
		public boolean contains(String key) {
			return values.containsKey(key);
		}

		@Override
		public Editor edit() {
			return new Editor() {
				@Override
				public Editor putString(String key, String value) {
					values.put(key, value);
					return this;
				}

				@Override
				public Editor putStringSet(String key,
						java.util.Set<String> value) {
					return this;
				}

				@Override
				public Editor putInt(String key, int value) {
					values.put(key, value);
					return this;
				}

				@Override
				public Editor putLong(String key, long value) {
					values.put(key, value);
					return this;
				}

				@Override
				public Editor putFloat(String key, float value) {
					return this;
				}

				@Override
				public Editor putBoolean(String key, boolean value) {
					values.put(key, value);
					return this;
				}

				@Override
				public Editor remove(String key) {
					values.remove(key);
					return this;
				}

				@Override
				public Editor clear() {
					values.clear();
					return this;
				}

				@Override
				public boolean commit() {
					return true;
				}

				@Override
				public void apply() {
				}
			};
		}

		@Override
		public void registerOnSharedPreferenceChangeListener(
				OnSharedPreferenceChangeListener listener) {
		}

		@Override
		public void unregisterOnSharedPreferenceChangeListener(
				OnSharedPreferenceChangeListener listener) {
		}
	}
}
