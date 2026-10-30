package com.professor.zerion.android.contact.add.remote;

import android.content.SharedPreferences;

import org.junit.Test;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.Transaction;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.PER_ADD_BINDING_MS;

public class OwnLinkPrefsMigrationTest {

	private static final long NOW = 1_700_000_000_000L;
	private static final String LINK = "zerion://"
			+ "aebaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Test
	public void aFreshEntryBecomesABindingAndTheKeysAreDeleted()
			throws Exception {
		MemPrefs prefs = new MemPrefs();
		prefs.edit().putString(OwnLinkPrefsMigration.KEY_LINK, LINK)
				.putLong(OwnLinkPrefsMigration.KEY_AT, NOW).apply();
		ContactManager contacts = mock(ContactManager.class);
		Transaction txn = new Transaction(null, false);
		OwnLinkPrefsMigration.migrate(prefs, contacts, txn,
				NOW + PER_ADD_BINDING_MS);
		verify(contacts).recordPerAddLinkShared(txn, LINK, NOW);
		assertFalse("kept until the transaction commits",
				prefs.values.isEmpty());
		runCommitActions(txn);
		assertTrue(prefs.values.isEmpty());
		OwnLinkPrefsMigration.migrate(prefs, contacts, txn, NOW);
		verifyNoMoreInteractions(contacts);
	}

	@Test
	public void anOldEntryIsDeletedWithoutABinding() throws Exception {
		MemPrefs prefs = new MemPrefs();
		prefs.edit().putString(OwnLinkPrefsMigration.KEY_LINK, LINK)
				.putLong(OwnLinkPrefsMigration.KEY_AT, NOW).apply();
		ContactManager contacts = mock(ContactManager.class);
		Transaction txn = new Transaction(null, false);
		OwnLinkPrefsMigration.migrate(prefs, contacts, txn,
				NOW + PER_ADD_BINDING_MS + 1);
		runCommitActions(txn);
		verify(contacts, never()).recordPerAddLinkShared(any(), any(),
				anyLong());
		assertFalse(prefs.contains(OwnLinkPrefsMigration.KEY_LINK));
		assertFalse(prefs.contains(OwnLinkPrefsMigration.KEY_AT));
	}

	@Test
	public void aDamagedEntryIsDeleted() throws Exception {
		MemPrefs prefs = new MemPrefs();
		prefs.edit().putString(OwnLinkPrefsMigration.KEY_LINK, "zerion://x")
				.putLong(OwnLinkPrefsMigration.KEY_AT, NOW).apply();
		ContactManager contacts = mock(ContactManager.class);
		doThrow(new FormatException()).when(contacts)
				.recordPerAddLinkShared(any(), any(), anyLong());
		Transaction txn = new Transaction(null, false);
		OwnLinkPrefsMigration.migrate(prefs, contacts, txn, NOW);
		runCommitActions(txn);
		assertTrue(prefs.values.isEmpty());
	}

	private static void runCommitActions(Transaction txn) {
		for (org.zerionproject.core.api.db.CommitAction a : txn.getActions()) {
			if (a instanceof org.zerionproject.core.api.db.TaskAction) {
				((org.zerionproject.core.api.db.TaskAction) a).getTask().run();
			}
		}
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
