package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ChannelStorageWindowTest {

	private final Random random = new Random(225);
	private CryptoComponent crypto;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;
	private ChannelTestNode.TestChannel channel;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		ChannelTestNode.MutableClock clock =
				new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		subscriber = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	@Test
	public void aChannelAtThePostCeilingStillTakesInNewPosts()
			throws Exception {
		int max = ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL;
		List<ChannelPost> history = new ArrayList<>(max);
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < max; seq++) {
			ChannelPost p = new ChannelPost(channel.channelId, seq, prev,
					ChannelTestNode.HOUR, "f",
					Collections.<ChannelPost.ChannelAttachment>emptyList(),
					0L, new byte[1], true);
			history.add(p);
			prev = subscriber.chain.hashOf(p);
		}
		ChannelPost next = publisher.post(channel, max, prev, "news",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(next));
		subscriber.seedSubscriber(channel, true, null, null, history);

		subscriber.manager.refreshChannel(channel.channelId);

		List<ChannelPost> held =
				subscriber.store.getPosts(channel.channelId);
		assertEquals("the new post is held", (long) max,
				held.get(held.size() - 1).getSeqNum());
		assertTrue("the ceiling still holds", held.size() <= max);
	}

	@Test
	public void appendingAPostWritesThatPostAndNotTheChannel()
			throws Exception {
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.<ChannelPost>emptyList());
		for (int i = 0; i < 60; i++) {
			publisher.manager.publishPost(channel.channelId, "post " + i,
					0L);
		}
		long one = onePostValue();
		long before = publisher.settings.bytesWritten();
		publisher.manager.publishPost(channel.channelId, "post 60", 0L);
		long written = publisher.settings.bytesWritten() - before;
		assertTrue("one append wrote " + written + " characters, one post"
				+ " is " + one, written < 4 * one);
	}

	@Test
	public void servingTheNextPostReadsThatPostAndNotTheChannel()
			throws Exception {
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.<ChannelPost>emptyList());
		for (int i = 0; i < 60; i++) {
			publisher.manager.publishPost(channel.channelId, "post " + i,
					0L);
		}
		List<ChannelPost> all = publisher.store.getPosts(channel.channelId);
		subscriber.seedSubscriber(channel, true, null, null,
				new ArrayList<>(all.subList(0, 59)));
		long one = onePostValue();
		long before = publisher.settings.bytesRead();
		subscriber.manager.refreshChannel(channel.channelId);
		long read = publisher.settings.bytesRead() - before;
		assertEquals(60, subscriber.store.getPosts(channel.channelId)
				.size());
		assertTrue("serving one post read " + read + " characters, one"
				+ " post is " + one, read < 8 * one);
	}

	private long onePostValue() throws Exception {
		ChannelPost p = publisher.post(channel, 0,
				new byte[ChannelConstants.PREV_HASH_BYTES], "post 1",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		return p.getSignature().length * 4L / 3L + 200L;
	}
}
