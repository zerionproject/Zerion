package org.zerionproject.app.channel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.app.api.channel.event.ChannelPostsRemovedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.event.Event;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class ChannelPostsRemovedEventTest {

	private final Random random = new Random(5512);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		owner = new ChannelTestNode(crypto, clock, network);
		nodes.add(owner);
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	private ChannelTestNode join(byte[] id) throws Exception {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, network, null,
				ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair()));
		nodes.add(n);
		n.manager.joinChannel(n.manager.parseInviteLink(
				owner.manager.exportInviteLink(id)));
		n.manager.refreshChannel(id);
		return n;
	}

	private static List<Long> removed(ChannelTestNode n, byte[] id) {
		List<Long> seqs = new ArrayList<>();
		synchronized (n.events) {
			for (Event e : n.events) {
				if (e instanceof ChannelPostsRemovedEvent) {
					ChannelPostsRemovedEvent r = (ChannelPostsRemovedEvent) e;
					if (Arrays.equals(id, r.getChannelId())) {
						seqs.addAll(r.getSeqNums());
					}
				}
			}
		}
		Collections.sort(seqs);
		return seqs;
	}

	@Test
	public void deletingAPostForEveryoneSaysWhichPostWentOnEveryDevice()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "kept", 0L);
		owner.manager.publishPost(id, "deleted", 0L);
		ChannelTestNode reader = join(id);
		assertEquals(Collections.emptyList(), removed(reader, id));

		owner.manager.deletePost(id, 1L);
		assertEquals(Collections.singletonList(1L), removed(owner, id));

		reader.manager.refreshChannel(id);
		assertEquals(Collections.singletonList(1L), removed(reader, id));
	}

	@Test
	public void anEditReplacedByALaterOneIsNamedWhenItGoes()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "original", 0L);
		owner.manager.editPost(id, 0L, "first correction");
		assertEquals(Collections.emptyList(), removed(owner, id));
		owner.manager.editPost(id, 0L, "second correction");
		assertEquals("a replaced edit went without a word",
				Collections.singletonList(1L), removed(owner, id));
	}

	@Test
	public void aPostWhoseTimerRanOutIsNamedWhenItIsPurged()
			throws Exception {
		byte[] id = owner.manager.createChannel("name", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "lasting", 0L);
		owner.manager.publishPost(id, "short lived", 60L * 60L);
		owner.manager.purgeExpiredPosts();
		assertEquals(Collections.emptyList(), removed(owner, id));

		clock.advance(3L * ChannelTestNode.HOUR);
		owner.manager.purgeExpiredPosts();
		assertEquals(Collections.singletonList(1L), removed(owner, id));
	}
}
