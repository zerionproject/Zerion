package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.AttachmentBlob;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChannelSubscriberCeilingsTest {

	private static final int MAX_POSTS = 10_000;
	private static final long MAX_POST_BYTES = 32L * 1024L * 1024L;
	private static final long MAX_ATTACHMENT_BYTES = 256L * 1024L * 1024L;

	private final Random random = new Random(195);
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
	public void aPostPastThePerPostCeilingIsPassedOverAndTheChainGoesOn()
			throws Exception {
		List<ChannelPost> posts = new ArrayList<>();
		ChannelPost p0 = publisher.post(channel, 0,
				new byte[ChannelConstants.PREV_HASH_BYTES], "small",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		posts.add(p0);
		ChannelPost p1 = publisher.post(channel, 1,
				publisher.chain.hashOf(p0), "huge",
				attachments(8, 20_000, 65_000, 65_000));
		posts.add(p1);
		posts.add(publisher.post(channel, 2, publisher.chain.hashOf(p1),
				"after", Collections.<ChannelPost.ChannelAttachment>emptyList()));
		publisher.seedPublisher(channel, true, null, null, false, posts);
		subscriber.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());

		subscriber.manager.refreshChannel(channel.channelId);

		List<ChannelPost> held =
				subscriber.store.getPosts(channel.channelId);
		assertEquals("the oversized post is not kept", 2, held.size());
		assertEquals(0L, held.get(0).getSeqNum());
		assertEquals("the post after it arrives", 2L,
				held.get(1).getSeqNum());
		assertEquals(2L, subscriber.store.getChannel(channel.channelId)
				.getHighestKnownPostSeq());
		assertEquals(1L, subscriber.store.posts().meta(channel.channelId)
				.skipped);
	}

	@Test
	public void aFullChannelGivesUpItsOldestPostForEachNewOne()
			throws Exception {
		List<ChannelPost> history = fakeChain(MAX_POSTS, 0);
		ChannelPost last = history.get(history.size() - 1);
		ChannelPost next = publisher.post(channel, MAX_POSTS,
				publisher.chain.hashOf(last), "one more",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		ChannelPost after = publisher.post(channel, MAX_POSTS + 1,
				publisher.chain.hashOf(next), "and another",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(next));
		subscriber.seedSubscriber(channel, true, null, null, history);

		subscriber.manager.refreshChannel(channel.channelId);
		publisher.store.posts().append(channel.channelId, after,
				publisher.chain.hashOf(after));
		subscriber.manager.refreshChannel(channel.channelId);

		ChannelPostStore.Meta meta =
				subscriber.store.posts().meta(channel.channelId);
		assertEquals("the count ceiling holds", MAX_POSTS, meta.count);
		assertEquals("the newest posts arrive", MAX_POSTS + 1L,
				subscriber.store.getChannel(channel.channelId)
						.getHighestKnownPostSeq());
		assertEquals("the oldest gave way", 2L, meta.lowest());
		assertEquals(2L, meta.pruned);
		assertEquals("one more", subscriber.store.posts().getPost(
				channel.channelId, MAX_POSTS).getBody());
	}

	@Test
	public void thePinnedPostIsNeverGivenUp() throws Exception {
		List<ChannelPost> history = fakeChain(MAX_POSTS, 0);
		ChannelPost last = history.get(history.size() - 1);
		ChannelPost next = publisher.post(channel, MAX_POSTS,
				publisher.chain.hashOf(last), "one more",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(next));
		subscriber.seedSubscriber(channel, true, null, null, history);
		publisher.manager.pinPost(channel.channelId, 0L);

		subscriber.manager.refreshChannel(channel.channelId);

		ChannelPostStore.Meta meta =
				subscriber.store.posts().meta(channel.channelId);
		assertTrue("the pinned post stays", meta.holds(0L));
		assertFalse("the next oldest gave way", meta.holds(1L));
		assertTrue(meta.holds(MAX_POSTS));
	}

	@Test
	public void theByteCeilingGivesUpOldPostsForANewOne()
			throws Exception {
		List<ChannelPost> history = fakeChain(64, 8);
		long held = 0;
		for (ChannelPost p : history) held += storedBytes(p);
		assertTrue(held < MAX_POST_BYTES);
		ChannelPost next = publisher.post(channel, 64,
				publisher.chain.hashOf(history.get(63)), "one more",
				attachments(8, 32, 32, 65_000));
		assertTrue(held + storedBytes(next) > MAX_POST_BYTES);
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(next));
		subscriber.seedSubscriber(channel, true, null, null, history);

		subscriber.manager.refreshChannel(channel.channelId);

		assertEquals("the new post is stored", 64L,
				subscriber.store.getChannel(channel.channelId)
						.getHighestKnownPostSeq());
		ChannelPostStore.Meta meta =
				subscriber.store.posts().meta(channel.channelId);
		assertTrue(meta.holds(64L));
		assertFalse("the oldest gave way", meta.holds(0L));
		assertTrue("within the byte ceiling",
				meta.bytes <= MAX_POST_BYTES);
	}

	@Test
	public void anAttachmentPastTheCeilingIsShownButNotKept()
			throws Exception {
		byte[] plain = new byte[4096];
		random.nextBytes(plain);
		byte[] key = publisher.contentKey.generateAttachmentKey();
		byte[] blob = publisher.contentKey.encryptBlob(key,
				channel.channelId, "image/jpeg", plain.length, plain);
		byte[] blobHash = crypto.hash(
				"org.zerionproject/CHANNEL_ATTACHMENT_BLOB", blob);
		ChannelPost post = publisher.post(channel, 0,
				new byte[ChannelConstants.PREV_HASH_BYTES], "photo",
				Collections.singletonList(new ChannelPost.ChannelAttachment(
						blobHash, plain.length, "image/jpeg", key, null)));
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(post));
		publisher.blobStore.put(channel.channelId, blobHash, blob);
		subscriber.seedSubscriber(channel, true, null, null,
				Collections.singletonList(post));
		fillBlobStore(subscriber, MAX_ATTACHMENT_BYTES);

		AttachmentBlob shown = subscriber.manager.fetchAttachment(
				channel.channelId, 0L, blobHash);

		assertNotNull("the attachment is shown", shown);
		assertArrayEquals(plain, shown.getPlaintextBytes());
		assertFalse("but not kept past the ceiling",
				subscriber.blobStore.has(channel.channelId, blobHash));
	}

	@Test
	public void anAttachmentWithinTheCeilingIsKept() throws Exception {
		byte[] plain = new byte[4096];
		random.nextBytes(plain);
		byte[] key = publisher.contentKey.generateAttachmentKey();
		byte[] blob = publisher.contentKey.encryptBlob(key,
				channel.channelId, "image/jpeg", plain.length, plain);
		byte[] blobHash = crypto.hash(
				"org.zerionproject/CHANNEL_ATTACHMENT_BLOB", blob);
		ChannelPost post = publisher.post(channel, 0,
				new byte[ChannelConstants.PREV_HASH_BYTES], "photo",
				Collections.singletonList(new ChannelPost.ChannelAttachment(
						blobHash, plain.length, "image/jpeg", key, null)));
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(post));
		publisher.blobStore.put(channel.channelId, blobHash, blob);
		subscriber.seedSubscriber(channel, true, null, null,
				Collections.singletonList(post));

		AttachmentBlob shown = subscriber.manager.fetchAttachment(
				channel.channelId, 0L, blobHash);

		assertNotNull(shown);
		assertTrue(subscriber.blobStore.has(channel.channelId, blobHash));
	}

	private List<ChannelPost.ChannelAttachment> attachments(int n,
			int hashBytes, int keyBytes, int thumbBytes) {
		List<ChannelPost.ChannelAttachment> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			byte[] hash = new byte[hashBytes];
			random.nextBytes(hash);
			byte[] key = new byte[keyBytes];
			random.nextBytes(key);
			out.add(new ChannelPost.ChannelAttachment(hash, 1000L,
					"image/jpeg", key, null, new byte[thumbBytes]));
		}
		return out;
	}

	private static long storedBytes(ChannelPost p) {
		long n = 64L + p.getBody().length() + p.getPrevHash().length
				+ p.getSignature().length;
		for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
			n += 32L + a.getBlobHash().length + a.getMimeType().length()
					+ a.getPerAttachmentKey().length
					+ a.getThumbnail().length;
		}
		return n;
	}

	private List<ChannelPost> fakeChain(int n, int attachmentsPerPost) {
		List<ChannelPost> out = new ArrayList<>(n);
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < n; seq++) {
			List<ChannelPost.ChannelAttachment> atts =
					attachments(attachmentsPerPost, 32, 32, 65_000);
			ChannelPost p = new ChannelPost(channel.channelId, seq, prev,
					ChannelTestNode.HOUR, "f", atts, 0L, new byte[1], true);
			out.add(p);
			prev = subscriber.chain.hashOf(p);
		}
		return out;
	}

	private void fillBlobStore(ChannelTestNode node, long bytes)
			throws Exception {
		byte[] fillerHash = new byte[32];
		Arrays.fill(fillerHash, (byte) 0x5a);
		node.blobStore.put(channel.channelId, fillerHash, new byte[1]);
		File filler = find(node.root, ChannelStore.hex(fillerHash) + ".bin");
		assertNotNull(filler);
		try (RandomAccessFile f = new RandomAccessFile(filler, "rw")) {
			f.setLength(bytes);
		}
	}

	private static File find(File dir, String name) {
		File[] children = dir.listFiles();
		if (children == null) return null;
		for (File c : children) {
			if (c.isDirectory()) {
				File found = find(c, name);
				if (found != null) return found;
			} else if (c.getName().equals(name)) {
				return c;
			}
		}
		return null;
	}
}
