package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelOnionRecoveryTest {

	private static final long DAY = 24L * ChannelTestNode.HOUR;
	private static final long MINUTE = 60_000L;

	private final Random random = new Random(411);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		fresh();
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	@Test
	public void aRotationWhileTheOnionIsNotBoundKeepsTheOldOnionServing()
			throws Exception {
		byte[] id = create(true);
		join(id);
		String first = onion(publisher, id);
		maintain(publisher);
		network.down = true;
		publisher = restart(publisher);
		network.down = false;
		assertFalse(network.published().contains(first));

		clock.advance(40 * DAY);
		maintain(publisher);

		String second = onion(publisher, id);
		assertNotEquals(first, second);
		assertTrue("the only onion subscribers know is not served",
				network.published().contains(first));
		subscriber.manager.refreshChannel(id);
		assertEquals("the subscriber could not follow", second,
				onion(subscriber, id));
	}

	@Test
	public void deletingAChannelWhileItsOnionIsNotBoundStillServesTheTombstone()
			throws Exception {
		byte[] id = create(true);
		join(id);
		String onion = onion(publisher, id);
		maintain(publisher);
		network.down = true;
		publisher = restart(publisher);
		network.down = false;

		publisher.manager.deleteChannel(id);

		assertTrue("the tombstone is not served", network.published()
				.contains(onion));
		subscriber.manager.refreshChannel(id);
		assertNull("the subscriber was never told", subscriber.store
				.getChannel(id));
	}

	@Test
	public void aCrashAtAnyPointOfARotationNeverLosesTheOldOnion()
			throws Exception {
		for (int crashAt = 1; crashAt < 20; crashAt++) {
			fresh();
			byte[] id = create(true);
			join(id);
			String first = onion(publisher, id);
			maintain(publisher);
			clock.advance(40 * DAY);
			publisher.settings.crashAfterWrite(crashAt);
			boolean crashed = false;
			try {
				maintain(publisher);
			} catch (ChannelTestNode.SimulatedCrash e) {
				crashed = true;
			}
			publisher.settings.crashAfterWrite(-1);
			publisher = restart(publisher);

			assertTrue("after a crash at write " + crashAt
					+ " the onion subscribers know is dark",
					network.published().contains(first));
			subscriber.manager.refreshChannel(id);
			publisher.manager.publishPost(id, "after the crash", 0L);
			subscriber.manager.refreshChannel(id);
			assertEquals("write " + crashAt, 1,
					subscriber.store.getPosts(id).size());
			if (!crashed) return;
		}
		fail("the rotation never completed");
	}

	@Test
	public void aCrashAtAnyPointOfADeletionKeepsTheChannelWholeOrServesItsTombstone()
			throws Exception {
		for (int crashAt = 1; crashAt < 60; crashAt++) {
			fresh();
			byte[] id = create(true);
			join(id);
			publisher.manager.publishPost(id, "hello", 0L);
			subscriber.manager.refreshChannel(id);
			String onion = onion(publisher, id);
			publisher.settings.crashAfterWrite(crashAt);
			boolean crashed = false;
			try {
				publisher.manager.deleteChannel(id);
			} catch (ChannelTestNode.SimulatedCrash e) {
				crashed = true;
			}
			publisher.settings.crashAfterWrite(-1);
			publisher = restart(publisher);

			String at = "write " + crashAt + ": ";
			assertTrue(at + "the channel's onion is dark",
					network.published().contains(onion));
			subscriber.manager.refreshChannel(id);
			if (publisher.store.getChannel(id) != null) {
				assertNotNull(at + "the owner's device still has the "
						+ "channel but the subscriber was told it is gone",
						subscriber.store.getChannel(id));
				assertEquals(at, 1, publisher.store.getPosts(id).size());
			} else {
				assertNull(at + "the subscriber was not told the channel "
						+ "is gone", subscriber.store.getChannel(id));
				assertOnlyTombstoneLeft(publisher, id, at);
				clock.advance(15 * DAY);
				maintain(publisher);
				assertNothingLeft(publisher, id, at);
			}
			if (!crashed) return;
		}
		fail("the deletion never completed");
	}

	@Test
	public void aRemovalCutShortIsFinishedAtTheNextStart() throws Exception {
		byte[] id = create(true);
		join(id);
		publisher.manager.publishPost(id, "hello", 0L);
		subscriber.manager.refreshChannel(id);
		subscriber.settings.crashAfterWrite(3);
		try {
			subscriber.manager.leaveChannel(id);
			fail("the removal was not cut short");
		} catch (ChannelTestNode.SimulatedCrash expected) {
		}
		subscriber.settings.crashAfterWrite(-1);
		assertFalse(rowsNaming(subscriber, id).isEmpty());

		subscriber = restart(subscriber);

		assertNothingLeft(subscriber, id, "");
	}

	@Test
	public void rotatingAnInviteLinkRemovesTheRetiringOnionsToo()
			throws Exception {
		byte[] id = create(false);
		String first = onion(publisher, id);
		maintain(publisher);
		clock.advance(40 * DAY);
		maintain(publisher);
		String second = onion(publisher, id);
		assertNotEquals(first, second);
		assertTrue(network.published().contains(first));

		publisher.manager.rotateJoinCapability(id);

		assertFalse("the onion retired earlier is still published",
				network.published().contains(first));
		assertFalse(network.published().contains(second));
		assertTrue(publisher.store.onions().get(id).retiring.isEmpty());
	}

	@Test
	public void aPublisherRestoredFromBeforeARotationIsFoundAgain()
			throws Exception {
		byte[] id = create(true);
		join(id);
		String first = onion(publisher, id);
		publisher.manager.publishPost(id, "before the backup", 0L);
		subscriber.manager.refreshChannel(id);
		maintain(publisher);
		publisher.callIfPresent("ensureInstanceChecked", new Class<?>[0]);
		ChannelTestNode.MemorySettings backup = publisher.settings.snapshot();
		clock.advance(40 * DAY);
		maintain(publisher);
		String second = onion(publisher, id);
		assertNotEquals(first, second);
		subscriber.manager.refreshChannel(id);
		assertEquals(second, onion(subscriber, id));

		network.unbindAll();
		ChannelTestNode restored = node(backup);
		restored.call("rebindOwnedChannelsOnStartup", new Class<?>[0]);
		assertTrue(network.published().contains(first));
		assertFalse(network.published().contains(second));
		try {
			subscriber.manager.refreshChannel(id);
			fail("the onion the subscriber knows is dark");
		} catch (DbException expected) {
		}
		clock.advance(16 * MINUTE);
		boolean reached = false;
		for (int i = 0; i < 4 && !reached; i++) {
			try {
				subscriber.manager.refreshChannel(id);
				reached = true;
			} catch (DbException stillDark) {
			}
		}

		assertTrue("the subscriber never found the restored publisher",
				reached);
		assertEquals(onion(restored, id), onion(subscriber, id));
		assertTrue(network.published().contains(onion(subscriber, id)));
		restored.manager.publishPost(id, "after the restore", 0L);
		subscriber.manager.refreshChannel(id);
		List<String> bodies = new ArrayList<>();
		for (ChannelPost p : subscriber.store.getPosts(id)) {
			bodies.add(p.getBody());
		}
		assertTrue(bodies.toString(), bodies.contains("after the restore"));
	}

	private void fresh() {
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		publisher = node(new ChannelTestNode.MemorySettings());
		subscriber = node(new ChannelTestNode.MemorySettings());
	}

	private ChannelTestNode node(ChannelTestNode.MemorySettings settings) {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, network,
				settings);
		nodes.add(n);
		return n;
	}

	private ChannelTestNode restart(ChannelTestNode dead) throws Exception {
		network.unbindAll();
		ChannelTestNode fresh = new ChannelTestNode(crypto, clock, network,
				dead);
		nodes.add(fresh);
		fresh.call("rebindOwnedChannelsOnStartup", new Class<?>[0]);
		return fresh;
	}

	private byte[] create(boolean publicChannel) throws Exception {
		ChannelState s = publisher.manager.createChannel("name", "about",
				publicChannel);
		return s.getChannelId();
	}

	private void join(byte[] id) throws Exception {
		String link = publisher.manager.exportInviteLink(id);
		subscriber.manager.joinChannel(
				subscriber.manager.parseInviteLink(link));
		subscriber.manager.refreshChannel(id);
	}

	private static String onion(ChannelTestNode node, byte[] id)
			throws Exception {
		ChannelState s = node.store.getChannel(id);
		assertNotNull(s);
		return s.getCurrentOnion();
	}

	private static void maintain(ChannelTestNode node) throws Exception {
		node.call("maintainOnions", new Class<?>[0]);
	}

	private static List<String> rowsNaming(ChannelTestNode node, byte[] id) {
		String hex = ChannelStore.hex(id);
		List<String> left = new ArrayList<>();
		for (String[] row : node.settings.rows()) {
			if (row[0].contains(hex) || row[1].contains(hex)) {
				left.add(row[0] + " / " + row[1]);
			}
		}
		return left;
	}

	private static void assertNothingLeft(ChannelTestNode node, byte[] id,
			String at) {
		List<String> left = rowsNaming(node, id);
		assertEquals(at + "rows naming the channel are left: " + left, 0,
				left.size());
		File blobs = new File(node.root, "channel-blobs");
		File[] dirs = blobs.listFiles();
		assertTrue(at + "attachment directories are left",
				dirs == null || dirs.length == 0);
	}

	private static void assertOnlyTombstoneLeft(ChannelTestNode node,
			byte[] id, String at) {
		List<String> left = new ArrayList<>();
		for (String row : rowsNaming(node, id)) {
			if (row.startsWith("zerion-channels-onions /")
					|| row.startsWith("zerion-channels-tombstones /")) {
				continue;
			}
			left.add(row);
		}
		assertEquals(at + "rows besides the tombstone are left: " + left, 0,
				left.size());
	}
}
