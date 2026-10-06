package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChannelCorruptStateTest {

	private final Random random = new Random(243);
	private CryptoComponent crypto;
	private ChannelTestNode node;
	private ChannelTestNode.TestChannel good;
	private ChannelTestNode.TestChannel corrupt;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		node = new ChannelTestNode(crypto, new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		good = new ChannelTestNode.TestChannel(crypto, random);
		corrupt = new ChannelTestNode.TestChannel(crypto, random);
		ChannelPost p = node.post(good, 0,
				new byte[ChannelConstants.PREV_HASH_BYTES], "x",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		node.seedPublisher(good, true, null, null, false,
				Collections.singletonList(new ChannelPost(good.channelId, 0L,
						p.getPrevHash(), 1L, "x",
						Collections.<ChannelPost.ChannelAttachment>emptyList(),
						1000L, p.getSignature(), true)));
		node.seedPublisher(corrupt, true, null, null, false,
				Collections.<ChannelPost>emptyList());
		Settings bad = new Settings();
		bad.put(ChannelStore.hex(corrupt.channelId), "!!not base64!!");
		node.settings.mergeSettings(bad, "zerion-channels-state");
	}

	@After
	public void tearDown() {
		node.deleteFiles();
	}

	@Test
	public void aCorruptStateIsSkippedAndReadsAsAbsent() throws Exception {
		assertEquals(1, node.store.listChannels().size());
		assertNull(node.store.getChannel(corrupt.channelId));
	}

	@Test
	public void aCorruptStateDoesNotStopThePurgeOrTheRefresh()
			throws Exception {
		node.manager.purgeExpiredPosts();
		assertTrue("the other channel's expired post is still held",
				node.store.getPosts(good.channelId).isEmpty());
		node.refreshAndReschedule();
		assertTrue(node.scheduledDelays.size() >= 1);
	}
}
