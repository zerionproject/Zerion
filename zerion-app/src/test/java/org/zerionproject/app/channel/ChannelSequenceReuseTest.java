package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelSequenceReuseTest {

	private static final long HOUR = ChannelTestNode.HOUR;

	private final Random random = new Random(196);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;
	private ChannelTestNode.TestChannel channel;
	private byte[] capability;
	private byte[] kContent;

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
		capability = new byte[32];
		random.nextBytes(capability);
		kContent = new byte[32];
		random.nextBytes(kContent);
		publisher.seedPublisher(channel, false, capability, kContent, false,
				Collections.<ChannelPost>emptyList());
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	@Test
	public void theNextPostAfterAnExpiredOneTakesANewSequenceNumber()
			throws Exception {
		String first = "first secret body of the channel";
		String second = "second secret body, same length!";
		publisher.manager.publishPost(channel.channelId, first, 3600L);
		ChannelPost expired = publisher.store.getPosts(channel.channelId)
				.get(0);
		clock.advance(3 * HOUR);
		publisher.manager.purgeExpiredPosts();
		assertTrue(publisher.store.getPosts(channel.channelId).isEmpty());

		publisher.manager.publishPost(channel.channelId, second, 0L);

		ChannelPost next = publisher.store.getPosts(channel.channelId)
				.get(0);
		assertEquals("the expired post's number is not issued again",
				expired.getSeqNum() + 1L, next.getSeqNum());
		byte[] a = Base64.getDecoder().decode(expired.getBody());
		byte[] b = Base64.getDecoder().decode(next.getBody());
		byte[] pa = first.getBytes(StandardCharsets.UTF_8);
		byte[] pb = second.getBytes(StandardCharsets.UTF_8);
		byte[] cipherXor = new byte[pa.length];
		byte[] plainXor = new byte[pa.length];
		for (int i = 0; i < pa.length; i++) {
			cipherXor[i] = (byte) (a[i] ^ b[i]);
			plainXor[i] = (byte) (pa[i] ^ pb[i]);
		}
		assertFalse("two bodies share a keystream: the nonce repeated",
				Arrays.equals(cipherXor, plainXor));
	}

	@Test
	public void aSubscriberHoldingTheExpiredPostAcceptsTheNextOne()
			throws Exception {
		publisher.manager.publishPost(channel.channelId, "first", 3600L);
		subscriber.seedSubscriber(channel, false, capability, kContent,
				Collections.<ChannelPost>emptyList());
		subscriber.manager.refreshChannel(channel.channelId);
		assertEquals(1, subscriber.store.getPosts(channel.channelId).size());
		clock.advance(3 * HOUR);
		publisher.manager.purgeExpiredPosts();

		publisher.manager.publishPost(channel.channelId, "second", 0L);
		subscriber.manager.refreshChannel(channel.channelId);

		List<ChannelPost> held = subscriber.store.getPosts(channel.channelId);
		assertEquals("the next post arrives", 2, held.size());
		assertEquals(1L, held.get(1).getSeqNum());
	}

	@Test
	public void aSubscriberWhoseCopyExpiredAcceptsTheNextPost()
			throws Exception {
		publisher.manager.publishPost(channel.channelId, "first", 3600L);
		subscriber.seedSubscriber(channel, false, capability, kContent,
				Collections.<ChannelPost>emptyList());
		subscriber.manager.refreshChannel(channel.channelId);
		clock.advance(3 * HOUR);
		subscriber.manager.purgeExpiredPosts();
		assertTrue(subscriber.store.getPosts(channel.channelId).isEmpty());

		publisher.manager.publishPost(channel.channelId, "second", 0L);
		subscriber.manager.refreshChannel(channel.channelId);

		List<ChannelPost> held = subscriber.store.getPosts(channel.channelId);
		assertEquals(1, held.size());
		assertEquals("second", subscriber.manager.getRecentPosts(
				channel.channelId, 10L).get(0).getBody());
	}

	@Test
	public void aSubscriberJoiningAfterTheFirstPostExpiredGetsTheRest()
			throws Exception {
		publisher.manager.publishPost(channel.channelId, "gone", 3600L);
		publisher.manager.publishPost(channel.channelId, "kept", 0L);
		clock.advance(3 * HOUR);
		publisher.manager.purgeExpiredPosts();
		subscriber.seedSubscriber(channel, false, capability, kContent,
				Collections.<ChannelPost>emptyList());

		subscriber.manager.refreshChannel(channel.channelId);

		List<ChannelPost> held = subscriber.store.getPosts(channel.channelId);
		assertEquals("the remaining post arrives", 1, held.size());
		assertEquals("kept", subscriber.manager.getRecentPosts(
				channel.channelId, 10L).get(0).getBody());
	}
}
