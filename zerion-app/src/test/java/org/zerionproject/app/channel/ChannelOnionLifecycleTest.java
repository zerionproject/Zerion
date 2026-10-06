package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelOnionLifecycleTest {

	private static final long DAY = 24L * ChannelTestNode.HOUR;

	private final Random random = new Random(305);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		publisher = new ChannelTestNode(crypto, clock, network);
		subscriber = new ChannelTestNode(crypto, clock, network);
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	@Test
	public void theOnionRotatesAndTheOldOneIsRemovedAfterTheWindow()
			throws Exception {
		byte[] id = create(true);
		join(id);
		String first = publisher.store.getChannel(id).getCurrentOnion();

		maintain();
		clock.advance(40 * DAY);
		maintain();

		String second = publisher.store.getChannel(id).getCurrentOnion();
		assertNotEquals("the onion never rotates", first, second);
		assertTrue("the old onion serves the move for a while",
				network.published().contains(first));
		subscriber.manager.refreshChannel(id);
		assertEquals("the subscriber follows", second,
				subscriber.store.getChannel(id).getCurrentOnion());

		clock.advance(31 * DAY);
		maintain();
		assertFalse("the old onion is still published",
				network.published().contains(first));
		publisher.manager.publishPost(id, "after the move", 0L);
		subscriber.manager.refreshChannel(id);
		assertEquals(1, subscriber.store.getPosts(id).size());
	}

	@Test
	public void rotatingAPrivateInviteLinkRemovesTheOldOnionAtOnce()
			throws Exception {
		byte[] id = create(false);
		String first = publisher.store.getChannel(id).getCurrentOnion();

		publisher.manager.rotateJoinCapability(id);

		String second = publisher.store.getChannel(id).getCurrentOnion();
		assertNotEquals("the onion did not change", first, second);
		assertFalse("the old onion is still reachable",
				network.published().contains(first));
		assertTrue(network.published().contains(second));
	}

	@Test
	public void rotatingAnInviteLinkWhileTorIsDownStillRemovesTheOldOnion()
			throws Exception {
		byte[] id = create(false);
		String first = publisher.store.getChannel(id).getCurrentOnion();

		network.down = true;
		publisher.manager.rotateJoinCapability(id);

		assertFalse("the old onion is still reachable",
				network.published().contains(first));
		assertNull("the old onion key is kept",
				publisher.store.getChannel(id).getOnionPrivateKey());

		network.down = false;
		publisher.call("ensurePublisherServersBound", new Class<?>[0]);

		String second = publisher.store.getChannel(id).getCurrentOnion();
		assertNotEquals("the old onion came back", first, second);
		assertTrue(network.published().contains(second));
		assertFalse(network.published().contains(first));
	}

	@Test
	public void aDeletedChannelsOnionServesTheTombstoneThenGoes()
			throws Exception {
		byte[] id = create(true);
		join(id);
		String onion = publisher.store.getChannel(id).getCurrentOnion();

		publisher.manager.deleteChannel(id);

		assertTrue("the tombstone is served for a while",
				network.published().contains(onion));
		subscriber.manager.refreshChannel(id);
		assertNull("the subscriber removed the channel",
				subscriber.store.getChannel(id));

		clock.advance(15 * DAY);
		maintain();
		assertFalse("the deleted channel's onion is still published",
				network.published().contains(onion));
		assertNothingLeft(publisher, id);
		assertNothingLeft(subscriber, id);
	}

	@Test
	public void leavingAChannelLeavesNothingBehind() throws Exception {
		byte[] id = create(true);
		join(id);
		publisher.manager.publishPost(id, "hello", 0L);
		subscriber.manager.refreshChannel(id);
		subscriber.manager.markChannelRead(id);

		subscriber.manager.leaveChannel(id);

		assertNothingLeft(subscriber, id);
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

	private void maintain() throws Exception {
		try {
			publisher.call("maintainOnions", new Class<?>[0]);
		} catch (NoSuchMethodException ignored) {
		}
	}

	private static void assertNothingLeft(ChannelTestNode node, byte[] id) {
		String hex = ChannelStore.hex(id);
		List<String> left = new ArrayList<>();
		for (String[] row : node.settings.rows()) {
			if (row[0].contains(hex) || row[1].contains(hex)) {
				left.add(row[0] + " / " + row[1]);
			}
		}
		assertEquals("rows naming the channel are left: " + left, 0,
				left.size());
		File blobs = new File(node.root, "channel-blobs");
		File[] dirs = blobs.listFiles();
		assertTrue("attachment directories are left",
				dirs == null || dirs.length == 0);
	}
}
