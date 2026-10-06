package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelWithheldFlagTest {

	private final Random random = new Random(198);
	private CryptoComponent crypto;
	private ChannelTestNode node;
	private ChannelTestNode.TestChannel channel;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		node = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		channel = new ChannelTestNode.TestChannel(crypto, random);
	}

	@After
	public void tearDown() {
		node.deleteFiles();
	}

	@Test
	public void markingTheChannelReadKeepsAWithheldPostWithheld()
			throws Exception {
		List<ChannelPost> posts = node.posts(channel, 3);
		posts.set(1, posts.get(1).withheld());
		node.seedSubscriber(channel, true, null, null, posts);
		node.store.setUnread(channel.channelId, 2);

		node.manager.markChannelRead(channel.channelId);

		List<ChannelPost> stored = node.store.getPosts(channel.channelId);
		assertTrue(stored.get(0).isRead());
		assertTrue(stored.get(2).isRead());
		assertTrue("the withheld flag survives being written back",
				stored.get(1).isWithheld());
		assertFalse(stored.get(0).isWithheld());
		assertFalse(stored.get(2).isWithheld());
	}

	@Test
	public void aPrivateChannelShowsAWithheldPostAsWithheld()
			throws Exception {
		byte[] kContent = node.contentKey.generateContentKey();
		List<ChannelPost> posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < 3; seq++) {
			byte[] ct = node.contentKey.encryptBody(kContent,
					channel.channelId, seq, "secret " + seq);
			ChannelPost p = node.post(channel, seq, prev,
					Base64.getEncoder().withoutPadding().encodeToString(ct),
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			posts.add(p);
			prev = node.chain.hashOf(p);
		}
		posts.set(1, posts.get(1).withheld());
		node.seedSubscriber(channel, false, new byte[32], kContent, posts);

		List<ChannelPost> shown =
				node.manager.getRecentPosts(channel.channelId, 10L);

		assertEquals(3, shown.size());
		assertEquals("secret 0", shown.get(0).getBody());
		assertFalse(shown.get(0).isWithheld());
		assertTrue("the decrypted view keeps the withheld flag",
				shown.get(1).isWithheld());
		assertFalse(shown.get(2).isWithheld());
	}

	@Test
	public void aDeletedWithheldPostStaysWithheld() throws Exception {
		List<ChannelPost> posts = node.posts(channel, 2);
		posts.set(1, posts.get(1).withheld());
		node.seedSubscriber(channel, true, null, null, posts);
		node.postTombstones.add(channel.channelId, 1L);

		List<ChannelPost> shown =
				node.manager.getRecentPosts(channel.channelId, 10L);

		assertEquals(2, shown.size());
		assertTrue("the deleted view keeps the withheld flag",
				shown.get(1).isWithheld());
	}
}
