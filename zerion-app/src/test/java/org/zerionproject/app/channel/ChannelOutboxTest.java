package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelNoAccessException;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelReactionTooSoonException;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelOutboxTest {

	private static final long HOUR = ChannelTestNode.HOUR;
	private static final long DAY = 24L * HOUR;
	private static final String THUMBS = "👍";
	private static final String HEART = "❤";

	private final Random random = new Random(5021);
	private final List<ChannelTestNode> nodes = new ArrayList<>();
	private final List<String> seen =
			Collections.synchronizedList(new ArrayList<>());
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private FakeOnionNetwork network;
	private ChannelTestNode owner;
	private ChannelTestNode member;
	private byte[] id;
	private long post;
	private String link;
	@Nullable
	private ChannelTransport.ChannelServer ownerServer;
	private volatile boolean dropAnswers;
	private volatile boolean staleSubmits;
	private volatile boolean swallowComments;
	private volatile boolean answerNothing;
	private volatile boolean refuseReactions;
	private volatile boolean forgeConfirmations;
	@Nullable
	private volatile Hook onConfirm;
	@Nullable
	private volatile Hook onComment;
	private final Set<String> loseAnswerOf = ConcurrentHashMap.newKeySet();
	private final Set<String> failBefore = ConcurrentHashMap.newKeySet();

	private interface Action {
		void run() throws DbException;
	}

	private interface Hook {
		void on(byte[] request) throws Exception;
	}

	private final ChannelTransport memberNet = new ChannelTransport() {
		@Override
		public ChannelServer bindServer(byte[] channelId,
				@Nullable String onionPrivateKey,
				ChannelRequestHandler handler) throws IOException {
			return network.bindServer(channelId, onionPrivateKey, handler);
		}

		@Override
		public byte[] requestFromOnion(String onion, byte[] request)
				throws IOException {
			String type = typeOrEmpty(request);
			if (failBefore.remove(type)) throw new IOException("lost");
			byte[] reply = network.requestFromOnion(onion, request);
			if (loseAnswerOf.remove(type)) throw new IOException("lost");
			return reply;
		}

		@Override
		public boolean isReachable(String onion) {
			return network.isReachable(onion);
		}
	};

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		network = new FakeOnionNetwork(random);
		owner = node(network);
		id = owner.manager.createChannel("open", "about", true)
				.getChannelId();
		owner.manager.publishPost(id, "hello", 0L);
		post = owner.manager.getRecentPosts(id, 10L).get(0).getSeqNum();
		ownerOnline();
		link = owner.manager.exportInviteLink(id);
		member = node(memberNet);
		member.manager.joinChannel(member.manager.parseInviteLink(link));
		member.manager.refreshChannel(id);
		seen.clear();
	}

	@After
	public void tearDown() {
		for (ChannelTestNode n : nodes) n.deleteFiles();
	}

	@Test
	public void aSentCommentReportsTheIdItIsStoredUnder() throws Exception {
		long commentId = member.manager.postCommentWithId(id, post, "hi");

		List<ChannelComment> held = owner.manager.getComments(id, post);
		assertEquals(1, held.size());
		assertEquals(commentId, held.get(0).getCommentId());
		assertEquals(commentId,
				member.manager.getComments(id, post).get(0).getCommentId());
	}

	@Test
	public void aQueuedCommentNamesTheItemItWaitsAs() throws Exception {
		ownerOffline();
		try {
			member.manager.postCommentWithId(id, post, "later");
			fail("reported as sent while the owner could not be reached");
		} catch (ChannelRequestQueuedException queued) {
			assertEquals(member.manager.getQueuedItems(id).get(0).getId(),
					queued.getItemId());
		}
	}

	@Test
	public void aCommentTheOwnerTookIsSentEvenIfThisDeviceCannotKeepIt()
			throws Exception {
		onComment = request -> {
			onComment = null;
			member.settings.failWritesTo("zerion-channels-comments");
		};
		try {
			member.manager.postCommentWithId(id, post, "taken");
			fail("a comment the owner took was not reported as sent");
		} catch (org.zerionproject.app.api.channel
				.ChannelSentNotSavedException sent) {
			List<ChannelComment> held = owner.manager.getComments(id, post);
			assertEquals("the owner holds it once", 1, held.size());
			assertEquals(held.get(0).getCommentId(), sent.getItemId());
		}
		assertTrue("nothing is left to send again",
				member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void anOwnersPostReportsItsSequenceNumber() throws Exception {
		long seq = owner.manager.publishPostWithSeq(id, "second", 0L);

		List<ChannelPost> posts = owner.manager.getRecentPosts(id, 10L);
		assertEquals(seq, posts.get(posts.size() - 1).getSeqNum());
		assertEquals("second", posts.get(posts.size() - 1).getBody());
	}

	@Test
	public void anOwnersPostThatIsKeptButNotRecordedIsNotAFailure()
			throws Exception {
		owner.settings.failWritesTo("zerion-channels-state");
		try {
			owner.manager.publishPostWithSeq(id, "second", 0L);
			fail("a stored post was not reported as stored");
		} catch (org.zerionproject.app.api.channel
				.ChannelSentNotSavedException kept) {
			boolean found = false;
			for (ChannelPost p : owner.manager.getRecentPosts(id, 10L)) {
				if (p.getSeqNum() == kept.getItemId()
						&& p.getBody().equals("second")) {
					found = true;
				}
			}
			assertTrue("the post it names is kept", found);
		}
	}

	@Test
	public void aQueuedPostKeepsItsTimer() throws Exception {
		makeMemberAnEditor();
		ownerOffline();
		queued(() -> member.manager.publishPost(id, "timed", 3600L));

		assertEquals(3600L,
				member.manager.getQueuedItems(id).get(0).getTtlSeconds());
	}

	@Test
	public void aDeleteThatWentOutIsFinishedHereToo() throws Exception {
		owner.settings.failWritesTo("zerion-channels-state");
		try {
			owner.manager.deletePost(id, post);
			fail("a published deletion was not reported as published");
		} catch (org.zerionproject.app.api.channel
				.ChannelSentNotSavedException published) {
		}
		for (ChannelPost p : owner.manager.getRecentPosts(id, 10L)) {
			assertFalse("the deleted post is gone here as well",
					p.getSeqNum() == post);
		}
	}

	@Test
	public void anEditThatWentOutErasesTheOldWordingHereAtOnce()
			throws Exception {
		owner.settings.failWritesTo("zerion-channels-state");
		try {
			owner.manager.editPost(id, post, "corrected");
			fail("a published edit was not reported as published");
		} catch (org.zerionproject.app.api.channel
				.ChannelSentNotSavedException published) {
		}
		ChannelPost original = owner.store.posts().getPost(id, post);
		assertTrue("the old wording is gone at once", original.isErased());
		assertEquals("", original.getBody());
		List<String> bodies = new ArrayList<>();
		for (ChannelPost p : owner.manager.getRecentPosts(id, 10L)) {
			bodies.add(p.getBody());
		}
		assertEquals(Collections.singletonList("corrected"), bodies);
	}

	@Test
	public void aCommentOnAClosedDiscussionIsRefused() throws Exception {
		owner.manager.setDiscussionsEnabled(id, false);
		try {
			owner.manager.postCommentWithId(id, post, "closed");
			fail("a comment on a closed discussion was accepted");
		} catch (org.zerionproject.app.api.channel
				.ChannelRefusedException expected) {
		}
	}

	@Test
	public void itemsWaitWhileTheOwnerIsAwayAndArriveOnceInOrder()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "first"));
		queued(() -> member.manager.reactToPost(id, post, THUMBS));
		queued(() -> member.manager.postComment(id, post, "second"));

		List<ChannelQueuedItem> waiting = member.manager.getQueuedItems(id);
		assertEquals(Arrays.asList(ChannelQueuedItem.Kind.COMMENT,
				ChannelQueuedItem.Kind.REACTION,
				ChannelQueuedItem.Kind.COMMENT), kinds(waiting));
		for (ChannelQueuedItem q : waiting) assertFalse(q.isNotSent());
		assertTrue(owner.manager.getComments(id, post).isEmpty());

		clock.advance(47L * HOUR);
		ownerOnline();
		member.manager.refreshChannel(id);

		assertEquals(Arrays.asList(ChannelConstants.WIRE_TYPE_POST_COMMENT,
				ChannelConstants.WIRE_TYPE_POST_REACTION,
				ChannelConstants.WIRE_TYPE_POST_COMMENT), itemsSeen());
		long hour = clock.currentTimeMillis() / HOUR * HOUR;
		List<ChannelComment> comments = owner.manager.getComments(id, post);
		assertEquals(Arrays.asList("first", "second"), bodies(comments));
		for (ChannelComment c : comments) {
			assertEquals("signed when it was sent", hour,
					c.getTimestampHourMs());
		}
		List<ChannelReaction> reactions = owner.manager.getReactions(id,
				post);
		assertEquals(1, reactions.size());
		assertEquals(THUMBS, reactions.get(0).getEmoji());
		assertEquals(hour, reactions.get(0).getTimestampHourMs());
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
		assertEquals(Arrays.asList("first", "second"),
				bodies(member.manager.getComments(id, post)));

		member.manager.refreshChannel(id);
		assertEquals("nothing was sent twice", 3, itemsSeen().size());
		assertEquals(2, owner.manager.getComments(id, post).size());
	}

	@Test
	public void anItemWaitingLongerThanTheItemWindowIsStillTaken()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "three days"));

		clock.advance(3L * DAY);
		ownerOnline();
		member.manager.refreshChannel(id);

		assertEquals(Collections.singletonList("three days"),
				bodies(owner.manager.getComments(id, post)));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void anItemTheOwnerRefusesIsMarkedNotSentAndNotRetried()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "hi"));
		owner.manager.banSubscriber(id, memberKey());
		ownerOnline();

		member.manager.refreshChannel(id);

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(1, held.size());
		assertTrue(held.get(0).isNotSent());
		assertEquals("hi", held.get(0).getText());
		assertEquals(1, count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
		member.manager.refreshChannel(id);
		clock.advance(HOUR);
		member.manager.refreshChannel(id);
		assertEquals("a refused item is not sent again", 1,
				count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
		assertTrue(owner.manager.getComments(id, post).isEmpty());
		assertTrue(member.manager.getQueuedItems(id).get(0).isNotSent());

		owner.manager.unbanSubscriber(id, memberKey());
		member.manager.retryQueuedItem(id, held.get(0).getId());

		assertEquals(Collections.singletonList("hi"),
				bodies(owner.manager.getComments(id, post)));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void aNotSentItemCanBeDeleted() throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "drop me"));
		clock.advance(7L * DAY);
		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertTrue(held.get(0).isNotSent());

		member.manager.deleteQueuedItem(id, held.get(0).getId());

		assertTrue(member.manager.getQueuedItems(id).isEmpty());
		ownerOnline();
		member.manager.refreshChannel(id);
		assertEquals(0, count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
	}

	@Test
	public void anItemWaitingSevenDaysIsShownAsNotSentAndIsNotSent()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "old"));
		clock.advance(2L * DAY);
		queued(() -> member.manager.postComment(id, post, "newer"));
		clock.advance(5L * DAY);

		ownerOnline();
		member.manager.refreshChannel(id);

		assertEquals(Collections.singletonList("newer"),
				bodies(owner.manager.getComments(id, post)));
		assertEquals(1, count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(1, held.size());
		assertEquals("old", held.get(0).getText());
		assertTrue(held.get(0).isNotSent());
	}

	@Test
	public void theOutboxOfAChannelHoldsAtMostFiftyItems() throws Exception {
		ownerOffline();
		for (int i = 0; i < 50; i++) {
			String body = "comment " + i;
			queued(() -> member.manager.postComment(id, post, body));
		}

		try {
			member.manager.postComment(id, post, "one too many");
			fail("a request beyond the limit was reported");
		} catch (ChannelRequestQueuedException e) {
			fail("a request beyond the limit was kept");
		} catch (DbException expected) {
		}

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(50, held.size());
		assertEquals("comment 0", held.get(0).getText());
		assertEquals("comment 49", held.get(49).getText());
	}

	@Test
	public void leavingAChannelClearsItsOutbox() throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "gone"));
		queued(() -> member.manager.reactToPost(id, post, THUMBS));

		member.manager.leaveChannel(id);

		for (String[] row : member.settings.rows()) {
			assertFalse(row[0].startsWith("zerion-channels-outbox"));
		}
		ownerOnline();
		member.manager.joinChannel(member.manager.parseInviteLink(link));
		member.manager.refreshChannel(id);
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
		assertEquals(Collections.emptyList(), itemsSeen());
		assertTrue(owner.manager.getComments(id, post).isEmpty());
		assertTrue(owner.manager.getReactions(id, post).isEmpty());
	}

	@Test
	public void aCommentWhoseAnswerIsLostIsNotSentTwice() throws Exception {
		dropAnswers = true;
		queued(() -> member.manager.postComment(id, post, "once"));
		assertEquals(1, owner.manager.getComments(id, post).size());

		dropAnswers = false;
		member.manager.refreshChannel(id);

		assertEquals(1, count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
		assertEquals(Collections.singletonList("once"),
				bodies(owner.manager.getComments(id, post)));
		assertEquals(Collections.singletonList("once"),
				bodies(member.manager.getComments(id, post)));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void aNewReactionReplacesTheOneWaiting() throws Exception {
		ownerOffline();
		queued(() -> member.manager.reactToPost(id, post, THUMBS));
		queued(() -> member.manager.reactToPost(id, post, HEART));

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(1, held.size());
		assertEquals(HEART, held.get(0).getText());

		ownerOnline();
		member.manager.refreshChannel(id);

		assertEquals(1, count(ChannelConstants.WIRE_TYPE_POST_REACTION));
		List<ChannelReaction> reactions = owner.manager.getReactions(id,
				post);
		assertEquals(1, reactions.size());
		assertEquals(HEART, reactions.get(0).getEmoji());
	}

	@Test
	public void anAnnouncementWaitsForTheOwner() throws Exception {
		ownerOffline();
		queued(() -> member.manager.announceMyself(id, "Member"));
		assertTrue(owner.manager.getAnnouncedSubscribers(id).isEmpty());

		ownerOnline();
		member.manager.refreshChannel(id);

		List<ChannelSubscriber> named =
				owner.manager.getAnnouncedSubscribers(id);
		assertEquals(1, named.size());
		assertEquals("Member", named.get(0).getDisplayName());
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void anEditorsPostWaitsAndIsPublishedOnce() throws Exception {
		byte[] key = member.manager.getMyChannelPublicKey(id);
		owner.manager.delegatePublisher(id, Arrays.copyOfRange(key, 0, 32),
				Arrays.copyOfRange(key, 32, key.length), 0L);
		member.manager.refreshChannel(id);
		ownerOffline();

		queued(() -> member.manager.publishPost(id, "from the editor", 0L));
		assertEquals(Collections.singletonList(ChannelQueuedItem.Kind.POST),
				kinds(member.manager.getQueuedItems(id)));

		clock.advance(HOUR);
		ownerOnline();
		member.manager.refreshChannel(id);
		member.manager.refreshChannel(id);

		assertEquals(1, count(ChannelConstants.WIRE_TYPE_SUBMIT_POST));
		List<String> bodies = new ArrayList<>();
		for (ChannelPost p : owner.manager.getRecentPosts(id, 10L)) {
			bodies.add(p.getBody());
		}
		assertEquals(Arrays.asList("hello", "from the editor"), bodies);
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void aDeleteDuringASendNeverReportsASentItemAsDeleted()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "first"));
		queued(() -> member.manager.postComment(id, post, "second"));
		queued(() -> member.manager.postComment(id, post, "third"));
		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		long first = held.get(0).getId();
		long third = held.get(2).getId();
		Map<Long, ChannelQueuedItem.DeleteResult> results =
				new ConcurrentHashMap<>();
		List<Thread> deleters = new ArrayList<>();
		onComment = request -> {
			onComment = null;
			deleters.add(deleteInBackground(first, results));
			deleters.add(deleteInBackground(third, results));
		};
		ownerOnline();

		member.manager.refreshChannel(id);
		for (Thread t : deleters) t.join(10_000L);

		assertEquals(Arrays.asList("first", "second"),
				bodies(owner.manager.getComments(id, post)));
		assertEquals(2, count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
		assertEquals("the item already on its way is reported as sent",
				ChannelQueuedItem.DeleteResult.SENT, results.get(first));
		assertEquals("the item not yet sent is deleted and never sent",
				ChannelQueuedItem.DeleteResult.DELETED, results.get(third));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void anEditorPostTheOwnerKeepsCallingStaleBacksOffAndIsNotOvertaken()
			throws Exception {
		makeMemberAnEditor();
		ownerOffline();
		queued(() -> member.manager.publishPost(id, "from the editor", 0L));
		queued(() -> member.manager.postComment(id, post, "after it"));
		staleSubmits = true;
		ownerOnline();

		member.manager.refreshChannel(id);
		member.manager.refreshChannel(id);
		clock.advance(30L * 1000L);
		member.manager.refreshChannel(id);

		assertEquals("one round of 3 submits, then it waits", 3,
				count(ChannelConstants.WIRE_TYPE_SUBMIT_POST));
		assertEquals("a later item does not overtake it", 0,
				count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
		assertFalse(member.manager.getQueuedItems(id).get(0).isNotSent());

		for (int i = 0; i < 12 && !member.manager.getQueuedItems(id)
				.get(0).isNotSent(); i++) {
			clock.advance(ChannelOutboxStore.RETRY_MAX_MS);
			member.manager.refreshChannel(id);
		}

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(1, held.size());
		assertEquals(ChannelQueuedItem.Kind.POST, held.get(0).getKind());
		assertTrue(held.get(0).isNotSent());
		assertEquals(ChannelQueuedItem.Reason.TOO_MANY_TRIES,
				held.get(0).getReason());
		assertEquals(3 * ChannelOutboxStore.MAX_TRIES,
				count(ChannelConstants.WIRE_TYPE_SUBMIT_POST));
		assertEquals(Collections.singletonList("after it"),
				bodies(owner.manager.getComments(id, post)));
	}

	@Test
	public void anItemTheOwnerNeverAnswersBacksOffAndIsMarkedNotSent()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "unheard"));
		swallowComments = true;
		ownerOnline();

		member.manager.refreshChannel(id);
		member.manager.refreshChannel(id);
		member.manager.refreshChannel(id);
		assertEquals("not tried again after every pull", 1,
				count(ChannelConstants.WIRE_TYPE_POST_COMMENT));

		for (int i = 0; i < 12 && !member.manager.getQueuedItems(id)
				.get(0).isNotSent(); i++) {
			clock.advance(ChannelOutboxStore.RETRY_MAX_MS);
			member.manager.refreshChannel(id);
		}

		ChannelQueuedItem item = member.manager.getQueuedItems(id).get(0);
		assertTrue(item.isNotSent());
		assertEquals(ChannelQueuedItem.Reason.TOO_MANY_TRIES,
				item.getReason());
		assertEquals(ChannelOutboxStore.MAX_TRIES,
				count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
	}

	@Test
	public void aMemberWhoseProofIsNoLongerTakenIsToldItHasNoAccess()
			throws Exception {
		useChannel(false);
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "early"));
		ownerOnline();
		ChannelState s = owner.store.getChannel(id);
		owner.store.putChannel(withCapability(s, new byte[s
				.getJoinCapability().length]));

		try {
			member.manager.postComment(id, post, "after");
			fail("a comment without access was reported or kept");
		} catch (ChannelNoAccessException expected) {
		} catch (DbException e) {
			fail("the member was not told it has no access");
		}

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(1, held.size());
		assertEquals("early", held.get(0).getText());
		assertTrue(held.get(0).isNotSent());
		assertEquals(ChannelQueuedItem.Reason.NO_ACCESS,
				held.get(0).getReason());
		assertTrue(owner.manager.getComments(id, post).isEmpty());
	}

	@Test
	public void aCommentWhoseAnswerWasLostIsRecognisedWhenItsCopyIsRefused()
			throws Exception {
		dropAnswers = true;
		queued(() -> member.manager.postComment(id, post, "once"));
		dropAnswers = false;
		member.call("pullAndApply", new Class<?>[] {byte[].class,
				boolean.class}, id, false);
		member.commentStore.setComments(id,
				Collections.<ChannelComment>emptyList());
		clock.advance(HOUR);

		member.manager.refreshChannel(id);

		assertTrue("the held copy counts as sent",
				member.manager.getQueuedItems(id).isEmpty());
		assertEquals(Collections.singletonList("once"),
				bodies(owner.manager.getComments(id, post)));
		assertEquals(Collections.singletonList("once"),
				bodies(member.manager.getComments(id, post)));
	}

	@Test
	public void aLostEditorAnswerIsNotResentAndATwinPostStillGoesOut()
			throws Exception {
		makeMemberAnEditor();
		loseAnswerOf.add(ChannelConstants.WIRE_TYPE_SUBMIT_POST);
		queued(() -> member.manager.publishPost(id, "same", 0L));
		ownerOffline();
		queued(() -> member.manager.publishPost(id, "same", 0L));
		ownerOnline();
		failBefore.add(ChannelConstants.WIRE_TYPE_SUBMIT_POST);

		member.manager.refreshChannel(id);
		clock.advance(ChannelOutboxStore.RETRY_MAX_MS);
		member.manager.refreshChannel(id);

		List<String> bodies = new ArrayList<>();
		for (ChannelPost p : owner.manager.getRecentPosts(id, 10L)) {
			bodies.add(p.getBody());
		}
		assertEquals(Arrays.asList("hello", "same", "same"), bodies);
		assertEquals(2, count(ChannelConstants.WIRE_TYPE_SUBMIT_POST));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void aReactionChangeThatIsTooSoonKeepsTheReactionWaiting()
			throws Exception {
		member.manager.reactToPost(id, post, THUMBS);
		member.manager.reactToPost(id, post, HEART);
		member.manager.reactToPost(id, post, THUMBS);
		ChannelOutboxStore outbox = (ChannelOutboxStore) member.call(
				"outbox", new Class<?>[0]);
		outbox.add(id, new ChannelOutboxStore.Entry(7L,
				ChannelQueuedItem.Kind.REACTION, post, HEART, 0L, false, -1L,
				clock.currentTimeMillis(), 0L, false),
				clock.currentTimeMillis());

		try {
			member.manager.reactToPost(id, post, "😂");
			fail("a change signed too far ahead was sent");
		} catch (ChannelReactionTooSoonException expected) {
		}

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(1, held.size());
		assertEquals(HEART, held.get(0).getText());
		assertFalse(held.get(0).isNotSent());
	}

	@Test
	public void aWaitingEditReplacedWhileTheOwnerAnswersIsNeverPublished()
			throws Exception {
		makeMemberAnEditor();
		member.manager.publishPost(id, "original", 0L);
		long mine = postWithBody(owner, "original").getSeqNum();
		ownerOffline();
		queued(() -> member.manager.editPost(id, mine, "A"));
		ownerOnline();
		seen.clear();

		member.manager.editPost(id, mine, "B");

		assertEquals("only the newer edit reaches the owner", 1,
				count(ChannelConstants.WIRE_TYPE_CHANGE_POST));
		assertEquals("B", postWithBody(owner, "B").getBody());
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void aWaitingReactionReplacedWhileTheOwnerAnswersIsNeverSent()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.reactToPost(id, post, THUMBS));
		ownerOnline();

		member.manager.reactToPost(id, post, HEART);

		assertEquals(1, count(ChannelConstants.WIRE_TYPE_POST_REACTION));
		List<ChannelReaction> reactions = owner.manager.getReactions(id,
				post);
		assertEquals(1, reactions.size());
		assertEquals(HEART, reactions.get(0).getEmoji());
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void deletingAnItemThatIsAlreadyGoneIsNotReportedAsSent()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.reactToPost(id, post, THUMBS));
		long replaced = member.manager.getQueuedItems(id).get(0).getId();
		queued(() -> member.manager.reactToPost(id, post, HEART));
		long waiting = member.manager.getQueuedItems(id).get(0).getId();

		assertEquals(ChannelQueuedItem.DeleteResult.GONE,
				member.manager.deleteQueuedItem(id, replaced));
		assertEquals(ChannelQueuedItem.DeleteResult.DELETED,
				member.manager.deleteQueuedItem(id, waiting));
		assertEquals(ChannelQueuedItem.DeleteResult.GONE,
				member.manager.deleteQueuedItem(id, waiting));
	}

	@Test
	public void anEmptyAnswerToACommentTheOwnerKeptIsNotReadAsNoAccess()
			throws Exception {
		useChannel(false);
		dropAnswers = true;

		member.manager.postComment(id, post, "kept");

		dropAnswers = false;
		assertEquals(Collections.singletonList("kept"),
				bodies(owner.manager.getComments(id, post)));
		assertEquals(Collections.singletonList("kept"),
				bodies(member.manager.getComments(id, post)));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void anOwnerThatAnswersNothingIsNotReadAsNoAccess()
			throws Exception {
		useChannel(false);
		answerNothing = true;

		queued(() -> member.manager.postComment(id, post, "later"));

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(1, held.size());
		assertFalse(held.get(0).isNotSent());
	}

	@Test
	public void aClockSetBackDoesNotHoldTheQueue() throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "late"));
		swallowComments = true;
		ownerOnline();
		member.manager.refreshChannel(id);
		assertEquals(1, count(ChannelConstants.WIRE_TYPE_POST_COMMENT));

		clock.advance(-3L * HOUR);
		member.manager.refreshChannel(id);

		assertEquals("a next try hours ahead counts as due", 2,
				count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
	}

	@Test
	public void aRequestBehindAWaitingItemSaysSo() throws Exception {
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "first"));
		swallowComments = true;
		ownerOnline();
		member.manager.refreshChannel(id);

		try {
			member.manager.postComment(id, post, "second");
			fail("a request behind a waiting item was reported as sent");
		} catch (ChannelRequestQueuedException e) {
			assertTrue("the owner answers; it waits behind the earlier item",
					e.isBehindEarlierItems());
		}
		assertEquals(1, count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
	}

	private ChannelPost postWithBody(ChannelTestNode n, String body)
			throws Exception {
		for (ChannelPost p : n.manager.getRecentPosts(id, 50L)) {
			if (p.getBody().equals(body)) return p;
		}
		throw new AssertionError(body);
	}

	@Test
	public void lostAccessCostsOneSendAndMarksEveryWaitingItem()
			throws Exception {
		useChannel(false);
		ownerOffline();
		for (int i = 0; i < 5; i++) {
			String body = "early " + i;
			queued(() -> member.manager.postComment(id, post, body));
		}
		ownerOnline();
		ChannelState s = owner.store.getChannel(id);
		owner.store.putChannel(withCapability(s, new byte[s
				.getJoinCapability().length]));
		seen.clear();

		try {
			member.manager.postComment(id, post, "after");
			fail("a comment without access was reported or kept");
		} catch (ChannelNoAccessException expected) {
		} catch (DbException e) {
			fail("the member was not told it has no access");
		}

		assertEquals("one item tried, the others only marked", 1,
				count(ChannelConstants.WIRE_TYPE_POST_COMMENT));
		assertEquals(1, count(ChannelConstants.WIRE_TYPE_CONFIRM_ONION));
		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(5, held.size());
		for (ChannelQueuedItem q : held) {
			assertTrue(q.isNotSent());
			assertEquals(ChannelQueuedItem.Reason.NO_ACCESS, q.getReason());
		}
	}

	@Test
	public void noAccessNeedsTheOwnersSignedConfirmation() throws Exception {
		useChannel(false);
		ChannelState s = owner.store.getChannel(id);
		owner.store.putChannel(withCapability(s, new byte[s
				.getJoinCapability().length]));
		forgeConfirmations = true;

		queued(() -> member.manager.postComment(id, post, "unsure"));

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(1, held.size());
		assertFalse("an unverified answer is no answer, not lost access",
				held.get(0).isNotSent());
	}

	@Test
	public void aReplacementGoesOutInThePlaceOfTheItemItReplaces()
			throws Exception {
		ownerOffline();
		queued(() -> member.manager.reactToPost(id, post, THUMBS));
		queued(() -> member.manager.postComment(id, post, "after it"));
		ownerOnline();
		refuseReactions = true;

		try {
			member.manager.reactToPost(id, post, HEART);
			fail("a refused reaction was reported as sent or kept");
		} catch (ChannelRequestQueuedException e) {
			fail("a refused reaction was kept");
		} catch (DbException expected) {
		}

		assertEquals("the new reaction went out first, in its place",
				Arrays.asList(ChannelConstants.WIRE_TYPE_POST_REACTION,
						ChannelConstants.WIRE_TYPE_POST_COMMENT),
				itemsSeen());
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
		assertEquals(Collections.singletonList("after it"),
				bodies(owner.manager.getComments(id, post)));
	}

	@Test
	public void aNewNameKeepsThePlaceOfTheNameItReplaces() throws Exception {
		ownerOffline();
		queued(() -> member.manager.announceMyself(id, "A"));
		queued(() -> member.manager.postComment(id, post, "c1"));
		queued(() -> member.manager.announceMyself(id, "B"));

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(Arrays.asList(ChannelQueuedItem.Kind.ANNOUNCE,
				ChannelQueuedItem.Kind.COMMENT), kinds(held));
		assertEquals("B", held.get(0).getText());

		ownerOnline();
		member.manager.refreshChannel(id);

		assertEquals(Arrays.asList(ChannelConstants.WIRE_TYPE_ANNOUNCE,
				ChannelConstants.WIRE_TYPE_POST_COMMENT), itemsSeen());
		List<ChannelComment> comments = owner.manager.getComments(id, post);
		assertEquals(1, comments.size());
		assertEquals("the comment carries the newest name", "B",
				comments.get(0).getAuthorDisplayName());
	}

	@Test
	public void aNewEditKeepsThePlaceOfTheEditItReplaces() throws Exception {
		makeMemberAnEditor();
		member.manager.publishPost(id, "original", 0L);
		long mine = postWithBody(owner, "original").getSeqNum();
		ownerOffline();
		queued(() -> member.manager.editPost(id, mine, "A"));
		queued(() -> member.manager.setPostCommentsEnabled(id, mine, false));
		queued(() -> member.manager.editPost(id, mine, "B"));

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(Arrays.asList(ChannelQueuedItem.Kind.EDIT,
				ChannelQueuedItem.Kind.COMMENTS_SETTING), kinds(held));
		assertEquals("B", held.get(0).getText());

		ownerOnline();
		seen.clear();
		member.manager.refreshChannel(id);

		assertEquals(2, count(ChannelConstants.WIRE_TYPE_CHANGE_POST));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
		assertEquals("B", postWithBody(owner, "B").getBody());
	}

	@Test
	public void aDeleteOfAPostNoLongerHeldStillGoesOut() throws Exception {
		makeMemberAnEditor();
		member.manager.publishPost(id, "mine", 0L);
		long mine = postWithBody(owner, "mine").getSeqNum();
		ownerOffline();
		queued(() -> member.manager.deletePost(id, mine));
		long item = member.manager.getQueuedItems(id).get(0).getId();
		member.store.posts().remove(id, Collections.singletonList(mine),
				true);
		ownerOnline();

		member.manager.refreshChannel(id);

		assertEquals(1, count(ChannelConstants.WIRE_TYPE_CHANGE_POST));
		for (ChannelPost p : owner.manager.getRecentPosts(id, 50L)) {
			assertFalse("the post is gone for everyone",
					p.getBody().equals("mine"));
		}
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
		assertEquals(ChannelQueuedItem.DeleteResult.SENT,
				member.manager.deleteQueuedItem(id, item));
	}

	@Test
	public void aDeleteOfAPostWhoseLifetimeIsOverIsDroppedQuietly()
			throws Exception {
		makeMemberAnEditor();
		member.manager.publishPost(id, "short", 60L * 60L);
		long mine = postWithBody(owner, "short").getSeqNum();
		ownerOffline();
		queued(() -> member.manager.deletePost(id, mine));
		long item = member.manager.getQueuedItems(id).get(0).getId();
		member.store.posts().remove(id, Collections.singletonList(mine),
				true);
		clock.advance(2L * HOUR);
		ownerOnline();

		member.manager.refreshChannel(id);

		assertEquals(0, count(ChannelConstants.WIRE_TYPE_CHANGE_POST));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
		assertEquals(ChannelQueuedItem.DeleteResult.GONE,
				member.manager.deleteQueuedItem(id, item));
	}

	@Test
	public void aRenewedCapabilityPutsLostAccessItemsBackToWaiting()
			throws Exception {
		useChannel(false);
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "early"));
		ownerOnline();
		ChannelState s = owner.store.getChannel(id);
		byte[] renewed = new byte[s.getJoinCapability().length];
		owner.store.putChannel(withCapability(s, renewed));
		try {
			member.manager.postComment(id, post, "after");
			fail("a comment without access was reported or kept");
		} catch (ChannelNoAccessException expected) {
		}
		assertEquals(ChannelQueuedItem.Reason.NO_ACCESS,
				member.manager.getQueuedItems(id).get(0).getReason());

		member.store.putChannel(withCapability(member.store.getChannel(id),
				renewed));

		ChannelQueuedItem again = member.manager.getQueuedItems(id).get(0);
		assertFalse("shown as waiting again", again.isNotSent());
		assertEquals(ChannelQueuedItem.Reason.NONE, again.getReason());
		member.manager.refreshChannel(id);
		assertEquals(Collections.singletonList("early"),
				bodies(owner.manager.getComments(id, post)));
		assertTrue(member.manager.getQueuedItems(id).isEmpty());
	}

	@Test
	public void lostAccessIsStampedWithTheCapabilityThatWasRefused()
			throws Exception {
		useChannel(false);
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "early"));
		ownerOnline();
		ChannelState s = owner.store.getChannel(id);
		byte[] renewed = new byte[s.getJoinCapability().length];
		owner.store.putChannel(withCapability(s, renewed));
		onConfirm = request -> member.store.putChannel(withCapability(
				member.store.getChannel(id), renewed));

		try {
			member.manager.postComment(id, post, "after");
			fail("a comment without access was reported or kept");
		} catch (ChannelNoAccessException expected) {
		}

		ChannelQueuedItem item = member.manager.getQueuedItems(id).get(0);
		assertFalse("the renewed capability brings it back",
				item.isNotSent());
		member.manager.refreshChannel(id);
		assertEquals(Collections.singletonList("early"),
				bodies(owner.manager.getComments(id, post)));
	}

	@Test
	public void aReplacementMarkedForLostAccessWaitsWithTheOthers()
			throws Exception {
		useChannel(false);
		ownerOffline();
		queued(() -> member.manager.postComment(id, post, "early"));
		queued(() -> member.manager.reactToPost(id, post, THUMBS));
		ownerOnline();
		ChannelState s = owner.store.getChannel(id);
		byte[] renewed = new byte[s.getJoinCapability().length];
		owner.store.putChannel(withCapability(s, renewed));

		try {
			member.manager.reactToPost(id, post, HEART);
			fail("a reaction without access was reported or kept");
		} catch (ChannelNoAccessException expected) {
		}

		List<ChannelQueuedItem> held = member.manager.getQueuedItems(id);
		assertEquals(Arrays.asList(ChannelQueuedItem.Kind.COMMENT,
				ChannelQueuedItem.Kind.REACTION), kinds(held));
		assertEquals(HEART, held.get(1).getText());
		for (ChannelQueuedItem q : held) {
			assertEquals(ChannelQueuedItem.Reason.NO_ACCESS, q.getReason());
		}

		member.store.putChannel(withCapability(member.store.getChannel(id),
				renewed));
		member.manager.refreshChannel(id);

		assertEquals(Collections.singletonList("early"),
				bodies(owner.manager.getComments(id, post)));
		List<ChannelReaction> reactions = owner.manager.getReactions(id,
				post);
		assertEquals(1, reactions.size());
		assertEquals(HEART, reactions.get(0).getEmoji());
	}

	@Test
	public void anUnstampedLostAccessItemIsNotTakenAsAChange()
			throws Exception {
		useChannel(false);
		ChannelOutboxStore outbox = (ChannelOutboxStore) member.call(
				"outbox", new Class<?>[0]);
		outbox.add(id, new ChannelOutboxStore.Entry(7L,
				ChannelQueuedItem.Kind.COMMENT, post, "old", 0L, false, -1L,
				clock.currentTimeMillis(), 0L, false),
				clock.currentTimeMillis());
		outbox.markWaitingNotSent(id, clock.currentTimeMillis(),
				ChannelQueuedItem.Reason.NO_ACCESS, "",
				Collections.<Long>emptySet());

		assertTrue("an unknown mark is not a changed capability",
				member.manager.getQueuedItems(id).get(0).isNotSent());

		ChannelState s = member.store.getChannel(id);
		member.store.putChannel(withCapability(s,
				new byte[s.getJoinCapability().length]));
		assertFalse("a later change still brings it back",
				member.manager.getQueuedItems(id).get(0).isNotSent());
	}

	private static byte[] forged(byte[] confirmation) throws Exception {
		BdfDictionary d = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(confirmation))
				.readDictionary();
		byte[] sig = d.getRaw("sig").clone();
		sig[sig.length - 1] ^= 1;
		d.put("sig", sig);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}

	private static ChannelState withCapability(ChannelState s,
			byte[] capability) {
		return new ChannelState(s.getChannelId(), s.getSalt(),
				s.getPublisherEd25519PubKey(), s.getPublisherMlDsaPubKey(),
				s.getName(), s.getDescription(), s.getAvatarHash(),
				s.getCreatedAtHourMs(), s.isPublicChannel(), capability,
				s.getCurrentOnion(), s.getManifestSeq(), s.weArePublisher(),
				s.getHighestKnownPostSeq(), s.getContentKeyHash(),
				s.getContentKey(), s.getActiveDelegations(),
				s.getRevokedDelegationSeqs(), s.getNextDelegationSeq(),
				s.getOnionPrivateKey(), s.getPinnedPostSeq(),
				s.requiresApproval(), s.getRetiredDelegations());
	}

	private Thread deleteInBackground(long item,
			Map<Long, ChannelQueuedItem.DeleteResult> results)
			throws InterruptedException {
		Thread t = new Thread(() -> {
			try {
				results.put(item, member.manager.deleteQueuedItem(id, item));
			} catch (DbException ignored) {
			}
		});
		t.start();
		long until = System.currentTimeMillis() + 5_000L;
		while (t.isAlive() && t.getState() != Thread.State.WAITING
				&& System.currentTimeMillis() < until) {
			Thread.sleep(5L);
		}
		return t;
	}

	private void makeMemberAnEditor() throws Exception {
		byte[] key = member.manager.getMyChannelPublicKey(id);
		owner.manager.delegatePublisher(id, Arrays.copyOfRange(key, 0, 32),
				Arrays.copyOfRange(key, 32, key.length), 0L);
		member.manager.refreshChannel(id);
		seen.clear();
	}

	private void useChannel(boolean publicChannel) throws Exception {
		ownerOffline();
		id = owner.manager.createChannel("other", "about", publicChannel)
				.getChannelId();
		owner.manager.publishPost(id, "hello", 0L);
		post = owner.manager.getRecentPosts(id, 10L).get(0).getSeqNum();
		ownerOnline();
		link = owner.manager.exportInviteLink(id);
		member.manager.joinChannel(member.manager.parseInviteLink(link));
		member.manager.refreshChannel(id);
		seen.clear();
	}

	private ChannelTestNode node(ChannelTransport transport) {
		ChannelTestNode n = new ChannelTestNode(crypto, clock, transport,
				null, ChannelTestNode.identity(
						crypto.generateHybridSignatureKeyPair()));
		nodes.add(n);
		return n;
	}

	private void ownerOnline() throws Exception {
		bindProxy(owner.store.getChannel(id).getOnionPrivateKey());
	}

	private void bindProxy(@Nullable String key) throws Exception {
		ownerServer = network.bindServer(id, key, request -> {
			try {
				String type = type(request);
				seen.add(type);
				if (answerNothing) return new byte[0];
				if (refuseReactions && ChannelConstants.WIRE_TYPE_POST_REACTION
						.equals(type)) {
					return owner.pullCodec.encodeReactionAck(false);
				}
				if (ChannelConstants.WIRE_TYPE_CONFIRM_ONION.equals(type)) {
					Hook hook = onConfirm;
					onConfirm = null;
					if (hook != null) hook.on(request);
				}
				if (forgeConfirmations && ChannelConstants
						.WIRE_TYPE_CONFIRM_ONION.equals(type)) {
					return forged(owner.handle(id, request));
				}
				if (ChannelConstants.WIRE_TYPE_POST_COMMENT.equals(type)) {
					Hook hook = onComment;
					if (hook != null) hook.on(request);
					if (swallowComments) return new byte[0];
				}
				if (staleSubmits && ChannelConstants.WIRE_TYPE_SUBMIT_POST
						.equals(type)) {
					return owner.pullCodec.encodeSubmitPostAck(
							ChannelConstants.SUBMIT_STATUS_STALE, 0L);
				}
				byte[] reply = owner.handle(id, request);
				return dropAnswers && !ChannelConstants
						.WIRE_TYPE_PULL_REQUEST.equals(type)
						? new byte[0] : reply;
			} catch (Exception e) {
				return new byte[0];
			}
		});
	}

	private static String typeOrEmpty(byte[] request) {
		try {
			return type(request);
		} catch (Exception e) {
			return "";
		}
	}

	private void ownerOffline() {
		if (ownerServer != null) ownerServer.close();
		ownerServer = null;
	}

	private static void queued(Action action) {
		try {
			action.run();
			fail("reported as sent while the owner could not be reached");
		} catch (ChannelRequestQueuedException expected) {
		} catch (DbException e) {
			fail("not kept to send later");
		}
	}

	private byte[] memberKey() throws Exception {
		return Arrays.copyOfRange(member.manager.getMyChannelPublicKey(id),
				0, 32);
	}

	private List<String> itemsSeen() {
		List<String> out = new ArrayList<>();
		synchronized (seen) {
			for (String t : seen) {
				if (t.equals(ChannelConstants.WIRE_TYPE_POST_COMMENT)
						|| t.equals(ChannelConstants.WIRE_TYPE_POST_REACTION)
						|| t.equals(ChannelConstants.WIRE_TYPE_ANNOUNCE)
						|| t.equals(ChannelConstants.WIRE_TYPE_SUBMIT_POST)) {
					out.add(t);
				}
			}
		}
		return out;
	}

	private int count(String type) {
		int n = 0;
		synchronized (seen) {
			for (String t : seen) if (t.equals(type)) n++;
		}
		return n;
	}

	private static List<ChannelQueuedItem.Kind> kinds(
			List<ChannelQueuedItem> items) {
		List<ChannelQueuedItem.Kind> out = new ArrayList<>();
		for (ChannelQueuedItem q : items) out.add(q.getKind());
		return out;
	}

	private static List<String> bodies(List<ChannelComment> comments) {
		List<String> out = new ArrayList<>();
		for (ChannelComment c : comments) out.add(c.getBody());
		return out;
	}

	private static String type(byte[] request) throws Exception {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(request));
		return r.readDictionary().getString("type");
	}
}
