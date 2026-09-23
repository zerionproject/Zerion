package com.professor.zerion.android.security;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Map;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A2-AND-07: a stored value that no longer authenticates must not quietly
 * become its default (which turns every security toggle off); the storage is
 * reported as failed so the app fails closed.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ZerionEncryptedPrefsTamperTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() {
		ZerionEncryptedPrefs.resetForTests();
		RuntimeEnvironment.getApplication()
				.getSharedPreferences("tamper_v2", Context.MODE_PRIVATE)
				.edit().clear().commit();
	}

	@Test
	public void anUnauthenticatedValueFailsClosed() {
		Context app = RuntimeEnvironment.getApplication();
		ZerionEncryptedPrefs.resetForTests();
		ZerionEncryptedPrefs prefs = ZerionEncryptedPrefs.create(app, "tamper");
		assertTrue(prefs.edit().putBoolean("bf_wipe", true).commit());
		assertTrue(prefs.getBoolean("bf_wipe", false));
		assertFalse(ZerionEncryptedPrefs.isStorageFailed());

		SharedPreferences backing =
				app.getSharedPreferences("tamper_v2", Context.MODE_PRIVATE);
		Map<String, ?> all = backing.getAll();
		assertEquals(1, all.size());
		Map.Entry<String, ?> only = all.entrySet().iterator().next();
		String b64 = (String) only.getValue();
		int mid = b64.length() / 2;
		char c = b64.charAt(mid);
		String flipped = b64.substring(0, mid) + (c == 'A' ? 'B' : 'A')
				+ b64.substring(mid + 1);
		assertTrue(backing.edit().putString(only.getKey(), flipped).commit());

		assertFalse("the value is not readable", prefs.getBoolean("bf_wipe",
				false));
		assertTrue("and the storage reports failure instead of defaulting",
				ZerionEncryptedPrefs.isStorageFailed());
	}
}
