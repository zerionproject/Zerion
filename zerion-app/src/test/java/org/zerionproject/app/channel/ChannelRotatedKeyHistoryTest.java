package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.AttachmentBlob;
import org.zerionproject.app.api.channel.AttachmentSpec;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ChannelRotatedKeyHistoryTest {

	private final Random random = new Random(2552);
	private final List<ChannelTestNode> others = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode publisher;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		publisher = new ChannelTestNode(crypto, clock, network);
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		for (ChannelTestNode n : others) n.deleteFiles();
	}

	@Test
	public void thePublisherStillReadsItsPostsAfterRotatingTheInviteLink()
			throws Exception {
		byte[] id = createPrivate();
		publisher.manager.publishPost(id, "written before the rotation", 0L);
		publisher.manager.publishPost(id, "also before the rotation", 0L);

		publisher.manager.rotateJoinCapability(id);
		publisher.manager.publishPost(id, "written after the rotation", 0L);

		assertEquals(Arrays.asList("written before the rotation",
				"also before the rotation", "written after the rotation"),
				bodies(publisher.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void thePublisherStillReadsThemAfterSeveralRotationsAndARestart()
			throws Exception {
		byte[] id = createPrivate();
		publisher.manager.publishPost(id, "first key", 0L);
		publisher.manager.rotateJoinCapability(id);
		publisher.manager.publishPost(id, "second key", 0L);
		publisher.manager.rotateJoinCapability(id);
		publisher.manager.publishPost(id, "third key", 0L);

		ChannelTestNode restarted =
				new ChannelTestNode(crypto, clock, network, publisher);

		assertEquals(Arrays.asList("first key", "second key", "third key"),
				bodies(restarted.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void aDeletionMarkMadeBeforeTheRotationStillHidesItsTarget()
			throws Exception {
		byte[] id = createPrivate();
		publisher.manager.publishPost(id, "kept", 0L);
		publisher.manager.publishPost(id, "taken back", 0L);
		publisher.manager.publishPost(id, ChannelConstants.TOMBSTONE_PREFIX
				+ ChannelStore.hex(id) + ":1:D", 0L);
		publisher.manager.rotateJoinCapability(id);
		publisher.manager.publishPost(id, "after", 0L);

		List<ChannelPost> shown = publisher.manager.getRecentPosts(id, 10L);

		assertEquals(Arrays.asList("kept",
				ChannelConstants.DELETED_POST_PLACEHOLDER, "after"),
				bodies(shown));
		assertEquals(1L, shown.get(1).getSeqNum());
	}

	@Test
	public void aPostDeletedBeforeTheRotationStaysGoneAfterIt()
			throws Exception {
		byte[] id = createPrivate();
		publisher.manager.publishPost(id, "kept", 0L);
		publisher.manager.publishPost(id, "taken back", 0L);
		publisher.manager.deletePost(id, 1L);

		publisher.manager.rotateJoinCapability(id);

		assertEquals(Collections.singletonList("kept"),
				bodies(publisher.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void thePublisherStillOpensAttachmentsPostedBeforeTheRotation()
			throws Exception {
		byte[] id = createPrivate();
		byte[] photo = new byte[4096];
		random.nextBytes(photo);
		byte[] thumbnail = new byte[256];
		random.nextBytes(thumbnail);
		publisher.manager.publishPostWithAttachments(id, "photo", 0L,
				Collections.singletonList(new AttachmentSpec("image/jpeg",
						photo, null, thumbnail)));
		byte[] blobHash = publisher.store.getPosts(id).get(0)
				.getAttachments().get(0).getBlobHash();

		publisher.manager.rotateJoinCapability(id);

		AttachmentBlob shown =
				publisher.manager.fetchAttachment(id, 0L, blobHash);
		assertNotNull("the attachment no longer opens", shown);
		assertArrayEquals(photo, shown.getPlaintextBytes());
		assertArrayEquals(thumbnail, publisher.manager
				.decryptAttachmentThumbnail(id, 0L, blobHash));
	}

	@Test
	public void aSubscriberWithTheNewLinkNeverSeesOlderPostsAsCiphertext()
			throws Exception {
		byte[] id = createPrivate();
		publisher.manager.publishPost(id, "before", 0L);
		publisher.manager.rotateJoinCapability(id);
		publisher.manager.publishPost(id, "after", 0L);
		ChannelTestNode subscriber = node(network);

		join(subscriber, id);

		List<ChannelPost> held = subscriber.store.getPosts(id);
		assertEquals("both posts are held", 2, held.size());
		List<ChannelPost> shown = subscriber.manager.getRecentPosts(id, 10L);
		assertNoCiphertextShown(held, shown);
		assertEquals(Collections.singletonList("after"), bodies(shown));
	}

	@Test
	public void aSubscriberWithOnlyTheOldLinkCannotReadLaterPosts()
			throws Exception {
		byte[] id = createPrivate();
		publisher.manager.publishPost(id, "before", 0L);
		ChannelTestNode subscriber =
				node(ChannelTestNode.servedBy(publisher, id));
		join(subscriber, id);
		assertEquals(Collections.singletonList("before"),
				bodies(subscriber.manager.getRecentPosts(id, 10L)));

		publisher.manager.rotateJoinCapability(id);
		publisher.manager.publishPost(id, "after", 0L);
		try {
			subscriber.manager.refreshChannel(id);
		} catch (DbException ignored) {
		}

		assertEquals(1, subscriber.store.getPosts(id).size());
		assertEquals(Collections.singletonList("before"),
				bodies(subscriber.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void aPostNoHeldKeyOpensIsLeftOutInsteadOfShownAsCiphertext()
			throws Exception {
		ChannelTestNode node = node(ChannelTestNode.noTransport());
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		byte[] held = node.contentKey.generateContentKey();
		byte[] unknown = node.contentKey.generateContentKey();
		List<ChannelPost> posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		String[] plain = {"readable", "sealed under a key nobody here holds",
				"readable too"};
		byte[][] keys = {held, unknown, held};
		for (int seq = 0; seq < plain.length; seq++) {
			ChannelPost p = node.post(channel, seq, prev,
					encrypted(node, keys[seq], channel, seq, plain[seq]),
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			posts.add(p);
			prev = node.chain.hashOf(p);
		}
		node.seedSubscriber(channel, false, new byte[32], held, posts);

		List<ChannelPost> shown =
				node.manager.getRecentPosts(channel.channelId, 10L);

		assertNoCiphertextShown(posts, shown);
		assertEquals(Arrays.asList("readable", "readable too"),
				bodies(shown));
	}

	@Test
	public void plaintextPostsFromBeforeEncryptionStillShow()
			throws Exception {
		ChannelTestNode node = node(ChannelTestNode.noTransport());
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		byte[] kContent = node.contentKey.generateContentKey();
		List<ChannelPost> posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		String[] bodies = {"a plaintext post from an older release",
				"Okay", "https://example.org/a/b", null};
		for (int seq = 0; seq < bodies.length; seq++) {
			String body = bodies[seq] != null ? bodies[seq]
					: encrypted(node, kContent, channel, seq,
							"an encrypted post");
			ChannelPost p = node.post(channel, seq, prev, body,
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			posts.add(p);
			prev = node.chain.hashOf(p);
		}
		node.seedSubscriber(channel, false, new byte[32], kContent, posts);

		assertEquals(Arrays.asList("a plaintext post from an older release",
				"Okay", "https://example.org/a/b", "an encrypted post"),
				bodies(node.manager.getRecentPosts(channel.channelId, 10L)));
	}

	@Test
	public void longPlainWordsThatAreNotCanonicalBase64StillShow()
			throws Exception {
		ChannelTestNode node = node(ChannelTestNode.noTransport());
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		byte[] kContent = node.contentKey.generateContentKey();
		List<ChannelPost> posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		String[] bodies = {"abcdefghijklmnopqrstuvwxyz",
				"Supercalifragilisticexpialidocious", "Thisisoneplainword"};
		for (int seq = 0; seq < bodies.length; seq++) {
			ChannelPost p = node.post(channel, seq, prev, bodies[seq],
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			posts.add(p);
			prev = node.chain.hashOf(p);
		}
		node.seedSubscriber(channel, false, new byte[32], kContent, posts);

		assertEquals(Arrays.asList(bodies),
				bodies(node.manager.getRecentPosts(channel.channelId, 10L)));
	}

	@Test
	public void plaintextPostsStillShowAfterAChannelWithoutAKeyIsRotated()
			throws Exception {
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		publisher.seedPublisher(channel, false, new byte[32], null, false,
				publisher.posts(channel, 2));

		publisher.manager.rotateJoinCapability(channel.channelId);
		publisher.manager.publishPost(channel.channelId, "after", 0L);

		assertEquals(Arrays.asList("post 0", "post 1", "after"),
				bodies(publisher.manager.getRecentPosts(channel.channelId,
						10L)));
	}

	@Test
	public void deletingTheChannelErasesItsRetiredKeys() throws Exception {
		byte[] id = createPrivate();
		publisher.manager.publishPost(id, "before", 0L);
		byte[] first = publisher.store.getChannel(id).getContentKey();
		publisher.manager.rotateJoinCapability(id);
		byte[] second = publisher.store.getChannel(id).getContentKey();
		publisher.manager.rotateJoinCapability(id);
		assertTrue("the first key is not kept", stored(first));
		assertTrue("the second key is not kept", stored(second));

		publisher.manager.deleteChannel(id);

		assertFalse("the first key is left behind", stored(first));
		assertFalse("the second key is left behind", stored(second));
	}

	private byte[] createPrivate() throws Exception {
		return publisher.manager.createChannel("name", "about", false)
				.getChannelId();
	}

	private ChannelTestNode node(ChannelTransport transport) {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, transport);
		others.add(n);
		return n;
	}

	private void join(ChannelTestNode subscriber, byte[] id)
			throws Exception {
		String link = publisher.manager.exportInviteLink(id);
		subscriber.manager.joinChannel(
				subscriber.manager.parseInviteLink(link));
		subscriber.manager.refreshChannel(id);
	}

	private boolean stored(byte[] key) {
		String encoded = Base64.getEncoder().withoutPadding()
				.encodeToString(key);
		for (String[] row : publisher.settings.rows()) {
			if (row[2] != null && row[2].contains(encoded)) return true;
		}
		return false;
	}

	private static String encrypted(ChannelTestNode node, byte[] key,
			ChannelTestNode.TestChannel channel, long seq, String body)
			throws Exception {
		return Base64.getEncoder().withoutPadding().encodeToString(
				node.contentKey.encryptBody(key, channel.channelId, seq,
						body));
	}

	private static void assertNoCiphertextShown(List<ChannelPost> held,
			List<ChannelPost> shown) {
		for (ChannelPost h : held) {
			for (ChannelPost s : shown) {
				assertFalse("a post is shown as its ciphertext",
						h.getBody().equals(s.getBody()));
			}
		}
	}

	private static List<String> bodies(List<ChannelPost> posts) {
		List<String> out = new ArrayList<>(posts.size());
		for (ChannelPost p : posts) out.add(p.getBody());
		return out;
	}
}
