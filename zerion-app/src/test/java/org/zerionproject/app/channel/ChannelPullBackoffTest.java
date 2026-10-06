package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelReaction;
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

public class ChannelPullBackoffTest {

	private static final long IDLE_MS = 121_000L;

	private final Random random = new Random(191);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;
	private ChannelTestNode.TestChannel channel;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		subscriber = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
		publisher.seedPublisher(channel, true, null, null, false,
				publisher.posts(channel, 2));
		publisher.reactionStore.setReactions(channel.channelId,
				Collections.singletonList(publisher.reaction(channel,
						crypto.generateHybridSignatureKeyPair(), 0L, "+")));
		subscriber.seedSubscriber(channel, true, null, null,
				Collections.emptyList());
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	private void firstPull() throws Exception {
		subscriber.refreshAndReschedule();
		clock.advance(lastDelay());
		subscriber.refreshAndReschedule();
		assertEquals(2, subscriber.store.getPosts(channel.channelId).size());
	}

	private long lastDelay() {
		return subscriber.scheduledDelays.get(
				subscriber.scheduledDelays.size() - 1);
	}

	@Test
	public void anUnchangedChannelWithAReactionBacksOff() throws Exception {
		firstPull();
		assertEquals(1, subscriber.reactionStore
				.getReactions(channel.channelId).size());
		assertEquals(5_000L, subscriber.manager.pollIntervalMs(
				channel.channelId));
		clock.advance(IDLE_MS);
		subscriber.refreshAndReschedule();
		assertEquals(10_000L, subscriber.manager.pollIntervalMs(
				channel.channelId));
		clock.advance(IDLE_MS);
		subscriber.refreshAndReschedule();
		assertEquals("the interval backs off although a reaction is held",
				15_000L, subscriber.manager.pollIntervalMs(
						channel.channelId));
		long delay = lastDelay();
		assertTrue("next pull in " + delay + " ms",
				delay >= 7_500L && delay <= 22_500L);
	}

	@Test
	public void aNewReactionBringsBackTheFastInterval() throws Exception {
		firstPull();
		clock.advance(IDLE_MS);
		subscriber.refreshAndReschedule();
		assertEquals(10_000L, subscriber.manager.pollIntervalMs(
				channel.channelId));
		List<ChannelReaction> more = new ArrayList<>(
				publisher.reactionStore.getReactions(channel.channelId));
		more.add(publisher.reaction(channel,
				crypto.generateHybridSignatureKeyPair(), 1L, "*"));
		publisher.reactionStore.setReactions(channel.channelId, more);
		clock.advance(IDLE_MS);
		subscriber.refreshAndReschedule();
		assertEquals(5_000L, subscriber.manager.pollIntervalMs(
				channel.channelId));
		assertEquals(2, subscriber.reactionStore
				.getReactions(channel.channelId).size());
	}
}
