package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

public class ChannelSequenceBoundTest {

	private static final long BOUND = ChannelTestNode.constant(
			"MAX_SEQUENCE_NUMBER", -1L);

	private final Random random = new Random(414);
	private CryptoComponent crypto;
	private ChannelTestNode node;
	private ChannelTestNode.TestChannel channel;
	private ChannelPostValidator validator;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		node = new ChannelTestNode(crypto, new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		channel = new ChannelTestNode.TestChannel(crypto, random);
		validator = new ChannelPostValidator(node.codec, node.signatures,
				node.chain);
	}

	@After
	public void tearDown() {
		node.deleteFiles();
	}

	@Test
	public void aSequenceNumberBeyondTheBoundIsRefusedByTheChainRule()
			throws Exception {
		List<ChannelPost> posts = node.posts(channel, 2);
		ChannelChainTip tip = new ChannelChainTip(1L,
				node.chain.hashOf(posts.get(1)));
		ChannelPost far = node.post(channel, Long.MAX_VALUE - 1L,
				new byte[ChannelConstants.PREV_HASH_BYTES], "far",
				Collections.<ChannelPost.ChannelAttachment>emptyList());

		assertNotEquals(ChannelPostValidator.Result.OK,
				validator.validateChain(far, tip, true));
		assertNotEquals(ChannelPostValidator.Result.OK,
				validator.validateChain(far, null, true));
		if (BOUND < 0L) fail("sequence numbers have no bound");
		ChannelPost justOver = node.post(channel, BOUND + 1L,
				new byte[ChannelConstants.PREV_HASH_BYTES], "over",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		assertNotEquals(ChannelPostValidator.Result.OK,
				validator.validateChain(justOver, tip, true));
	}

	@Test
	public void aSequenceNumberBeyondTheBoundIsRefusedAtTheWire()
			throws Exception {
		ChannelPost far = node.post(channel, Long.MAX_VALUE,
				new byte[ChannelConstants.PREV_HASH_BYTES], "far",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		BdfDictionary manifest = node.pullCodec.encodeManifest(
				channel.channelId, channel.salt, channel.ed(), channel.ml(),
				"name", "description", null, ChannelTestNode.HOUR, true,
				null, ChannelTestNode.PUBLISHER_ONION, 1L, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), ChannelState.NO_PINNED_POST,
				false, true, new byte[64]);
		byte[] response = node.pullCodec.encodePullResponse(manifest,
				Collections.singletonList(far), null,
				Collections.<String>emptyList(),
				Collections.<org.zerionproject.app.api.channel
						.ChannelReaction>emptyList(),
				Collections.<org.zerionproject.app.api.channel
						.ChannelComment>emptyList());
		try {
			node.pullCodec.decodePullResponse(response, channel.channelId);
			fail("a post at the largest number was decoded");
		} catch (IOException expected) {
		}
	}

	@Test(timeout = 20_000)
	public void theHeldRangesAreWalkedWithoutOverflow() {
		ChannelPostStore.Meta m = new ChannelPostStore.Meta();
		m.add(Long.MAX_VALUE);
		m.readThrough = Long.MAX_VALUE - 1L;
		assertEquals(1, m.unread());
		m.add(0L);
		m.readThrough = -1L;
		assertEquals(2, m.heldSeqs().size());
		assertEquals(2, m.unread());
	}
}
