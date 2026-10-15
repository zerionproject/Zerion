package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.event.PurgeRequestedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelEditErasureTest {

	private static final String ORIGINAL = "the wording as first published";
	private static final String CORRECTED = "the wording after correction";

	private final Random random = new Random(4411);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;

	private interface ReplyEdit {
		byte[] apply(byte[] reply) throws Exception;
	}

	@Nullable
	private volatile ReplyEdit replyEdit;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		owner = new ChannelTestNode(crypto, clock, network);
		nodes.add(owner);
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	@Test
	public void anOwnerEditLeavesTheOriginalInNoRecordAnywhere()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.publishPost(id, "an unrelated post", 0L);
		ChannelTestNode reader = join(id);
		ChannelTestNode mirror = join(id);
		mirror.manager.setMirrorOptedIn(id, true);
		for (ChannelTestNode n : Arrays.asList(owner, reader, mirror)) {
			assertTrue("the scan finds the text before the edit",
					holds(n, ORIGINAL));
		}

		owner.manager.editPost(id, 0L, CORRECTED);
		reader.manager.refreshChannel(id);
		mirror.manager.refreshChannel(id);
		ChannelTestNode late = join(id);

		for (ChannelTestNode n : Arrays.asList(owner, reader, mirror, late)) {
			assertFalse(holds(n, ORIGINAL));
			List<ChannelPost> shown = n.manager.getRecentPosts(id, 10L);
			assertEquals(Arrays.asList(CORRECTED, "an unrelated post"),
					bodies(shown));
			assertTrue(shown.get(0).isEdited());
			ChannelPost stored = n.store.posts().getPost(id, 0L);
			assertTrue(stored.isErased());
			assertEquals("", stored.getBody());
		}
		for (ChannelTestNode n : Arrays.asList(owner, reader, mirror)) {
			assertTrue("the old pages are purged", purgeRequests(n) > 0);
		}
	}

	@Test
	public void theOriginalStaysGoneAfterARestartAndAReconnect()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		ChannelTestNode reader = join(id);
		owner.manager.editPost(id, 0L, CORRECTED);
		reader.manager.refreshChannel(id);

		ChannelTestNode restarted = new ChannelTestNode(crypto, clock,
				network, reader);
		nodes.add(restarted);
		restarted.manager.refreshChannel(id);
		restarted.manager.refreshChannel(id);

		assertFalse(holds(restarted, ORIGINAL));
		assertEquals(Collections.singletonList(CORRECTED),
				bodies(restarted.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void anEditorEditLeavesTheOriginalInNoRecordAnywhere()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "by the owner", 0L);
		ChannelTestNode editor = editor(id);
		editor.manager.publishPost(id, "an editor draft", 0L);
		ChannelTestNode reader = join(id);
		assertTrue(holds(reader, "an editor draft"));

		editor.manager.editPost(id, 1L, "what the editor meant");
		reader.manager.refreshChannel(id);
		ChannelTestNode late = join(id);

		for (ChannelTestNode n : Arrays.asList(owner, editor, reader, late)) {
			assertFalse(holds(n, "an editor draft"));
			List<ChannelPost> shown = n.manager.getRecentPosts(id, 10L);
			assertEquals(Arrays.asList("by the owner",
					"what the editor meant"), bodies(shown));
			assertTrue(shown.get(1).signedByDelegate());
			assertTrue(shown.get(1).isEdited());
		}
	}

	@Test
	public void aSecondEditLeavesNeitherTheOriginalNorTheFirstEdit()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		ChannelTestNode reader = join(id);
		owner.manager.editPost(id, 0L, "an interim correction");
		reader.manager.refreshChannel(id);
		assertTrue(holds(reader, "an interim correction"));
		owner.manager.editPost(id, 0L, CORRECTED);
		reader.manager.refreshChannel(id);
		ChannelTestNode late = join(id);

		for (ChannelTestNode n : Arrays.asList(owner, reader, late)) {
			assertFalse(holds(n, ORIGINAL));
			assertFalse(holds(n, "an interim correction"));
			assertEquals(Collections.singletonList(CORRECTED),
					bodies(n.manager.getRecentPosts(id, 10L)));
			assertEquals(2, n.store.getPosts(id).size());
		}
	}

	@Test
	public void aPrivateEditLeavesNeitherTheOriginalTextNorItsCiphertext()
			throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		String ciphertext = owner.store.posts().getPost(id, 0L).getBody();
		ChannelTestNode reader = join(id);
		assertTrue(holds(reader, ciphertext));

		owner.manager.editPost(id, 0L, CORRECTED);
		reader.manager.refreshChannel(id);
		ChannelTestNode late = join(id);

		for (ChannelTestNode n : Arrays.asList(owner, reader, late)) {
			assertFalse(holds(n, ciphertext));
			assertFalse(holds(n, ORIGINAL));
			assertEquals(Collections.singletonList(CORRECTED),
					bodies(n.manager.getRecentPosts(id, 10L)));
		}
	}

	@Test
	public void aSubscriberThatCannotReadTheEditIsNotStoppedByIt()
			throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.editPost(id, 0L, CORRECTED);
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the new link", 0L);

		ChannelTestNode late = join(id);

		assertNull("a post made under the old key is not sent",
				late.store.posts().getPost(id, 0L));
		assertEquals(2, late.store.getPosts(id).size());
		assertEquals(Collections.singletonList("after the new link"),
				bodies(late.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void theErasedPostStillVerifiesAndCarriesTheChain()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		ChannelPost original = owner.store.posts().getPost(id, 0L);
		owner.manager.editPost(id, 0L, CORRECTED);
		owner.manager.publishPost(id, "written later", 0L);

		ChannelPost erased = owner.store.posts().getPost(id, 0L);
		assertTrue(erased.isErased());
		assertArrayEquals(owner.chain.hashOf(original),
				owner.chain.hashOf(erased));
		assertArrayEquals(owner.codec.signedInputOf(original),
				owner.codec.signedInputOf(erased));
		assertEquals(ChannelChainVerifier.Result.OK,
				owner.chain.verifyOrdered(owner.store.getPosts(id)));

		ChannelTestNode late = join(id);
		assertEquals(Arrays.asList(CORRECTED, "written later"),
				bodies(late.manager.getRecentPosts(id, 10L)));
		assertEquals(3, late.store.getPosts(id).size());
	}

	@Test
	public void anErasedPostWithoutItsEditIsRefused() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.publishPost(id, "the next post", 0L);
		ChannelTestNode reader = joinWithoutRefresh(id);

		replyEdit = reply -> erasedOnTheWay(reply, false);
		reader.manager.refreshChannel(id);
		assertTrue("an erased post without an edit is not stored",
				reader.store.getPosts(id).isEmpty());
		assertTrue(reader.manager.getRecentPosts(id, 10L).isEmpty());

		replyEdit = reply -> erasedOnTheWay(reply, true);
		reader.manager.refreshChannel(id);
		assertTrue("an erased post under a false hash is not stored",
				reader.store.getPosts(id).isEmpty());

		replyEdit = null;
		reader.manager.refreshChannel(id);
		assertEquals(Arrays.asList(ORIGINAL, "the next post"),
				bodies(reader.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void anErasedPostInAnOlderVersionReplyFailsTheReply()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.editPost(id, 0L, CORRECTED);
		ChannelTestNode reader = joinWithoutRefresh(id);
		replyEdit = reply -> {
			BdfDictionary d = read(reply);
			d.put("v", (long) ChannelConstants.PROTOCOL_VERSION_POST_CHANGES);
			return write(d);
		};
		try {
			reader.manager.refreshChannel(id);
			fail("a version 4 reply carried an erased post");
		} catch (DbException expected) {
		}
		assertTrue(reader.store.getPosts(id).isEmpty());
	}

	@Test
	public void anEditOnTheNextPageStillReachesANewSubscriber()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		for (int i = 1; i < ChannelConstants.PULL_BATCH_MAX_POSTS; i++) {
			owner.manager.publishPost(id, "filler " + i, 0L);
		}
		owner.manager.editPost(id, 0L, CORRECTED);

		ChannelTestNode late = join(id);

		assertFalse(holds(late, ORIGINAL));
		List<ChannelPost> shown = late.manager.getRecentPosts(id, 200L);
		assertEquals((int) ChannelConstants.PULL_BATCH_MAX_POSTS,
				shown.size());
		assertEquals(CORRECTED, shown.get(0).getBody());
		assertTrue(shown.get(0).isEdited());
	}

	@Test
	public void deletingAnEditedPostLeavesNeitherText() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.publishPost(id, "stays", 0L);
		ChannelTestNode reader = join(id);
		owner.manager.editPost(id, 0L, CORRECTED);
		reader.manager.refreshChannel(id);

		owner.manager.deletePost(id, 0L);
		reader.manager.refreshChannel(id);

		for (ChannelTestNode n : Arrays.asList(owner, reader)) {
			assertNull(n.store.posts().getPost(id, 0L));
			assertFalse(holds(n, ORIGINAL));
			assertFalse(holds(n, CORRECTED));
			assertEquals(Collections.singletonList("stays"),
					bodies(n.manager.getRecentPosts(id, 10L)));
		}
	}

	@Test
	public void anInterruptedErasureIsCompletedOnTheNextRead()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		ChannelTestNode reader = join(id);
		String ns = "zerion-channels-post:" + ChannelStore.hex(id) + ":0";
		Settings before = reader.settings.getSettings(ns);
		owner.manager.editPost(id, 0L, CORRECTED);
		reader.manager.refreshChannel(id);
		reader.settings.mergeSettings(before, ns);
		assertTrue(holds(reader, ORIGINAL));

		assertEquals(Collections.singletonList(CORRECTED),
				bodies(reader.manager.getRecentPosts(id, 10L)));

		assertFalse(holds(reader, ORIGINAL));
		assertTrue(reader.store.posts().getPost(id, 0L).isErased());
	}

	@Test
	public void anInterruptedErasureIsCompletedByTheStorageScan()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		ChannelTestNode reader = join(id);
		String ns = "zerion-channels-post:" + ChannelStore.hex(id) + ":0";
		Settings before = reader.settings.getSettings(ns);
		owner.manager.editPost(id, 0L, CORRECTED);
		reader.manager.refreshChannel(id);
		reader.settings.mergeSettings(before, ns);
		assertTrue(holds(reader, ORIGINAL));

		reader.manager.purgeExpiredPosts();

		assertFalse(holds(reader, ORIGINAL));
	}

	@Test
	public void onlyFormatTwoPostsCanBeEdited() {
		long now = clock.currentTimeMillis() / ChannelTestNode.HOUR
				* ChannelTestNode.HOUR;
		ChannelPost legacy = new ChannelPost(new byte[32], 0L, new byte[32],
				now, "legacy", Collections.<ChannelPost.ChannelAttachment>
				emptyList(), 0L, new byte[0], false);
		ChannelPost current = new ChannelPost(new byte[32], 0L, new byte[32],
				now, "current", Collections.<ChannelPost.ChannelAttachment>
				emptyList(), 0L, new byte[0], false, null, null, false,
				ChannelPost.FORMAT_V2, new byte[16]);
		assertFalse(legacy.isEditableAt(now));
		assertTrue(current.isEditableAt(now));
	}

	@Test
	public void thePinnedPostKeepsItsEditWhenTheWindowRollsOver()
			throws Exception {
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		ChannelTestNode publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		ChannelTestNode subscriber = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
		nodes.add(publisher);
		nodes.add(subscriber);
		int max = ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL;
		String mark = ChannelPostMarkers.edit(channel.channelId, 0L,
				CORRECTED);
		List<ChannelPost> history = new ArrayList<>(max);
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < max; seq++) {
			ChannelPost p = new ChannelPost(channel.channelId, seq, prev,
					ChannelTestNode.HOUR, seq == 1 ? mark : "f",
					Collections.<ChannelPost.ChannelAttachment>emptyList(),
					0L, new byte[1], true);
			history.add(seq == 0 ? p.erased(new byte[32]) : p);
			prev = subscriber.chain.hashOf(p);
		}
		ChannelPost next = publisher.post(channel, max, prev, "news",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(next));
		subscriber.seedSubscriber(channel, true, null, null, history);
		pin(publisher, channel.channelId, 0L);

		subscriber.manager.refreshChannel(channel.channelId);

		assertTrue(subscriber.store.posts().getPost(channel.channelId, 0L)
				!= null);
		assertTrue("the edit of the pinned post is kept", subscriber.store
				.posts().getPost(channel.channelId, 1L) != null);
		assertNull(subscriber.store.posts().getPost(channel.channelId, 2L));
	}

	@Test
	public void aFullReplyEndingInAPostOfARemovedEditorDoesNotStall()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		ChannelTestNode editor = editor(id);
		int max = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		for (int i = 1; i < max - 1; i++) {
			owner.manager.publishPost(id, "filler " + i, 0L);
		}
		editor.manager.publishPost(id, "by an editor", 0L);
		assertTrue(owner.store.posts().getPost(id, max - 1L)
				.signedByDelegate());
		owner.manager.publishPost(id, "after the editor", 0L);
		owner.manager.editPost(id, 0L, CORRECTED);
		owner.manager.revokeDelegation(id, owner.store.getChannel(id)
				.getActiveDelegations().get(0).getDelegationSeq());
		ChannelTestNode late = joinWithoutRefresh(id);

		late.manager.refreshChannel(id);
		assertTrue("the first page is taken although its last post "
						+ "cannot be checked yet",
				late.store.posts().getPost(id, max - 2L) != null);
		assertTrue(late.store.posts().getPost(id, 0L).isErased());
		assertFalse(bodies(late.manager.getRecentPosts(id, 200L))
				.contains(""));
		late.manager.refreshChannel(id);

		List<ChannelPost> shown = late.manager.getRecentPosts(id, 200L);
		assertEquals(0L, shown.get(0).getSeqNum());
		assertEquals(CORRECTED, shown.get(0).getBody());
		assertTrue(shown.get(0).isEdited());
		assertEquals("after the editor",
				shown.get(shown.size() - 1).getBody());
		assertFalse(holds(late, ORIGINAL));
	}

	@Test
	public void aBrokenPostBetweenAnErasedPostAndItsEditDoesNotStallThere()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.publishPost(id, "one", 0L);
		owner.manager.publishPost(id, "two", 0L);
		owner.manager.publishPost(id, "three", 0L);
		owner.manager.editPost(id, 0L, CORRECTED);
		ChannelTestNode late = joinWithoutRefresh(id);

		replyEdit = reply -> brokenOnTheWay(reply, 3L);
		late.manager.refreshChannel(id);
		assertEquals(Arrays.asList(0L, 1L, 2L),
				seqs(late.store.getPosts(id)));
		assertEquals("the erased post waits for its edit unseen",
				Arrays.asList("one", "two"),
				bodies(late.manager.getRecentPosts(id, 10L)));

		replyEdit = null;
		late.manager.refreshChannel(id);
		assertEquals(Arrays.asList(CORRECTED, "one", "two", "three"),
				bodies(late.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void aPostStrippedOnTheWayInAFullReplyIsFetchedAgainWithItsBody()
			throws Exception {
		byte[] id = create(true);
		int max = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		for (int i = 0; i <= max; i++) {
			owner.manager.publishPost(id, "post " + i, 0L);
		}
		ChannelTestNode late = joinWithoutRefresh(id);
		int[] pulls = {0};
		replyEdit = reply -> pulls[0]++ == 0
				? strippedOnTheWay(reply, max - 1L) : reply;

		late.manager.refreshChannel(id);

		ChannelPost stored = late.store.posts().getPost(id, max - 1L);
		assertFalse("the stripped post is fetched again", stored.isErased());
		assertEquals("post " + (max - 1), stored.getBody());
		assertTrue(late.store.posts().meta(id).pending.isEmpty());
		List<Long> shown = seqs(late.manager.getRecentPosts(id, 200L));
		assertEquals(max + 1, shown.size());
		assertEquals(max + 1, late.manager.getUnreadCount(id));
		assertEquals(1, late.announced(max - 1L));
		late.manager.refreshChannel(id);
		assertEquals(1, late.announced(max - 1L));
	}

	@Test
	public void aPostStrippedBeforeAFailingPostIsFetchedAgainWithItsBody()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.publishPost(id, "one", 0L);
		owner.manager.publishPost(id, "two", 0L);
		ChannelTestNode late = joinWithoutRefresh(id);
		replyEdit = reply -> brokenOnTheWay(strippedOnTheWay(reply, 0L), 1L);
		late.manager.refreshChannel(id);
		assertTrue(late.store.posts().getPost(id, 0L).isErased());
		assertTrue(late.manager.getRecentPosts(id, 10L).isEmpty());

		replyEdit = null;
		late.manager.refreshChannel(id);

		assertEquals(Arrays.asList(ORIGINAL, "one", "two"),
				bodies(late.manager.getRecentPosts(id, 10L)));
		assertFalse(late.store.posts().getPost(id, 0L).isErased());
		assertEquals(3, late.manager.getUnreadCount(id));
		assertEquals(1, late.announced(0L));
	}

	@Test
	public void aReplyFromAnOnionNoSignedManifestNamesGivesNoPosts()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.publishPost(id, "the next post", 0L);
		String real = owner.store.getChannel(id).getCurrentOnion();
		int[] asked = {0};
		byte[][] served = new byte[1][];
		ChannelTransport.ChannelServer impostor = network.bindServer(id,
				"key-impostor", request -> {
					asked[0]++;
					try {
						byte[] r = served[0] != null ? served[0]
								: brokenOnTheWay(strippedOnTheWay(
								network.requestFromOnion(real, request), 0L),
								1L);
						served[0] = r;
						return r;
					} catch (Exception e) {
						return new byte[0];
					}
				});
		String fake = impostor.getOnionAddress();
		ChannelTestNode reader = joinThrough(
				owner.manager.exportInviteLink(id).replace(real, fake));

		reader.manager.refreshChannel(id);

		assertEquals(1, asked[0]);
		assertEquals(real, reader.store.getChannel(id).getCurrentOnion());
		assertFalse(reader.store.posts().getPost(id, 0L).isErased());
		assertEquals(Arrays.asList(ORIGINAL, "the next post"),
				bodies(reader.manager.getRecentPosts(id, 10L)));
		assertEquals(2, reader.manager.getUnreadCount(id));
		assertFalse("an address no signed manifest named is not kept",
				reader.store.onions().remembered(id).contains(fake));

		network.unbindAll();
		network.bindServer(id, "key-impostor", request -> {
			asked[0]++;
			return served[0];
		});
		try {
			reader.manager.refreshChannel(id);
			fail("the owner's onion is dark");
		} catch (DbException expected) {
		}
		clock.advance(ChannelConstants.ONION_FALLBACK_AFTER_MS + 60_000L);
		for (int i = 0; i < 4; i++) {
			try {
				reader.manager.refreshChannel(id);
			} catch (DbException expected) {
			}
		}
		assertEquals("the impostor is never asked again", 1, asked[0]);
		assertEquals(2, reader.store.getPosts(id).size());
	}

	@Test
	public void atMostTwoHundredErasedPostsWaitForTheirEdit()
			throws Exception {
		byte[] id = create(true);
		int max = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		for (int i = 0; i <= 3 * max; i++) {
			owner.manager.publishPost(id, "post " + i, 0L);
		}
		ChannelTestNode late = joinWithoutRefresh(id);
		int[] pulls = {0};
		replyEdit = reply -> {
			if (pulls[0]++ >= 3) throw new IOException();
			return allStrippedOnTheWay(reply);
		};
		try {
			late.manager.refreshChannel(id);
			fail("the fourth page arrived");
		} catch (DbException expected) {
		}

		ChannelPostStore.Meta m = late.store.posts().meta(id);
		assertEquals(2 * max, m.pending.size());
		assertEquals(Long.valueOf(max), m.pending.first());
		assertNull(late.store.posts().getPost(id, 0L));
		assertEquals(max, late.manager.getPostsGivenUp(id));

		replyEdit = null;
		late.manager.refreshChannel(id);

		assertTrue(late.store.posts().meta(id).pending.isEmpty());
		List<ChannelPost> shown = late.manager.getRecentPosts(id, 1000L);
		assertEquals(2 * max + 1, shown.size());
		assertEquals("post " + max, shown.get(0).getBody());
		assertEquals(2 * max + 1, late.manager.getUnreadCount(id));
	}

	@Test
	public void aPostThatArrivesBeforeItsKeyIsCountedAndAnnouncedOnce()
			throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.editPost(id, 0L, CORRECTED);
		owner.manager.publishPost(id, "written later", 0L);
		ChannelTestNode late = joinWithoutRefresh(id);
		replyEdit = reply -> {
			BdfDictionary d = read(reply);
			d.remove("contentKeyEnvelope");
			return write(d);
		};
		late.manager.refreshChannel(id);
		assertNull(late.store.getChannel(id).getContentKey());
		assertEquals(3, late.store.getPosts(id).size());
		assertEquals(0, late.manager.getUnreadCount(id));
		assertEquals(0, late.announced(0L));
		assertEquals(0, late.announced(2L));
		assertTrue(late.manager.getRecentPosts(id, 10L).isEmpty());

		replyEdit = null;
		late.manager.refreshChannel(id);
		late.manager.refreshChannel(id);

		assertEquals(Arrays.asList(CORRECTED, "written later"),
				bodies(late.manager.getRecentPosts(id, 10L)));
		assertEquals(2, late.manager.getUnreadCount(id));
		assertEquals(1, late.announced(0L));
		assertEquals(0, late.announced(1L));
		assertEquals(1, late.announced(2L));
	}

	@Test
	public void aPostErasedWhileUnreadableIsNotCountedWhenItsEditIsTakenAgain()
			throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the new link", 0L);
		ChannelTestNode between = join(id);
		owner.manager.editPost(id, 0L, CORRECTED);
		between.manager.refreshChannel(id);
		assertTrue(between.store.posts().getPost(id, 0L).isErased());

		owner.manager.editPost(id, 0L, "a second correction");
		between.manager.refreshChannel(id);
		assertEquals(1, between.manager.getUnreadCount(id));
		clock.advance(8L * 24L * ChannelTestNode.HOUR);
		between.manager.purgeExpiredPosts();

		assertEquals(1, between.manager.getUnreadCount(id));
		assertEquals(0, between.announced(0L));
		assertEquals(Collections.singletonList("after the new link"),
				bodies(between.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void aCommentOnAPostThisMemberCannotReadRaisesNoNotification()
			throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		ChannelTestNode reader = join(id);
		reader.manager.postComment(id, 0L, "a remark");
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the new link", 0L);

		ChannelTestNode between = join(id);

		assertEquals(1, between.commentStore.getComments(id).size());
		assertEquals(0, between.commentAlerts(0L));
		assertEquals(0, between.announced(0L));
		assertEquals(1, between.manager.getUnreadCount(id));
	}

	@Test
	public void aWaitingPostOfAnEditorRevokedWithItsEditIsNotAnnounced()
			throws Exception {
		byte[] id = create(true);
		int max = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		for (int i = 0; i < max - 2; i++) {
			owner.manager.publishPost(id, "filler " + i, 0L);
		}
		ChannelTestNode editor = editor(id);
		editor.manager.publishPost(id, "an editor draft", 0L);
		long draft = max - 2L;
		assertTrue(owner.store.posts().getPost(id, draft).signedByDelegate());
		owner.manager.publishPost(id, "the end of the page", 0L);
		editor.manager.editPost(id, draft, "what the editor meant");
		ChannelTestNode late = joinWithoutRefresh(id);
		int[] pulls = {0};
		replyEdit = reply -> {
			if (pulls[0]++ > 0) throw new IOException();
			return reply;
		};
		try {
			late.manager.refreshChannel(id);
			fail("the second page arrived");
		} catch (DbException expected) {
		}
		assertTrue(late.store.posts().getPost(id, draft).isErased());
		owner.manager.revokeDelegation(id, owner.store.getChannel(id)
				.getActiveDelegations().get(0).getDelegationSeq());

		replyEdit = null;
		late.manager.refreshChannel(id);

		assertTrue(late.store.posts().getPost(id, draft).isWithheld());
		assertTrue(late.store.posts().meta(id).pending.isEmpty());
		assertEquals(0, late.announced(draft));
		assertEquals(max - 1, late.manager.getUnreadCount(id));
	}

	@Test
	public void aPostProvenAfterTheChannelWasReadIsCountedAndAnnounced()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		int max = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		for (int i = 1; i < max; i++) {
			owner.manager.publishPost(id, "filler " + i, 0L);
		}
		owner.manager.editPost(id, 0L, CORRECTED);
		ChannelTestNode late = joinWithoutRefresh(id);
		int[] pulls = {0};
		replyEdit = reply -> {
			if (pulls[0]++ > 0) throw new IOException();
			return reply;
		};
		try {
			late.manager.refreshChannel(id);
			fail("the second page arrived");
		} catch (DbException expected) {
		}
		late.manager.markChannelRead(id);
		assertEquals(0, late.manager.getUnreadCount(id));

		replyEdit = null;
		late.manager.refreshChannel(id);

		assertEquals(1, late.manager.getUnreadCount(id));
		assertEquals(1, late.announced(0L));
		ChannelPost first = late.manager.getRecentPosts(id, 200L).get(0);
		assertEquals(CORRECTED, first.getBody());
		assertFalse(first.isRead());
		late.manager.markChannelRead(id);
		assertEquals(0, late.manager.getUnreadCount(id));
		assertTrue(late.manager.getRecentPosts(id, 200L).get(0).isRead());
	}

	@Test
	public void postsThisMemberCannotReadKeepTheChannelRecordSmall()
			throws Exception {
		int[] before = {1, 60};
		int[] sizes = new int[before.length];
		for (int round = 0; round < before.length; round++) {
			byte[] id = create(false);
			for (int i = 0; i < before[round]; i++) {
				owner.manager.publishPost(id, "before " + i, 0L);
			}
			owner.manager.rotateJoinCapability(id);
			owner.manager.publishPost(id, "after the new link", 0L);
			ChannelTestNode between = join(id);
			assertEquals(1, between.manager.getUnreadCount(id));
			sizes[round] = between.settings.getSettings(
					"zerion-channels-post-meta:" + ChannelStore.hex(id))
					.get("m").length();
		}
		assertTrue(Arrays.toString(sizes), sizes[1] - sizes[0] < 16);
	}

	@Test
	public void postsThisMemberCannotReadAreHeldInBoundedRuns()
			throws Exception {
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		byte[] capability = new byte[ChannelConstants.JOIN_CAPABILITY_BYTES];
		random.nextBytes(capability);
		byte[] key = owner.contentKey.generateContentKey();
		byte[] never = owner.contentKey.generateContentKey();
		ChannelTestNode publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		ChannelTestNode subscriber = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
		nodes.add(publisher);
		nodes.add(subscriber);
		int pairs = ChannelPostCeilings.MAX_UNREADABLE_RUNS + 6;
		List<ChannelPost> history = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < 2 * pairs; seq++) {
			byte[] k = seq % 2 == 0 ? key : never;
			String body = Base64.getEncoder().withoutPadding().encodeToString(
					owner.contentKey.encryptBody(k, channel.channelId, seq,
							"post " + seq));
			ChannelPost p = publisher.post(channel, seq, prev, body,
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			history.add(p);
			prev = publisher.chain.hashOf(p);
		}
		publisher.seedPublisher(channel, false, capability, key, false,
				history);
		subscriber.seedSubscriber(channel, false, capability, key,
				Collections.<ChannelPost>emptyList());

		subscriber.manager.refreshChannel(channel.channelId);

		assertEquals(pairs, subscriber.manager.getUnreadCount(
				channel.channelId));
		assertEquals(pairs, subscriber.manager.getRecentPosts(
				channel.channelId, 1000L).size());
		assertEquals(pairs + ChannelPostCeilings.MAX_UNREADABLE_RUNS,
				subscriber.store.getPosts(channel.channelId).size());
		assertEquals(pairs - ChannelPostCeilings.MAX_UNREADABLE_RUNS,
				subscriber.manager.getPostsGivenUp(channel.channelId));
		assertEquals(2L * pairs - 1L, subscriber.store.posts()
				.tip(channel.channelId).seqNum);
	}

	@Test
	public void anErasedPostStoredWithoutItsKeyByAnEarlierBuildStillShows()
			throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.editPost(id, 0L, CORRECTED);
		ChannelPost erased = owner.store.posts().getPost(id, 0L);
		assertTrue(erased.isErased());
		owner.store.posts().remove(id, Collections.singletonList(0L), false);
		owner.store.posts().append(id,
				erased.erased(erased.getErasedBodyHash(), null),
				owner.chain.hashOf(erased));

		assertEquals(Collections.singletonList(CORRECTED),
				bodies(owner.manager.getRecentPosts(id, 10L)));
		ChannelTestNode late = join(id);
		assertEquals(Collections.singletonList(CORRECTED),
				bodies(late.manager.getRecentPosts(id, 10L)));

		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the new link", 0L);
		ChannelTestNode after = join(id);
		assertNull("a post whose key is unknown is not sent once the "
				+ "link was renewed", after.store.posts().getPost(id, 0L));
		assertEquals(Arrays.asList(CORRECTED, "after the new link"),
				bodies(owner.manager.getRecentPosts(id, 10L)));
	}

	@Test
	public void theWeeklyScanDoesNotWalkTheChannelAgainForEveryEdit()
			throws Exception {
		byte[] id = create(true);
		int n = 40;
		for (int i = 0; i < n; i++) {
			owner.manager.publishPost(id, "post " + i, 0L);
		}
		for (int i = 0; i < n; i++) {
			owner.manager.editPost(id, i, "edited " + i);
		}
		long held = 0L;
		String prefix = "zerion-channels-post:" + ChannelStore.hex(id) + ":";
		for (String[] row : owner.settings.rows()) {
			if (row[0].startsWith(prefix)) held += row[2].length();
		}
		long before = owner.settings.bytesRead();

		owner.manager.purgeExpiredPosts();

		long read = owner.settings.bytesRead() - before;
		assertTrue(read + " read for " + held + " held", read < 3L * held);
		assertEquals("edited 0",
				owner.manager.getRecentPosts(id, 100L).get(0).getBody());
	}

	@Test
	public void anErasedPostWaitingForItsEditIsNeitherCountedNorAnnounced()
			throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		int max = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		for (int i = 1; i < max; i++) {
			owner.manager.publishPost(id, "filler " + i, 0L);
		}
		owner.manager.editPost(id, 0L, CORRECTED);
		ChannelTestNode late = joinWithoutRefresh(id);
		int[] pulls = {0};
		replyEdit = reply -> {
			if (pulls[0]++ > 0) throw new IOException();
			return reply;
		};
		try {
			late.manager.refreshChannel(id);
			fail("the second page arrived");
		} catch (DbException expected) {
		}

		assertTrue(late.store.posts().getPost(id, 0L).isErased());
		assertEquals(max - 1, late.manager.getUnreadCount(id));
		assertEquals(0, late.announced(0L));
		assertEquals(max - 1, late.manager.getRecentPosts(id, 200L).size());

		replyEdit = null;
		late.manager.refreshChannel(id);

		assertEquals(max, late.manager.getUnreadCount(id));
		assertEquals(1, late.announced(0L));
		List<ChannelPost> shown = late.manager.getRecentPosts(id, 200L);
		assertEquals(max, shown.size());
		assertEquals(CORRECTED, shown.get(0).getBody());
	}

	@Test
	public void aCommentOnAPostWaitingForItsEditRaisesNoNotification()
			throws Exception {
		byte[] id = create(true);
		int max = (int) ChannelConstants.PULL_BATCH_MAX_POSTS;
		for (int i = 0; i < max; i++) {
			owner.manager.publishPost(id, "post " + i, 0L);
		}
		ChannelTestNode reader = join(id);
		reader.manager.postComment(id, max - 1L, "a remark");
		owner.manager.editPost(id, max - 1L, CORRECTED);
		ChannelTestNode late = joinWithoutRefresh(id);
		int[] pulls = {0};
		replyEdit = reply -> {
			if (pulls[0]++ > 0) throw new IOException();
			return reply;
		};
		try {
			late.manager.refreshChannel(id);
			fail("the second page arrived");
		} catch (DbException expected) {
		}

		assertTrue(late.store.posts().getPost(id, max - 1L).isErased());
		assertEquals(1, late.commentStore.getComments(id).size());
		assertEquals(0, late.commentAlerts(max - 1L));
		assertEquals(0, late.announced(max - 1L));

		replyEdit = null;
		late.manager.refreshChannel(id);

		assertEquals(1, late.announced(max - 1L));
		List<ChannelPost> shown = late.manager.getRecentPosts(id, 200L);
		assertEquals(CORRECTED, shown.get(shown.size() - 1).getBody());
		assertEquals(1, late.commentStore.getComments(id).size());
	}

	@Test
	public void anEditAfterANewLinkShowsNoPostMadeUnderTheOldKey()
			throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the new link", 0L);
		ChannelTestNode between = join(id);
		assertEquals(Collections.singletonList("after the new link"),
				bodies(between.manager.getRecentPosts(id, 10L)));

		owner.manager.editPost(id, 0L, CORRECTED);
		between.manager.refreshChannel(id);
		ChannelTestNode late = join(id);

		for (ChannelTestNode n : Arrays.asList(between, late)) {
			assertEquals(Collections.singletonList("after the new link"),
					bodies(n.manager.getRecentPosts(id, 10L)));
		}
		assertTrue("the copy is erased although it stays hidden",
				between.store.posts().getPost(id, 0L).isErased());
		assertNull("a post made under the old key is not sent",
				late.store.posts().getPost(id, 0L));
		assertEquals(Arrays.asList(CORRECTED, "after the new link"),
				bodies(owner.manager.getRecentPosts(id, 10L)));
		assertTrue(owner.store.posts().getPost(id, 0L).isErased());
	}

	@Test
	public void aPostMadeUnderAKeyThisMemberNeverHeldIsNeitherCountedNorAnnounced()
			throws Exception {
		byte[] id = create(false);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.rotateJoinCapability(id);
		owner.manager.publishPost(id, "after the new link", 0L);
		ChannelTestNode between = join(id);
		assertEquals(1, between.manager.getUnreadCount(id));
		assertEquals(0, announced(between, id, 0L));

		owner.manager.editPost(id, 0L, CORRECTED);
		between.manager.refreshChannel(id);
		assertEquals(1, between.manager.getUnreadCount(id));
		assertEquals(0, announced(between, id, 0L));
		assertEquals(Collections.singletonList("after the new link"),
				bodies(between.manager.getRecentPosts(id, 10L)));
	}

	private static int announced(ChannelTestNode node, byte[] id, long seq) {
		int n = 0;
		synchronized (node.events) {
			for (org.zerionproject.core.api.event.Event e : node.events) {
				if (e instanceof org.zerionproject.app.api.channel.event
						.ChannelPostReceivedEvent) {
					org.zerionproject.app.api.channel.event
							.ChannelPostReceivedEvent r =
							(org.zerionproject.app.api.channel.event
									.ChannelPostReceivedEvent) e;
					if (java.util.Arrays.equals(id, r.getChannelId())
							&& r.getSeqNum() == seq) {
						n++;
					}
				}
			}
		}
		return n;
	}

	@Test
	public void onlyTheNewestEditOfThePinnedPostOutlivesTheWindow()
			throws Exception {
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		ChannelTestNode publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		ChannelTestNode subscriber = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
		nodes.add(publisher);
		nodes.add(subscriber);
		int max = ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL;
		int stale = 50;
		long outOfTime = ChannelTestNode.HOUR
				+ ChannelConstants.POST_EDIT_WINDOW_MS
				+ 2L * ChannelTestNode.HOUR;
		List<ChannelPost> history = new ArrayList<>(max);
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < max; seq++) {
			String body = "f";
			long ts = outOfTime;
			if (seq == 0) {
				ts = ChannelTestNode.HOUR;
			} else if (seq == 1 || seq == 2) {
				body = ChannelPostMarkers.edit(channel.channelId, 0L,
						seq == 1 ? "an earlier edit" : CORRECTED);
				ts = ChannelTestNode.HOUR;
			} else if (seq < 3 + stale) {
				body = ChannelPostMarkers.edit(channel.channelId, 0L,
						"too late " + seq);
			}
			ChannelPost p = new ChannelPost(channel.channelId, seq, prev, ts,
					body, Collections.<ChannelPost.ChannelAttachment>
					emptyList(), 0L, new byte[1], true);
			history.add(p);
			prev = subscriber.chain.hashOf(p);
		}
		List<ChannelPost> next = new ArrayList<>();
		for (int i = 0; i < stale + 10; i++) {
			ChannelPost p = publisher.post(channel, max + i, prev, "news " + i,
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			next.add(p);
			prev = subscriber.chain.hashOf(p);
		}
		publisher.seedPublisher(channel, true, null, null, false, next);
		subscriber.seedSubscriber(channel, true, null, null, history);
		pin(publisher, channel.channelId, 0L);

		subscriber.manager.refreshChannel(channel.channelId);

		ChannelPostStore posts = subscriber.store.posts();
		assertTrue(posts.getPost(channel.channelId, 0L) != null);
		assertTrue("the newest edit of the pinned post is kept",
				posts.getPost(channel.channelId, 2L) != null);
		assertNull("an earlier edit gives way",
				posts.getPost(channel.channelId, 1L));
		for (long seq = 3L; seq < 3L + stale; seq++) {
			assertNull("an edit made too late gives way",
					posts.getPost(channel.channelId, seq));
		}
		assertTrue(posts.meta(channel.channelId).count <= max);
		assertTrue(posts.getPost(channel.channelId, max + stale + 9L)
				!= null);
		List<ChannelPost> shown = subscriber.manager.getRecentPosts(
				channel.channelId, 2L * max);
		assertEquals(0L, shown.get(0).getSeqNum());
		assertEquals(CORRECTED, shown.get(0).getBody());
	}

	@Test
	public void anEditOfALegacyPostFromAnEarlierBuildShowsAndErasesNothing()
			throws Exception {
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		ChannelTestNode node = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		nodes.add(node);
		node.seedSubscriber(channel, true, null, null,
				legacyWithEdit(node, channel));

		List<ChannelPost> shown =
				node.manager.getRecentPosts(channel.channelId, 10L);
		node.manager.purgeExpiredPosts();

		assertEquals(Arrays.asList(CORRECTED, "stays"), bodies(shown));
		assertTrue(shown.get(0).isEdited());
		ChannelPost stored = node.store.posts().getPost(channel.channelId,
				0L);
		assertFalse(stored.isErased());
		assertEquals(ORIGINAL, stored.getBody());
		assertEquals(ChannelChainVerifier.Result.OK, node.chain
				.verifyOrdered(node.store.getPosts(channel.channelId)));
		assertEquals(0, purgeRequests(node));
	}

	@Test
	public void deletingALegacyPostRemovesTheEditsMadeForIt()
			throws Exception {
		ChannelTestNode.TestChannel channel =
				new ChannelTestNode.TestChannel(crypto, random);
		ChannelTestNode publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		ChannelTestNode subscriber = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
		nodes.add(publisher);
		nodes.add(subscriber);
		List<ChannelPost> history = legacyWithEdit(publisher, channel);
		publisher.seedPublisher(channel, true, null, null, false, history);
		subscriber.seedSubscriber(channel, true, null, null, history);

		publisher.manager.deletePost(channel.channelId, 0L);
		subscriber.manager.refreshChannel(channel.channelId);

		for (ChannelTestNode n : Arrays.asList(publisher, subscriber)) {
			assertNull(n.store.posts().getPost(channel.channelId, 0L));
			assertNull("the edit made for the post is deleted with it",
					n.store.posts().getPost(channel.channelId, 1L));
			assertFalse(holds(n, ORIGINAL));
			assertFalse(holds(n, CORRECTED));
			assertEquals(Collections.singletonList("stays"), bodies(
					n.manager.getRecentPosts(channel.channelId, 10L)));
		}
	}

	@Test
	public void aWithheldPostIsErasedAndStaysAPlaceholder() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, "by the owner", 0L);
		ChannelTestNode editor = editor(id);
		editor.manager.publishPost(id, "an editor draft", 0L);
		ChannelTestNode reader = join(id);
		editor.manager.editPost(id, 1L, "what the editor meant");
		owner.manager.revokeDelegation(id, owner.store.getChannel(id)
				.getActiveDelegations().get(0).getDelegationSeq());

		reader.manager.refreshChannel(id);

		ChannelPost stored = reader.store.posts().getPost(id, 1L);
		assertTrue(stored.isWithheld());
		assertTrue(stored.isErased());
		assertFalse(holds(reader, "an editor draft"));
		List<ChannelPost> shown = reader.manager.getRecentPosts(id, 10L);
		assertEquals(2, shown.size());
		assertTrue(shown.get(1).isWithheld());
		assertFalse(shown.get(1).isEdited());
		assertEquals("", shown.get(1).getBody());
	}

	@Test
	public void anEditMarkCannotBeDeletedOnItsOwn() throws Exception {
		byte[] id = create(true);
		owner.manager.publishPost(id, ORIGINAL, 0L);
		owner.manager.editPost(id, 0L, CORRECTED);
		try {
			owner.manager.deletePost(id, 1L);
			fail("an edit mark was deleted on its own");
		} catch (DbException expected) {
		}

		ChannelTestNode late = join(id);

		assertEquals(2, owner.store.getPosts(id).size());
		assertEquals(Collections.singletonList(CORRECTED),
				bodies(late.manager.getRecentPosts(id, 10L)));
	}

	private List<ChannelPost> legacyWithEdit(ChannelTestNode n,
			ChannelTestNode.TestChannel channel) throws Exception {
		List<ChannelPost> out = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		String[] bodies = {ORIGINAL, ChannelPostMarkers.edit(
				channel.channelId, 0L, CORRECTED), "stays"};
		for (int seq = 0; seq < bodies.length; seq++) {
			ChannelPost p = n.post(channel, seq, prev, bodies[seq],
					Collections.<ChannelPost.ChannelAttachment>emptyList());
			assertEquals(ChannelPost.FORMAT_LEGACY, p.getFormatVersion());
			out.add(p);
			prev = n.chain.hashOf(p);
		}
		return out;
	}

	private void pin(ChannelTestNode n, byte[] id, long seq)
			throws Exception {
		ChannelState s = n.store.getChannel(id);
		n.store.putChannel(new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(), s.getPublisherMlDsaPubKey(),
				s.getName(), s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(),
				s.getJoinCapability(), s.getCurrentOnion(),
				s.getManifestSeq(), s.weArePublisher(),
				s.getHighestKnownPostSeq(), s.getContentKeyHash(),
				s.getContentKey(), s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(), s.getNextDelegationSeq(),
				s.getOnionPrivateKey(), seq, s.requiresApproval(),
				Collections.<ChannelDelegationCert>emptyList()));
	}

	private byte[] erasedOnTheWay(byte[] reply, boolean falseHash)
			throws Exception {
		BdfDictionary d = read(reply);
		BdfList posts = d.getList("posts");
		BdfDictionary first = (BdfDictionary) posts.get(0);
		String body = first.getString("body");
		byte[] hash = falseHash ? new byte[32]
				: owner.codec.bodyHashV2(first.getRaw("salt"), body);
		first.remove("body");
		first.put("bh", hash);
		return write(d);
	}

	private byte[] strippedOnTheWay(byte[] reply, long seq) throws Exception {
		BdfDictionary d = read(reply);
		for (Object o : d.getList("posts")) {
			BdfDictionary p = (BdfDictionary) o;
			if (p.getLong("seqNum") != seq) continue;
			String body = p.getString("body");
			byte[] hash = owner.codec.bodyHashV2(p.getRaw("salt"), body);
			p.remove("body");
			p.put("bh", hash);
		}
		return write(d);
	}

	private byte[] allStrippedOnTheWay(byte[] reply) throws Exception {
		BdfDictionary d = read(reply);
		for (Object o : d.getList("posts")) {
			BdfDictionary p = (BdfDictionary) o;
			String body = p.getString("body");
			p.put("bh", owner.codec.bodyHashV2(p.getRaw("salt"), body));
			p.remove("body");
		}
		return write(d);
	}

	private byte[] brokenOnTheWay(byte[] reply, long seq) throws Exception {
		BdfDictionary d = read(reply);
		for (Object o : d.getList("posts")) {
			BdfDictionary p = (BdfDictionary) o;
			if (p.getLong("seqNum") != seq) continue;
			byte[] sig = p.getRaw("signature").clone();
			sig[sig.length / 2] ^= 1;
			p.put("signature", sig);
		}
		return write(d);
	}

	private static List<Long> seqs(List<ChannelPost> posts) {
		List<Long> out = new ArrayList<>();
		for (ChannelPost p : posts) out.add(p.getSeqNum());
		return out;
	}

	private byte[] create(boolean publicChannel) throws Exception {
		return owner.manager.createChannel("name", "about", publicChannel)
				.getChannelId();
	}

	private ChannelTestNode join(byte[] id) throws Exception {
		ChannelTestNode n = joinWithoutRefresh(id);
		n.manager.refreshChannel(id);
		return n;
	}

	private ChannelTestNode joinWithoutRefresh(byte[] id) throws Exception {
		return joinThrough(owner.manager.exportInviteLink(id));
	}

	private ChannelTestNode joinThrough(String link) throws Exception {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, tapped(),
				null, ChannelTestNode.identity(
				crypto.generateHybridSignatureKeyPair()));
		nodes.add(n);
		n.manager.joinChannel(n.manager.parseInviteLink(link));
		return n;
	}

	private ChannelTestNode editor(byte[] id) throws Exception {
		ChannelTestNode n = join(id);
		byte[] key = n.manager.getMyChannelPublicKey(id);
		owner.manager.delegatePublisher(id, Arrays.copyOfRange(key, 0, 32),
				Arrays.copyOfRange(key, 32, key.length), 0L);
		n.manager.refreshChannel(id);
		return n;
	}

	private ChannelTransport tapped() {
		return new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] channelId,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				return network.bindServer(channelId, onionPrivateKey,
						handler);
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				try {
					byte[] reply = network.requestFromOnion(onion, request);
					ReplyEdit edit = replyEdit;
					boolean pull = ChannelConstants.WIRE_TYPE_PULL_REQUEST
							.equals(owner.pullCodec.peekType(request));
					return pull && edit != null ? edit.apply(reply) : reply;
				} catch (IOException e) {
					throw e;
				} catch (Exception e) {
					throw new IOException(e);
				}
			}

			@Override
			public boolean isReachable(String onion) {
				return network.isReachable(onion);
			}
		};
	}

	static boolean holds(ChannelTestNode n, String text) {
		byte[] wanted = text.getBytes(StandardCharsets.UTF_8);
		for (String[] row : n.settings.rows()) {
			for (String field : row) {
				if (field.contains(text)) return true;
			}
			try {
				if (contains(Base64.getDecoder().decode(row[2]), wanted)) {
					return true;
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		return false;
	}

	static boolean contains(byte[] data, byte[] wanted) {
		outer:
		for (int i = 0; i + wanted.length <= data.length; i++) {
			for (int j = 0; j < wanted.length; j++) {
				if (data[i + j] != wanted[j]) continue outer;
			}
			return true;
		}
		return false;
	}

	private static int purgeRequests(ChannelTestNode n) {
		int count = 0;
		synchronized (n.events) {
			for (Event e : n.events) {
				if (e instanceof PurgeRequestedEvent) count++;
			}
		}
		return count;
	}

	private static List<String> bodies(List<ChannelPost> posts) {
		List<String> out = new ArrayList<>();
		for (ChannelPost p : posts) out.add(p.getBody());
		return out;
	}

	private static BdfDictionary read(byte[] bytes) throws Exception {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(bytes));
		return r.readDictionary();
	}

	private static byte[] write(BdfDictionary d) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}
}
