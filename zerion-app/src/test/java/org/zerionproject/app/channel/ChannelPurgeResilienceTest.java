package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelPurgeResilienceTest {

	private final Random random = new Random(7);
	private CryptoComponent crypto;
	private ChannelTestNode publisher;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		publisher = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
	}

	@Test
	public void oneUnreadableChannelDoesNotStopThePurgeOfTheOthers()
			throws Exception {
		List<ChannelTestNode.TestChannel> expiring = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			ChannelTestNode.TestChannel c =
					new ChannelTestNode.TestChannel(crypto, random);
			ChannelPost p = publisher.post(c, 0,
					new byte[ChannelConstants.PREV_HASH_BYTES], "x",
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			ChannelPost expired = new ChannelPost(c.channelId, 0L,
					p.getPrevHash(), 1L, "x",
					Collections.<ChannelPost.ChannelAttachment>emptyList(),
					1000L, p.getSignature(), true);
			publisher.seedPublisher(c, true, null, null, false,
					Collections.singletonList(expired));
			expiring.add(c);
		}
		ChannelTestNode.TestChannel unreadable =
				new ChannelTestNode.TestChannel(crypto, random);
		publisher.seedPublisher(unreadable, true, null, null, false,
				Collections.<ChannelPost>emptyList());
		ChannelPost.ChannelAttachment oversizedThumbnail =
				new ChannelPost.ChannelAttachment(new byte[32], 1L,
						"image/jpeg", new byte[32], null,
						new byte[70 * 1024]);
		publisher.store.writePosts(unreadable.channelId,
				Collections.singletonList(new ChannelPost(
						unreadable.channelId, 0L, new byte[32], 1L, "y",
						Collections.singletonList(oversizedThumbnail), 0L,
						new byte[64], true)));

		boolean reported = false;
		try {
			publisher.manager.purgeExpiredPosts();
		} catch (DbException e) {
			reported = true;
		}

		int stillHeld = 0;
		for (ChannelTestNode.TestChannel c : expiring) {
			if (!publisher.store.getPosts(c.channelId).isEmpty()) stillHeld++;
		}
		assertEquals("expired posts still held in other channels", 0,
				stillHeld);
		assertTrue("the unreadable channel is still reported", reported);
	}
}
