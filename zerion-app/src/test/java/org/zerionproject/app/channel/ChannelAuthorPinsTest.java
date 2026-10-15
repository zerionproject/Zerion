package org.zerionproject.app.channel;

import org.zerionproject.core.api.settings.Settings;
import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelAuthorPinsTest {

	private final Random random = new Random(3203);
	private final ChannelTestNode.MemorySettings settings =
			new ChannelTestNode.MemorySettings();
	private final byte[] channelId = bytes(32);

	@Test
	public void theFirstPinOfAKeyIsNeverReplaced() throws Exception {
		ChannelAuthorPins pins = new ChannelAuthorPins(settings);
		byte[] ed = bytes(32);
		byte[] first = bytes(32);
		byte[] second = bytes(32);
		pins.put(channelId, one(ed, first), none());
		pins.put(channelId, one(ed, second), none());
		assertArrayEquals(first, pins.get(channelId, ed));
		assertArrayEquals("a fresh instance reads the same pin", first,
				new ChannelAuthorPins(settings).get(channelId, ed));
	}

	@Test
	public void pinsAreKeptPerChannel() throws Exception {
		ChannelAuthorPins pins = new ChannelAuthorPins(settings);
		byte[] ed = bytes(32);
		byte[] other = bytes(32);
		pins.put(channelId, one(ed, bytes(32)), none());
		assertNull(pins.get(other, ed));
		pins.removeAll(channelId);
		assertNull(pins.get(channelId, ed));
		assertTrue("the namespace is gone", settings.getSettings(
				ChannelAuthorPins.namespace(channelId)).isEmpty());
	}

	@Test
	public void malformedPinsAreNotWrittenAndDamagedRowsAreUnpinned()
			throws Exception {
		ChannelAuthorPins pins = new ChannelAuthorPins(settings);
		Map<String, byte[]> bad = new LinkedHashMap<>();
		bad.put("zz" + ChannelStore.hex(bytes(31)), bytes(32));
		bad.put(ChannelStore.hex(bytes(32)), bytes(31));
		bad.put(ChannelStore.hex(bytes(16)), bytes(32));
		pins.put(channelId, bad, none());
		assertTrue(settings.getSettings(
				ChannelAuthorPins.namespace(channelId)).isEmpty());
		byte[] ed = bytes(32);
		Settings damaged = new Settings();
		damaged.put(ChannelStore.hex(ed), "7:" + "x".repeat(64));
		settings.mergeSettings(damaged, ChannelAuthorPins.namespace(channelId));
		assertNull(pins.get(channelId, ed));
	}

	@Test
	public void trimmingDropsTheOldestPinsOfAuthorsNothingIsHeldFor()
			throws Exception {
		int max = 300;
		ChannelAuthorPins pins = new ChannelAuthorPins(settings, max);
		byte[] heldEd = bytes(32);
		pins.put(channelId, one(heldEd, bytes(32)), none());
		byte[] oldest = bytes(32);
		pins.put(channelId, one(oldest, bytes(32)), none());
		Set<String> held = new HashSet<>();
		held.add(ChannelStore.hex(heldEd));
		byte[] newest = null;
		for (int i = 0; i < 2 * max; i++) {
			newest = bytes(32);
			pins.put(channelId, one(newest, bytes(32)), () -> held);
		}
		assertNotNull("the held author keeps its pin",
				pins.get(channelId, heldEd));
		assertNull("the oldest unheld author lost its pin",
				pins.get(channelId, oldest));
		assertNotNull("the newest author keeps its pin",
				pins.get(channelId, newest));
		assertTrue("the table stays near its bound",
				authorRows() <= max + 256);
	}

	@Test
	public void heldAuthorsAreNeverTrimmedEvenPastTheBound()
			throws Exception {
		int max = 8;
		ChannelAuthorPins pins = new ChannelAuthorPins(settings, max);
		Set<String> held = new HashSet<>();
		Map<String, byte[]> batch = new LinkedHashMap<>();
		for (int i = 0; i < 300; i++) {
			byte[] ed = bytes(32);
			held.add(ChannelStore.hex(ed));
			batch.put(ChannelStore.hex(ed), bytes(32));
		}
		pins.put(channelId, batch, () -> held);
		assertEquals(300, authorRows());
	}

	@Test
	public void seedingRecordsTheFirstKeyAndTheMarker() throws Exception {
		ChannelAuthorPins pins = new ChannelAuthorPins(settings);
		assertFalse(pins.seeded(channelId));
		pins.seed(channelId, Collections.<String, byte[]>emptyMap(), none());
		assertTrue(pins.seeded(channelId));
		assertTrue("an empty seed writes nothing", settings.getSettings(
				ChannelAuthorPins.namespace(channelId)).isEmpty());
		assertFalse("and is redone by the next process",
				new ChannelAuthorPins(settings).seeded(channelId));
		byte[] ed = bytes(32);
		byte[] pinned = bytes(32);
		pins.put(channelId, one(ed, pinned), none());
		ChannelAuthorPins next = new ChannelAuthorPins(settings);
		next.seed(channelId, one(ed, bytes(32)), none());
		assertArrayEquals("seeding never replaces a pin", pinned,
				next.get(channelId, ed));
		assertTrue(new ChannelAuthorPins(settings).seeded(channelId));
	}

	@Test
	public void theBoundReplyMarkIsKeptUntilTheChannelIsRemoved()
			throws Exception {
		ChannelAuthorPins pins = new ChannelAuthorPins(settings);
		assertFalse(pins.boundReplies(channelId));
		pins.markBoundReplies(channelId);
		assertTrue(new ChannelAuthorPins(settings).boundReplies(channelId));
		pins.removeAll(channelId);
		assertFalse(pins.boundReplies(channelId));
		assertFalse(new ChannelAuthorPins(settings).boundReplies(channelId));
	}

	private int authorRows() throws Exception {
		int n = 0;
		for (String k : settings.getSettings(
				ChannelAuthorPins.namespace(channelId)).keySet()) {
			if (k.length() == 64) n++;
		}
		return n;
	}

	private static Map<String, byte[]> one(byte[] ed, byte[] pin) {
		Map<String, byte[]> m = new LinkedHashMap<>();
		m.put(ChannelStore.hex(ed), pin);
		return m;
	}

	private static ChannelAuthorPins.Held none() {
		return Collections::emptySet;
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}
}
