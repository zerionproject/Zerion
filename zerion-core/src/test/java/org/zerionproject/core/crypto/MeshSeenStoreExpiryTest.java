package org.zerionproject.core.crypto;

import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.async.MeshSeenStore;
import org.zerionproject.core.util.StringUtils;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MeshSeenStoreExpiryTest {

	private final InMemorySettingsManager settings =
			new InMemorySettingsManager();
	private final MutableClock clock =
			new MutableClock(1_700_000_000_000L);

	@Test
	public void anIdIsKnownForTheRetentionTimeAndThenForgotten()
			throws Exception {
		MeshSeenStore store = new MeshSeenStore(settings, clock);
		assertFalse(store.checkAndMark(id(1)));
		clock.now += MeshSeenStore.RETENTION_MS - 1;
		assertTrue(store.checkAndMark(id(1)));
		clock.now += 1;
		assertFalse("forgotten once the retention time has passed",
				store.checkAndMark(id(1)));
	}

	@Test
	public void expiredIdsMakeRoomBeforeAnyLiveIdIsDropped()
			throws Exception {
		MeshSeenStore store = new MeshSeenStore(settings, clock);
		for (int i = 0; i < MeshSeenStore.MAX_IDS; i++) {
			store.checkAndMark(id(i));
		}
		clock.now += MeshSeenStore.RETENTION_MS;
		byte[] live = id(-1);
		assertFalse(store.checkAndMark(live));
		for (int i = 0; i < MeshSeenStore.MAX_IDS - 1; i++) {
			store.checkAndMark(id(100_000 + i));
		}
		assertTrue(store.checkAndMark(live));
	}

	@Test
	public void theRecordIsBoundedWithinTheRetentionTime() throws Exception {
		MeshSeenStore store = new MeshSeenStore(settings, clock);
		byte[] oldest = id(-1);
		store.checkAndMark(oldest);
		for (int i = 0; i < MeshSeenStore.MAX_IDS; i++) {
			store.checkAndMark(id(i));
		}
		String stored = settings.getSettings(
				"org.zerionproject.async/meshSeen").get("ids");
		assertTrue(stored.split(",").length <= MeshSeenStore.MAX_IDS);
		assertFalse(store.checkAndMark(oldest));
	}

	@Test
	public void aRecordOfAnEarlierReleaseIsStillRecognised() throws Exception {
		byte[] legacy = id(5);
		Settings s = new Settings();
		s.put("ids", StringUtils.toHexString(legacy));
		settings.mergeSettings(s, "org.zerionproject.async/meshSeen");
		MeshSeenStore store = new MeshSeenStore(settings, clock);
		assertTrue(store.checkAndMark(legacy));
		clock.now += MeshSeenStore.RETENTION_MS - 1;
		assertTrue(store.checkAndMark(legacy));
		store.unmark(legacy);
		assertFalse(store.checkAndMark(legacy));
	}

	private static byte[] id(int n) {
		return ByteBuffer.allocate(32).putInt(9).putInt(n).array();
	}

	private static final class MutableClock implements Clock {
		long now;

		MutableClock(long now) {
			this.now = now;
		}

		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	}
}
