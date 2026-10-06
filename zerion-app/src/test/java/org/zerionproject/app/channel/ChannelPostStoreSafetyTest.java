package org.zerionproject.app.channel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.app.api.channel.AttachmentSpec;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class ChannelPostStoreSafetyTest {

	private final CryptoComponent crypto =
			DaggerChannelCryptoTestComponent.create().getCryptoComponent();
	private final Random random = new Random(7);
	private ChannelTestNode node;
	private ChannelTestNode.TestChannel channel;
	private byte[] kContent;

	@Before
	public void setUp() throws Exception {
		node = new ChannelTestNode(crypto, new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		channel = new ChannelTestNode.TestChannel(crypto, random);
		kContent = new byte[32];
		random.nextBytes(kContent);
		node.seedPublisher(channel, false, new byte[32], kContent, false,
				Collections.<ChannelPost>emptyList());
	}

	@After
	public void tearDown() {
		node.deleteFiles();
	}

	@Test
	public void anUnreadableChainRefusesToPublishInsteadOfRestartingIt()
			throws Exception {
		node.manager.publishPost(channel.channelId, "first", 0);
		List<ChannelPost> held = node.store.getPosts(channel.channelId);
		assertEquals(1, held.size());
		ChannelPost first = held.get(0);
		ChannelPost.ChannelAttachment tooBig =
				new ChannelPost.ChannelAttachment(new byte[32], 1L,
						"image/jpeg", new byte[32], null,
						new byte[70 * 1024]);
		List<ChannelPost> corrupt = new ArrayList<>(held);
		corrupt.add(new ChannelPost(channel.channelId, 1L,
				node.chain.hashOf(first), first.getTimestampHourMs(), "x",
				Collections.singletonList(tooBig), 0L, first.getSignature(),
				true));
		node.store.writePosts(channel.channelId, corrupt);
		try {
			node.store.getPosts(channel.channelId);
			fail("an unreadable chain must not read as empty");
		} catch (DbException expected) {
		}
		try {
			node.manager.publishPost(channel.channelId, "second", 0);
			fail("publishing on an unreadable chain would reuse seq 0");
		} catch (DbException expected) {
		}
	}

	@Test
	public void anOversizedThumbnailIsLeftOutAndTheChainStaysReadable()
			throws Exception {
		byte[] thumb = new byte[ChannelConstants.MAX_ATTACHMENT_THUMBNAIL_BYTES
				+ 1];
		random.nextBytes(thumb);
		node.manager.publishPostWithAttachments(channel.channelId, " ", 0,
				Collections.singletonList(new AttachmentSpec("video/mp4",
						new byte[1024], null, thumb)));
		List<ChannelPost> held = node.store.getPosts(channel.channelId);
		assertEquals(1, held.size());
		assertNull(held.get(0).getAttachments().get(0).getThumbnail());
		node.manager.publishPost(channel.channelId, "next", 0);
		assertEquals(1L, node.store.getPosts(channel.channelId).get(1)
				.getSeqNum());
	}

	@Test
	public void aPrivateBodyTooLongOnTheWireIsRefused() throws Exception {
		StringBuilder body = new StringBuilder();
		for (int i = 0; i < ChannelConstants.MAX_POST_BODY_CHARS; i++) {
			body.append('b');
		}
		try {
			node.manager.publishPost(channel.channelId, body.toString(), 0);
			fail();
		} catch (org.zerionproject.app.api.channel
				.ChannelPostTooLongException expected) {
		}
		assertEquals(0, node.store.getPosts(channel.channelId).size());
		node.manager.publishPost(channel.channelId, "short", 0);
		assertEquals(1, node.store.getPosts(channel.channelId).size());
	}

	@Test
	public void anOversizedCaptionRefusesThePost() throws Exception {
		StringBuilder caption = new StringBuilder();
		for (int i = 0; i <= ChannelConstants.MAX_ATTACHMENT_CAPTION_BYTES;
				i++) {
			caption.append('c');
		}
		try {
			node.manager.publishPostWithAttachments(channel.channelId, " ", 0,
					Collections.singletonList(new AttachmentSpec("image/jpeg",
							new byte[1024], caption.toString())));
			fail();
		} catch (DbException expected) {
		}
		assertEquals(0, node.store.getPosts(channel.channelId).size());
	}
}
