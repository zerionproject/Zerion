package com.professor.zerion.android.security;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.tor.TorStateStore;
import org.zerionproject.tor.TorStateStore.StoredState;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class DeviceTorStateStoreTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String BACKING = DeviceTorStateStore.FILE + "_v2";

	static final class ProfileStore implements TorStateStore {

		@Nullable
		StoredState stored = null;
		int clears = 0;

		@Override
		@Nullable
		public StoredState load() {
			return stored;
		}

		@Override
		public boolean save(StoredState state) {
			stored = state;
			return true;
		}

		@Override
		public void clear() {
			clears++;
			stored = null;
		}
	}

	@After
	public void tearDown() {
		ZerionEncryptedPrefs.resetForTests();
		backing().edit().clear().commit();
	}

	private Context app() {
		return RuntimeEnvironment.getApplication();
	}

	private SharedPreferences backing() {
		return app().getSharedPreferences(BACKING, Context.MODE_PRIVATE);
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	@Test
	public void theStateRoundTripsEncryptedInOneDeviceFile() {
		DeviceTorStateStore store = new DeviceTorStateStore(app(), null);
		assertNull(store.load());
		byte[] state = bytes("Guard in EntryGuard relay1 ABCDEF sampled_on=2026");
		assertTrue(store.save(new StoredState(state, 123456789L)));
		StoredState loaded = new DeviceTorStateStore(app(), null).load();
		assertNotNull(loaded);
		assertArrayEquals(state, loaded.bytes);
		assertEquals(123456789L, loaded.modifiedMs);
		Map<String, ?> raw = backing().getAll();
		assertEquals(2, raw.size());
		for (Object v : raw.values()) {
			String s = String.valueOf(v);
			assertFalse(s.contains("EntryGuard"));
			assertFalse(s.contains("R3VhcmQ"));
		}
		File file = new File(app().getApplicationInfo().dataDir,
				"shared_prefs/" + BACKING + ".xml");
		assertTrue("one file in the app's device-wide preferences",
				file.isFile());
		store.clear();
		assertNull(store.load());
	}

	@Test
	public void theSizeCapIsKept() {
		DeviceTorStateStore store = new DeviceTorStateStore(app(), null);
		assertFalse(store.save(new StoredState(new byte[0], 1L)));
		assertFalse(store.save(new StoredState(
				new byte[DeviceTorStateStore.MAX_STATE_BYTES + 1], 1L)));
		assertTrue(store.save(new StoredState(
				new byte[DeviceTorStateStore.MAX_STATE_BYTES], 1L)));
	}

	@Test
	public void aProfileCopyFromThisCycleIsMovedOnceAndThenCleared() {
		ProfileStore first = new ProfileStore();
		first.stored = new StoredState(bytes("first"), 10L);
		StoredState loaded = new DeviceTorStateStore(app(), first).load();
		assertNotNull(loaded);
		assertArrayEquals(bytes("first"), loaded.bytes);
		assertNull("the profile copy is cleared", first.stored);
		ProfileStore second = new ProfileStore();
		second.stored = new StoredState(bytes("second"), 20L);
		loaded = new DeviceTorStateStore(app(), second).load();
		assertNotNull(loaded);
		assertArrayEquals("the device value wins over a second profile copy",
				bytes("first"), loaded.bytes);
		assertNull(second.stored);
		ProfileStore clean = new ProfileStore();
		new DeviceTorStateStore(app(), clean).load();
		assertEquals("nothing to clear, no write to the profile", 0,
				clean.clears);
	}

	@Test
	public void aFailingKeystoreStoresNothingAndKeepsTheProfileCopy() {
		ProfileStore profile = new ProfileStore();
		profile.stored = new StoredState(bytes("kept"), 1L);
		DeviceTorStateStore store = new DeviceTorStateStore(app(), c -> {
			throw new IllegalStateException();
		}, profile);
		assertNull(store.load());
		assertFalse(store.save(new StoredState(bytes("x"), 1L)));
		assertNotNull(profile.stored);
		assertTrue(backing().getAll().isEmpty());
	}

	@Test
	public void aFailedDeviceWriteKeepsTheProfileCopy() {
		ProfileStore profile = new ProfileStore();
		profile.stored = new StoredState(bytes("guards"), 5L);
		SharedPreferences real = ZerionEncryptedPrefs.create(app(),
				DeviceTorStateStore.FILE);
		ClassLoader loader = SharedPreferences.class.getClassLoader();
		SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
				loader, new Class<?>[] {SharedPreferences.class},
				(proxy, method, args) -> {
					if (!method.getName().equals("edit")) {
						return method.invoke(real, args);
					}
					SharedPreferences.Editor e = real.edit();
					return Proxy.newProxyInstance(loader,
							new Class<?>[] {SharedPreferences.Editor.class},
							(p2, m2, a2) -> {
								if (m2.getName().equals("commit")) return false;
								Object r = m2.invoke(e, a2);
								return r == e ? p2 : r;
							});
				});
		StoredState loaded =
				new DeviceTorStateStore(app(), c -> failing, profile).load();
		assertNotNull("the profile copy is still used this time", loaded);
		assertArrayEquals(bytes("guards"), loaded.bytes);
		assertNotNull("and kept for the next try", profile.stored);
		assertEquals(0, profile.clears);
	}
}
