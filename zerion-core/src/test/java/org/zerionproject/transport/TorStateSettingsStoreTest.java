package org.zerionproject.transport;

import org.junit.Test;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.tor.TorStateStore.StoredState;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TorStateSettingsStoreTest {

	private final NamespacedSettings settings = new NamespacedSettings();
	private final TorStateSettingsStore store =
			new TorStateSettingsStore(settings);

	@Test
	public void nothingStoredLoadsAsNull() {
		assertNull(store.load());
	}

	@Test
	public void theStateRoundTripsThroughTheSettingsAsBase64() {
		byte[] state = {0, 1, 2, (byte) 0xFF, 10, 13, 'G', 'u', 'a', 'r', 'd'};
		assertTrue(store.save(new StoredState(state, 123456789L)));
		Settings s = settings.namespace(TorStateSettingsStore.NAMESPACE);
		assertEquals("AAEC/woNR3VhcmQ=", s.get(TorStateSettingsStore.KEY_STATE));
		assertEquals(123456789L,
				s.getLong(TorStateSettingsStore.KEY_MODIFIED, 0L));
		StoredState loaded = store.load();
		assertNotNull(loaded);
		assertArrayEquals(state, loaded.bytes);
		assertEquals(123456789L, loaded.modifiedMs);
	}

	@Test
	public void clearRemovesTheStoredState() {
		assertTrue(store.save(new StoredState(new byte[] {1}, 1L)));
		store.clear();
		assertNull(store.load());
	}

	@Test
	public void aClosedDatabaseMeansNoStateAndAFailedSave() {
		settings.failing = true;
		assertNull(store.load());
		assertFalse(store.save(new StoredState(new byte[] {1}, 1L)));
	}

	@Test
	public void aCorruptValueLoadsAsNull() {
		settings.namespace(TorStateSettingsStore.NAMESPACE)
				.put(TorStateSettingsStore.KEY_STATE, "not base64 at all!");
		assertNull(store.load());
	}
}
