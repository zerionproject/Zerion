package org.zerionproject.crypto;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;

import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the settings-backed stream counter store persists high-water marks
 * durably (survives a simulated restart) and keeps different contacts
 * independent, so the stream counter never reuses an id.
 */
public class SettingsStreamCounterStoreTest {

	private static final int SEND = 0;
	private static final int RECV = 1;
	private static final int CONTACT_A = 473729761;
	private static final int CONTACT_B = 12;

	/** In-memory SettingsManager with the same merge semantics as the real one. */
	private static class FakeSettingsManager implements SettingsManager {
		private final Map<String, Settings> byNamespace = new HashMap<>();

		@Override
		public Settings getSettings(String namespace) {
			Settings copy = new Settings();
			Settings stored = byNamespace.get(namespace);
			if (stored != null) copy.putAll(stored);
			return copy;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public void mergeSettings(Settings s, String namespace) {
			Settings existing = byNamespace.get(namespace);
			if (existing == null) {
				existing = new Settings();
				byNamespace.put(namespace, existing);
			}
			existing.putAll(s);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
			mergeSettings(s, namespace);
		}
	}

	@Test
	public void sendIdsSurviveRestart() {
		FakeSettingsManager settings = new FakeSettingsManager();
		StreamCounterStore store = new SettingsStreamCounterStore(settings);

		ZwfStreamCounter before = new ZwfStreamCounter(store);
		for (int i = 1; i <= 5; i++) {
			assertEquals(i, before.allocateSendStreamId(CONTACT_A));
		}
		// restart: fresh counter (cache lost), same durable store
		ZwfStreamCounter after = new ZwfStreamCounter(store);
		assertEquals(6, after.allocateSendStreamId(CONTACT_A));
		assertEquals(7, after.allocateSendStreamId(CONTACT_A));
	}

	@Test
	public void recvReplayRejectionSurvivesRestart() {
		FakeSettingsManager settings = new FakeSettingsManager();
		StreamCounterStore store = new SettingsStreamCounterStore(settings);

		int w = REPLAY_WINDOW_SIZE;
		ZwfStreamCounter before = new ZwfStreamCounter(store);
		for (long id = 1; id <= 2L * w; id++) {
			assertTrue(before.acceptRecvStreamId(CONTACT_A, id));
		}

		// The persisted receive high-water still bars anything older than the
		// window after a restart (within-window ids are re-acceptable by design).
		ZwfStreamCounter after = new ZwfStreamCounter(store);
		assertFalse("stale id below the window rejected after restart",
				after.acceptRecvStreamId(CONTACT_A, w / 2));
		assertTrue(after.acceptRecvStreamId(CONTACT_A, 2L * w + 1));
	}

	@Test
	public void contactsAndDirectionsAreIndependent() {
		FakeSettingsManager settings = new FakeSettingsManager();
		StreamCounterStore store = new SettingsStreamCounterStore(settings);
		ZwfStreamCounter counter = new ZwfStreamCounter(store);

		assertEquals(1, counter.allocateSendStreamId(CONTACT_A));
		assertEquals(1, counter.allocateSendStreamId(CONTACT_B));
		assertEquals(2, counter.allocateSendStreamId(CONTACT_A));
		assertTrue(counter.acceptRecvStreamId(CONTACT_A, 1));
		// send and recv high-water for the same contact are separate
		assertEquals(3, counter.allocateSendStreamId(CONTACT_A));

		// state survives a restart: A-send high-water is 3, so next is 4
		ZwfStreamCounter after = new ZwfStreamCounter(store);
		assertEquals(4, after.allocateSendStreamId(CONTACT_A));
		assertEquals(2, after.allocateSendStreamId(CONTACT_B)); // B-send was 1
		// A-recv is a separate namespace from A-send: a fresh recv id is accepted
		assertTrue(after.acceptRecvStreamId(CONTACT_A, 2));
	}

	@Test
	public void directHighWaterReadWrite() {
		FakeSettingsManager settings = new FakeSettingsManager();
		StreamCounterStore store = new SettingsStreamCounterStore(settings);
		assertEquals(0, store.loadHighWater(CONTACT_A, SEND));
		store.storeHighWater(CONTACT_A, SEND, 42);
		store.storeHighWater(CONTACT_A, RECV, 7);
		store.storeHighWater(CONTACT_B, SEND, 100);
		assertEquals(42, store.loadHighWater(CONTACT_A, SEND));
		assertEquals(7, store.loadHighWater(CONTACT_A, RECV));
		assertEquals(100, store.loadHighWater(CONTACT_B, SEND));
		assertEquals(0, store.loadHighWater(CONTACT_B, RECV));
	}
}
