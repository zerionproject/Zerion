package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelPostQuotaException;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelRefusedException;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.db.DbException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelMemberPostingTest {

	private static final long HOUR = ChannelTestNode.HOUR;

	private final Random random = new Random(6021);
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode publisher;
	private ChannelTestNode member;
	private ChannelTestNode reader;
	private ChannelTestNode.TestChannel channel;
	private KeyPair memberIdentity;
	private volatile boolean ownerOffline;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		publisher.seedPublisher(channel, true, null, null, false,
				publisher.posts(channel, 2));
		memberIdentity = crypto.generateHybridSignatureKeyPair();
		member = new ChannelTestNode(crypto, clock, switchable(), null,
				ChannelTestNode.identity(memberIdentity));
		member.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());
		reader = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(publisher, channel.channelId));
		reader.seedSubscriber(channel, true, null, null,
				Collections.<ChannelPost>emptyList());
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		member.deleteFiles();
		reader.deleteFiles();
	}

	private volatile boolean downgrade;

	private ChannelTransport downgrading() {
		ChannelTransport served =
				ChannelTestNode.servedBy(publisher, channel.channelId);
		return new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				throw new IOException();
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				BdfDictionary d = dict(request);
				if (downgrade && d.containsKey("v")) {
					d.put("v", (long) ChannelConstants
							.PROTOCOL_VERSION_ERASED_EDITS);
					d.remove("hmac2");
					request = bytes(d);
				}
				return served.requestFromOnion(onion, request);
			}

			@Override
			public boolean isReachable(String onion) {
				return true;
			}
		};
	}

	private ChannelTransport switchable() {
		ChannelTransport served =
				ChannelTestNode.servedBy(publisher, channel.channelId);
		return new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] id,
					@Nullable String onionPrivateKey,
					ChannelRequestHandler handler) throws IOException {
				throw new IOException();
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				if (ownerOffline) throw new IOException("offline");
				return served.requestFromOnion(onion, request);
			}

			@Override
			public boolean isReachable(String onion) {
				return !ownerOffline;
			}
		};
	}

	private byte[] id() {
		return channel.channelId;
	}

	private byte[] memberEd() throws Exception {
		return Arrays.copyOfRange(
				member.manager.getMyChannelPublicKey(id()), 0, 32);
	}

	private void everyoneMayPost() throws Exception {
		publisher.manager.setPostingMode(id(),
				ChannelConstants.POSTING_EVERYONE);
		member.manager.refreshChannel(id());
		member.manager.announceMyself(id(), "Robin");
		publisher.manager.setSubscriberTrusted(id(), memberEd(), true);
	}

	private List<ChannelPost> held() throws Exception {
		return publisher.store.getPosts(id());
	}

	@Test
	public void aTrustedSubscribersPostReachesReadersUnderTheirName()
			throws Exception {
		everyoneMayPost();
		assertTrue(member.manager.canPostAsMember(id()));

		long seq = member.manager.publishPostWithSeq(id(), "Road is open",
				0L);

		assertEquals(2L, seq);
		ChannelPost made = held().get(2);
		assertEquals(ChannelPost.FORMAT_V3, made.getFormatVersion());
		assertFalse(made.signedByDelegate());
		ChannelPost.MemberAuthor author = made.getMemberAuthor();
		assertNotNull(author);
		assertEquals("Robin", author.getName());
		assertArrayEquals(memberEd(), author.getEd25519PubKey());
		assertEquals(ChannelConstants.MEMBER_ROLE_SUBSCRIBER,
				author.getRole());
		reader.manager.refreshChannel(id());
		List<ChannelPost> read = reader.manager.getRecentPosts(id(), 10L);
		assertEquals(3, read.size());
		assertEquals("Road is open", read.get(2).getBody());
		assertNotNull(read.get(2).getMemberAuthor());
		assertEquals("Robin", read.get(2).getMemberAuthor().getName());
		assertTrue(member.manager.getQueuedItems(id()).isEmpty());
	}

	@Test
	public void aSubscriberTheOwnerDoesNotKnowCannotPost() throws Exception {
		publisher.manager.setPostingMode(id(),
				ChannelConstants.POSTING_EVERYONE);
		member.manager.refreshChannel(id());
		try {
			member.manager.publishPostWithSeq(id(), "hello all", 0L);
			fail("an unknown subscriber posted");
		} catch (ChannelRefusedException expected) {
		}
		assertEquals(2, held().size());
	}

	@Test
	public void aBlockedSubscriberCannotPost() throws Exception {
		everyoneMayPost();
		publisher.manager.banSubscriber(id(), memberEd());
		try {
			member.manager.publishPostWithSeq(id(), "hello all", 0L);
			fail("a blocked subscriber posted");
		} catch (ChannelRefusedException expected) {
		}
		assertEquals(2, held().size());
	}

	@Test
	public void turningPostingOffStopsTheNextSubmissionAndKeepsEarlierOnes()
			throws Exception {
		everyoneMayPost();
		member.manager.publishPostWithSeq(id(), "first", 0L);
		publisher.manager.setPostingMode(id(),
				ChannelConstants.POSTING_EDITORS);

		assertEquals(ChannelConstants.POSTING_EVERYONE,
				member.manager.getPostingMode(id()));
		byte[] request = memberRequest(7L, "second", "Robin",
				clock.currentTimeMillis() / HOUR * HOUR);
		assertEquals(ChannelConstants.SUBMIT_STATUS_REFUSED,
				status(publisher.handle(id(), request)));
		assertEquals(3, held().size());

		member.manager.refreshChannel(id());
		assertEquals(ChannelConstants.POSTING_EDITORS,
				member.manager.getPostingMode(id()));
		assertFalse(member.manager.canPostAsMember(id()));
		reader.manager.refreshChannel(id());
		assertEquals("first", reader.manager.getRecentPosts(id(), 10L)
				.get(2).getBody());
	}

	@Test
	public void aRepeatedSubmissionMakesNoSecondPost() throws Exception {
		everyoneMayPost();
		byte[] request = memberRequest(41L, "once", "Robin",
				clock.currentTimeMillis() / HOUR * HOUR);

		byte[] first = publisher.handle(id(), request);
		byte[] again = publisher.handle(id(), request);

		assertEquals(ChannelConstants.SUBMIT_STATUS_OK, status(first));
		assertEquals(ChannelConstants.SUBMIT_STATUS_OK, status(again));
		assertEquals(tip(first), tip(again));
		assertEquals(3, held().size());
	}

	@Test
	public void aSubmissionIsRefusedWhenAnyFieldDiffersFromTheSignedOne()
			throws Exception {
		everyoneMayPost();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		byte[] good = memberRequest(5L, "signed text", "Robin", ts);
		for (String key : new String[] {"body", "name", "id", "ts"}) {
			BdfDictionary d = dict(good);
			Object v = d.get(key);
			d.put(key, v instanceof String ? v + "x" : (Long) v + HOUR);
			assertEquals(key, ChannelConstants.SUBMIT_STATUS_REFUSED,
					status(publisher.handle(id(), bytes(d))));
		}
		assertEquals(2, held().size());
	}

	@Test
	public void anOldOrFutureSubmissionIsAskedToComeAgain()
			throws Exception {
		everyoneMayPost();
		long now = clock.currentTimeMillis() / HOUR * HOUR;
		assertEquals(ChannelConstants.SUBMIT_STATUS_STALE, status(
				publisher.handle(id(), memberRequest(1L, "late", "Robin",
						now - ChannelConstants.MEMBER_POST_MAX_AGE_MS
								- HOUR))));
		assertEquals(ChannelConstants.SUBMIT_STATUS_STALE, status(
				publisher.handle(id(), memberRequest(2L, "early", "Robin",
						now + ChannelConstants.ITEM_MAX_FUTURE_MS + HOUR))));
		assertEquals(2, held().size());
	}

	@Test
	public void aSubmissionShapedLikeAnOwnersMarkIsRefused()
			throws Exception {
		everyoneMayPost();
		String mark = ChannelPostMarkers.deletion(id(), 0L);
		assertEquals(ChannelConstants.SUBMIT_STATUS_REFUSED, status(
				publisher.handle(id(), memberRequest(3L, mark, "Robin",
						clock.currentTimeMillis() / HOUR * HOUR))));
		assertEquals(2, held().size());
	}

	@Test
	public void aNameWithControlCharactersIsRefused() throws Exception {
		everyoneMayPost();
		assertEquals(ChannelConstants.SUBMIT_STATUS_REFUSED, status(
				publisher.handle(id(), memberRequest(3L, "hi",
						"Robin\u202eevil",
						clock.currentTimeMillis() / HOUR * HOUR))));
		assertEquals(2, held().size());
	}

	@Test
	public void aSubscriberMayPostTenTimesAnHourAndFiftyADay()
			throws Exception {
		everyoneMayPost();
		long id = 100L;
		for (int hour = 0; hour < 6; hour++) {
			for (int n = 0; n < ChannelConstants.MAX_MEMBER_POSTS_PER_HOUR;
					n++) {
				String want = hour * 10 + n
						< ChannelConstants.MAX_MEMBER_POSTS_PER_DAY
						? ChannelConstants.SUBMIT_STATUS_OK
						: ChannelConstants.SUBMIT_STATUS_QUOTA;
				assertEquals(want, status(publisher.handle(id(),
						memberRequest(id++, "post " + id, "Robin",
								clock.currentTimeMillis() / HOUR * HOUR))));
			}
			if (hour == 0) {
				assertEquals(ChannelConstants.SUBMIT_STATUS_QUOTA, status(
						publisher.handle(id(), memberRequest(id++, "more",
								"Robin", clock.currentTimeMillis()
										/ HOUR * HOUR))));
			}
			clock.advance(HOUR);
		}
		assertEquals(2 + ChannelConstants.MAX_MEMBER_POSTS_PER_DAY,
				held().size());
	}

	@Test
	public void anOlderReaderGetsTheOwnersPostsAroundASubscribersPost()
			throws Exception {
		everyoneMayPost();
		member.manager.publishPostWithSeq(id(), "from Robin", 0L);
		publisher.manager.publishPost(id(), "from the owner", 0L);

		byte[] reply = publisher.handle(id(), withVersion(
				reader.pullCodec.encodePullRequest(id(), -1L, null, null),
				ChannelConstants.PROTOCOL_VERSION_ERASED_EDITS));

		BdfDictionary d = dict(reply);
		assertEquals(ChannelConstants.PROTOCOL_VERSION_ERASED_EDITS,
				d.getLong("v").intValue());
		assertFalse(d.containsKey("wp"));
		BdfList posts = d.getList("posts");
		assertEquals(3, posts.size());
		for (Object o : posts) {
			assertFalse(((BdfDictionary) o).containsKey("au"));
			assertTrue(((BdfDictionary) o).getLong("pv", 1L)
					< ChannelPost.FORMAT_V3);
		}
		ChannelPullProtocol.ProcessResult r = process(reply);
		assertTrue(r.error, r.ok);
		assertEquals(3, r.acceptedPosts.size());
		assertEquals(3L, r.acceptedPosts.get(2).getSeqNum());
		assertEquals("from the owner", r.acceptedPosts.get(2).getBody());
	}

	@Test
	public void aSubscribersPostInAReplyLabelledOlderIsRefused()
			throws Exception {
		everyoneMayPost();
		member.manager.publishPostWithSeq(id(), "from Robin", 0L);
		BdfDictionary d = dict(currentReply());
		d.put("v", (long) ChannelConstants.PROTOCOL_VERSION_ERASED_EDITS);
		d.remove("wp");
		assertFalse(process(bytes(d)).ok);
	}

	@Test
	public void aCurrentReplyWithoutTheSettingIsRefused() throws Exception {
		BdfDictionary d = dict(currentReply());
		d.remove("wp");
		assertFalse(process(bytes(d)).ok);
	}

	@Test
	public void aCurrentReplyWithAnAlteredSettingIsRefused()
			throws Exception {
		BdfDictionary d = dict(currentReply());
		assertEquals((long) ChannelConstants.POSTING_EDITORS,
				d.getLong("wp").longValue());
		d.put("wp", (long) ChannelConstants.POSTING_EVERYONE);
		assertFalse(process(bytes(d)).ok);
		d.put("wp", 9L);
		assertFalse(process(bytes(d)).ok);
	}

	@Test
	public void anAuthorFieldChangedInTransitIsRefused() throws Exception {
		everyoneMayPost();
		member.manager.publishPostWithSeq(id(), "from Robin", 0L);
		BdfDictionary d = dict(currentReply());
		BdfDictionary post = (BdfDictionary) d.getList("posts").get(2);
		BdfDictionary au = post.getDictionary("au");
		au.put("name", "Owner");
		ChannelPullProtocol.ProcessResult r = process(bytes(d));
		assertTrue(r.ok);
		assertEquals(2, r.acceptedPosts.size());
	}

	@Test
	public void aSubscribersPostCannotDeleteTheOwnersPostOnReaders()
			throws Exception {
		HybridSignaturePrivateKey priv = channel.priv();
		ChannelState s = publisher.store.getChannel(id());
		ChannelPost forged = (ChannelPost) publisher.call("buildPost",
				new Class<?>[] {ChannelState.class, byte[].class,
						String.class, long.class, List.class,
						HybridSignaturePrivateKey.class, byte[].class,
						byte[].class, ChannelPost.MemberAuthor.class},
				s, id(), ChannelPostMarkers.deletion(id(), 0L), 0L,
				Collections.emptyList(), priv, null, null,
				new ChannelPost.MemberAuthor(memberEd(), memberMl(), "Robin",
						ChannelConstants.MEMBER_ROLE_SUBSCRIBER));
		publisher.store.posts().append(id(), forged,
				publisher.chain.hashOf(forged));

		reader.manager.refreshChannel(id());

		List<ChannelPost> read = reader.manager.getRecentPosts(id(), 10L);
		assertEquals("post 0", read.get(0).getBody());
		assertEquals(3, read.size());
	}

	@Test
	public void theOwnerCanDeleteASubscribersPostForEveryone()
			throws Exception {
		everyoneMayPost();
		long seq = member.manager.publishPostWithSeq(id(), "remove me", 0L);
		reader.manager.refreshChannel(id());
		assertEquals(3, reader.manager.getRecentPosts(id(), 10L).size());

		publisher.manager.deletePost(id(), seq);
		reader.manager.refreshChannel(id());

		for (ChannelPost p : reader.manager.getRecentPosts(id(), 10L)) {
			assertFalse("remove me".equals(p.getBody()));
		}
	}

	@Test
	public void aPostWaitsWhileTheOwnerIsAwayAndIsSentLater()
			throws Exception {
		everyoneMayPost();
		ownerOffline = true;
		try {
			member.manager.publishPostWithSeq(id(), "queued", 0L);
			fail("no queue");
		} catch (ChannelRequestQueuedException expected) {
		}
		List<ChannelQueuedItem> q = member.manager.getQueuedItems(id());
		assertEquals(1, q.size());
		assertEquals(ChannelQueuedItem.Kind.MEMBER_POST, q.get(0).getKind());
		assertEquals(2, held().size());

		ownerOffline = false;
		clock.advance(2L * 60L * 1000L);
		member.manager.refreshChannel(id());
		member.call("flushOutboxSafely", new Class<?>[] {byte[].class},
				(Object) id());

		assertEquals(3, held().size());
		assertTrue(member.manager.getQueuedItems(id()).isEmpty());
		assertEquals(0, member.manager.takeRefusedPostNotices(id()));
	}

	@Test
	public void aWaitingPostTheOwnerRefusesIsKeptAsNotSentWithANotice()
			throws Exception {
		everyoneMayPost();
		ownerOffline = true;
		try {
			member.manager.publishPostWithSeq(id(), "queued", 0L);
			fail("no queue");
		} catch (ChannelRequestQueuedException expected) {
		}
		publisher.manager.banSubscriber(id(), memberEd());

		ownerOffline = false;
		clock.advance(2L * 60L * 1000L);
		member.call("flushOutboxSafely", new Class<?>[] {byte[].class},
				(Object) id());

		List<ChannelQueuedItem> q = member.manager.getQueuedItems(id());
		assertEquals(1, q.size());
		assertTrue(q.get(0).isNotSent());
		assertEquals(ChannelQueuedItem.Reason.REFUSED, q.get(0).getReason());
		assertEquals("queued", q.get(0).getText());
		assertEquals(2, held().size());
		assertEquals(1, member.manager.takeRefusedPostNotices(id()));
		assertEquals(0, member.manager.takeRefusedPostNotices(id()));

		publisher.manager.unbanSubscriber(id(), memberEd());
		publisher.manager.setSubscriberTrusted(id(), memberEd(), true);
		member.manager.retryQueuedItem(id(), q.get(0).getId());
		assertEquals(3, held().size());
		assertTrue(member.manager.getQueuedItems(id()).isEmpty());
	}

	@Test
	public void aFloodFromManyTrustedKeysIsCappedForTheChannel()
			throws Exception {
		publisher.manager.setPostingMode(id(),
				ChannelConstants.POSTING_EVERYONE);
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		int ok = 0;
		int quota = 0;
		for (int i = 0; i < 100; i++) {
			KeyPair identity = crypto.generateHybridSignatureKeyPair();
			KeyPair keys = memberKeysOf(identity, id());
			HybridSignaturePublicKey pub =
					(HybridSignaturePublicKey) keys.getPublic();
			publisher.subscriberStore.putSubscriber(id(),
					new ChannelSubscriber("Member " + i,
							pub.getEd25519PublicKey(),
							pub.getMlDsaPublicKey(), ts, false));
			publisher.manager.setSubscriberTrusted(id(),
					pub.getEd25519PublicKey(), true);
			String st = status(publisher.handle(id(), signedRequestBy(
					identity, publisher, id(), 1L, "hello " + i,
					"Member " + i, ts, null, null)));
			if (ChannelConstants.SUBMIT_STATUS_OK.equals(st)) ok++;
			else if (ChannelConstants.SUBMIT_STATUS_QUOTA.equals(st)) quota++;
		}
		assertEquals(ChannelConstants.MAX_MEMBER_POSTS_PER_CHANNEL_PER_HOUR,
				ok);
		assertEquals(100 - ok, quota);
		assertEquals(2 + ok, held().size());
	}

	@Test
	public void subscriberPostsStopAtTheChannelsByteCap() throws Exception {
		everyoneMayPost();
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		assertEquals(ChannelConstants.SUBMIT_STATUS_OK, status(
				publisher.handle(id(), memberRequest(1L, "one", "Robin", ts))));
		long one = publisher.store.posts().meta(id()).memberBytes;
		assertTrue(one > 0L);
		publisher.manager.memberPostBytesCap = one + one / 2;
		assertEquals(ChannelConstants.SUBMIT_STATUS_QUOTA, status(
				publisher.handle(id(), memberRequest(2L, "two", "Robin", ts))));
		publisher.manager.deletePost(id(), 2L);
		assertEquals(0L, publisher.store.posts().meta(id()).memberBytes);
		assertEquals(ChannelConstants.SUBMIT_STATUS_OK, status(
				publisher.handle(id(), memberRequest(3L, "three", "Robin",
						ts))));
	}

	@Test
	public void theOwnersPostsOutliveSubscriberPostsInAFullWindow()
			throws Exception {
		int max = ChannelConstants.MAX_SUBSCRIBER_POSTS_PER_CHANNEL;
		List<ChannelPost> history = new java.util.ArrayList<>(max);
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = 0; seq < max; seq++) {
			ChannelPost p = seq == 5000 ? new ChannelPost(id(), seq, prev,
					HOUR, "f", Collections.<ChannelPost.ChannelAttachment>
					emptyList(), 0L, new byte[1], true, false, new byte[16],
					new ChannelPost.MemberAuthor(new byte[32], new byte[1952],
							"Robin", ChannelConstants.MEMBER_ROLE_SUBSCRIBER))
					: new ChannelPost(id(), seq, prev, HOUR, "f",
					Collections.<ChannelPost.ChannelAttachment>emptyList(),
					0L, new byte[1], true);
			history.add(p);
			prev = reader.chain.hashOf(p);
		}
		ChannelPost next = publisher.post(channel, max, prev, "news",
				Collections.<ChannelPost.ChannelAttachment>emptyList());
		publisher.seedPublisher(channel, true, null, null, false,
				Collections.singletonList(next));
		reader.seedSubscriber(channel, true, null, null, history);

		reader.manager.refreshChannel(id());

		ChannelPostStore.Meta m = reader.store.posts().meta(id());
		assertTrue("the oldest owner post stays", m.holds(0L));
		assertFalse("the subscriber post gave way", m.holds(5000L));
		assertTrue(m.holds((long) max));
		assertEquals(0L, m.memberBytes);
	}

	@Test
	public void theNameShownIsTheOneTheOwnerHoldsForTheKey()
			throws Exception {
		everyoneMayPost();
		assertEquals(ChannelConstants.SUBMIT_STATUS_OK, status(
				publisher.handle(id(), memberRequest(4L, "it is me",
						"The owner", clock.currentTimeMillis()
								/ HOUR * HOUR))));
		ChannelPost made = held().get(2);
		assertEquals("Robin", made.getMemberAuthor().getName());
	}

	@Test
	public void aKeyTheOwnerHoldsNoNameForCannotPost() throws Exception {
		publisher.manager.setPostingMode(id(),
				ChannelConstants.POSTING_EVERYONE);
		member.manager.refreshChannel(id());
		publisher.manager.setSubscriberTrusted(id(), memberEd(), true);
		assertEquals(ChannelConstants.SUBMIT_STATUS_REFUSED, status(
				publisher.handle(id(), memberRequest(4L, "hello", "Robin",
						clock.currentTimeMillis() / HOUR * HOUR))));
		assertEquals(2, held().size());
	}

	@Test
	public void anEditorMustPostAsAnEditor() throws Exception {
		everyoneMayPost();
		byte[] key = member.manager.getMyChannelPublicKey(id());
		publisher.manager.delegatePublisher(id(),
				Arrays.copyOfRange(key, 0, 32),
				Arrays.copyOfRange(key, 32, key.length), 0L);
		assertEquals(ChannelConstants.SUBMIT_STATUS_REFUSED, status(
				publisher.handle(id(), memberRequest(4L, "hello", "Robin",
						clock.currentTimeMillis() / HOUR * HOUR))));
		assertEquals(2, held().size());
	}

	@Test
	public void aPostOverTheHourlyQuotaWaitsForTheNextHour()
			throws Exception {
		everyoneMayPost();
		for (int i = 0; i < ChannelConstants.MAX_MEMBER_POSTS_PER_HOUR; i++) {
			member.manager.publishPostWithSeq(id(), "post " + i, 0L);
		}
		try {
			member.manager.publishPostWithSeq(id(), "one too many", 0L);
			fail("over the quota");
		} catch (ChannelPostQuotaException expected) {
		}
		List<ChannelQueuedItem> q = member.manager.getQueuedItems(id());
		assertEquals(1, q.size());
		assertFalse(q.get(0).isNotSent());
		assertEquals(2 + ChannelConstants.MAX_MEMBER_POSTS_PER_HOUR,
				held().size());

		clock.advance(HOUR);
		member.call("flushOutboxSafely", new Class<?>[] {byte[].class},
				(Object) id());

		assertTrue(member.manager.getQueuedItems(id()).isEmpty());
		assertEquals(3 + ChannelConstants.MAX_MEMBER_POSTS_PER_HOUR,
				held().size());
	}

	@Test
	public void everyPostWhileOverTheQuotaSaysSo() throws Exception {
		everyoneMayPost();
		for (int i = 0; i < ChannelConstants.MAX_MEMBER_POSTS_PER_HOUR; i++) {
			member.manager.publishPostWithSeq(id(), "post " + i, 0L);
		}
		for (String text : new String[] {"eleven", "twelve"}) {
			try {
				member.manager.publishPostWithSeq(id(), text, 0L);
				fail("over the quota");
			} catch (ChannelPostQuotaException expected) {
			}
		}
		assertEquals(2, member.manager.getQueuedItems(id()).size());
		assertEquals(0, member.manager.takeTooManyPostNotices(id()));
	}

	@Test
	public void aWaitingPostTheOwnerLimitsLeavesANotice() throws Exception {
		everyoneMayPost();
		ownerOffline = true;
		try {
			member.manager.publishPostWithSeq(id(), "queued", 0L);
			fail("no queue");
		} catch (ChannelRequestQueuedException expected) {
		}
		long ts = clock.currentTimeMillis() / HOUR * HOUR;
		for (int i = 0; i < ChannelConstants.MAX_MEMBER_POSTS_PER_HOUR; i++) {
			assertEquals(ChannelConstants.SUBMIT_STATUS_OK, status(
					publisher.handle(id(), memberRequest(500L + i, "direct " + i,
							"Robin", ts))));
		}
		ownerOffline = false;
		clock.advance(2L * 60L * 1000L);
		member.call("flushOutboxSafely", new Class<?>[] {byte[].class},
				(Object) id());

		List<ChannelQueuedItem> q = member.manager.getQueuedItems(id());
		assertEquals(1, q.size());
		assertFalse(q.get(0).isNotSent());
		assertEquals(1, member.manager.takeTooManyPostNotices(id()));
		assertEquals(0, member.manager.takeTooManyPostNotices(id()));
	}

	@Test
	public void anAnnouncedNameTellsTheOwnersScreens() throws Exception {
		publisher.events.clear();
		member.manager.announceMyself(id(), "Robin");
		boolean told = false;
		synchronized (publisher.events) {
			for (org.zerionproject.core.api.event.Event e : publisher.events) {
				if (e instanceof org.zerionproject.app.api.channel.event
						.ChannelStateChangedEvent) {
					told = true;
				}
			}
		}
		assertTrue(told);
	}

	@Test
	public void aPostFromAPhoneWithAWrongClockIsKeptNotDropped()
			throws Exception {
		everyoneMayPost();
		ChannelTestNode.MutableClock skewed = new ChannelTestNode.MutableClock();
		skewed.advance(-5L * HOUR);
		ChannelTestNode late = new ChannelTestNode(crypto, skewed,
				ChannelTestNode.servedBy(publisher, id()), null,
				ChannelTestNode.identity(memberIdentity));
		try {
			late.seedSubscriber(channel, true, null, null,
					Collections.<ChannelPost>emptyList());
			late.manager.refreshChannel(id());
			late.subscriberStore.putSubscriber(id(), new ChannelSubscriber(
					"Robin", memberEd(), memberMl(), 0L, false));
			try {
				late.manager.publishPostWithSeq(id(), "from the past", 0L);
				fail("posted with a wrong clock");
			} catch (ChannelRequestQueuedException expected) {
			}
			List<ChannelQueuedItem> q = late.manager.getQueuedItems(id());
			assertEquals(1, q.size());
			assertFalse(q.get(0).isNotSent());
			assertEquals(2, held().size());
		} finally {
			late.deleteFiles();
		}
	}

	@Test
	public void aLowerVersionReplyLaterDoesNotChangeTheSetting()
			throws Exception {
		ChannelTestNode downgraded = new ChannelTestNode(crypto, clock,
				downgrading(), null, ChannelTestNode.identity(memberIdentity));
		try {
			downgraded.seedSubscriber(channel, true, null, null,
					Collections.<ChannelPost>emptyList());
			publisher.manager.setPostingMode(id(),
					ChannelConstants.POSTING_EVERYONE);
			downgraded.manager.refreshChannel(id());
			assertEquals(ChannelConstants.POSTING_EVERYONE,
					downgraded.manager.getPostingMode(id()));

			downgrade = true;
			publisher.manager.publishPost(id(), "still served", 0L);
			downgraded.manager.refreshChannel(id());
			assertEquals("the older reply is still taken", 3,
					downgraded.manager.getRecentPosts(id(), 10L).size());
			assertEquals(ChannelConstants.POSTING_EVERYONE,
					downgraded.manager.getPostingMode(id()));
		} finally {
			downgraded.deleteFiles();
		}
	}

	@Test
	public void anEditorCannotPostWhenOnlyTheOwnerMay() throws Exception {
		byte[] key = member.manager.getMyChannelPublicKey(id());
		publisher.manager.delegatePublisher(id(),
				Arrays.copyOfRange(key, 0, 32),
				Arrays.copyOfRange(key, 32, key.length), 0L);
		publisher.manager.setPostingMode(id(),
				ChannelConstants.POSTING_OWNER_ONLY);
		member.manager.refreshChannel(id());
		try {
			member.manager.publishPostWithSeq(id(), "editor", 0L);
			fail("an editor posted while only the owner may");
		} catch (ChannelRefusedException expected) {
		}
		assertEquals(2, held().size());
	}

	@Test
	public void onlyTheOwnerMayChangeWhoCanPost() throws Exception {
		try {
			member.manager.setPostingMode(id(),
					ChannelConstants.POSTING_EVERYONE);
			fail("a subscriber changed the setting");
		} catch (DbException expected) {
		}
		try {
			publisher.manager.setPostingMode(id(), 7);
			fail("an unknown setting was stored");
		} catch (DbException expected) {
		}
		assertEquals(ChannelConstants.POSTING_EDITORS,
				publisher.manager.getPostingMode(id()));
	}

	@Test
	public void aPrivateChannelSubmissionNeedsTheJoinCapability()
			throws Exception {
		byte[] capability = new byte[32];
		random.nextBytes(capability);
		byte[] kContent = new byte[32];
		random.nextBytes(kContent);
		ChannelTestNode.TestChannel secret =
				new ChannelTestNode.TestChannel(crypto, random);
		ChannelTestNode owner = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		owner.seedPublisher(secret, false, capability, kContent, false,
				owner.posts(secret, 1));
		ChannelTestNode sub = new ChannelTestNode(crypto, clock,
				ChannelTestNode.servedBy(owner, secret.channelId), null,
				ChannelTestNode.identity(memberIdentity));
		sub.seedSubscriber(secret, false, capability, kContent,
				Collections.<ChannelPost>emptyList());
		try {
			owner.manager.setPostingMode(secret.channelId,
					ChannelConstants.POSTING_EVERYONE);
			sub.manager.refreshChannel(secret.channelId);
			sub.manager.announceMyself(secret.channelId, "Robin");
			byte[] key = Arrays.copyOfRange(sub.manager
					.getMyChannelPublicKey(secret.channelId), 0, 32);
			owner.manager.setSubscriberTrusted(secret.channelId, key, true);

			sub.manager.publishPostWithSeq(secret.channelId, "inside", 0L);

			ChannelPost made = owner.store.getPosts(secret.channelId).get(1);
			assertEquals(ChannelPost.FORMAT_V3, made.getFormatVersion());
			assertFalse("the body is sent encrypted",
					made.getBody().contains("inside"));
			assertEquals("inside", owner.manager.getRecentPosts(
					secret.channelId, 10L).get(1).getBody());
			byte[] bare = memberRequestFor(sub, secret, 9L, "no proof");
			assertEquals(0, owner.handle(secret.channelId, bare).length);
			assertEquals(2, owner.store.getPosts(secret.channelId).size());
		} finally {
			owner.deleteFiles();
			sub.deleteFiles();
		}
	}

	private byte[] memberMl() throws Exception {
		byte[] key = member.manager.getMyChannelPublicKey(id());
		return Arrays.copyOfRange(key, 32, key.length);
	}

	private byte[] currentReply() throws Exception {
		return publisher.handle(id(), reader.pullCodec.encodePullRequest(
				id(), -1L, null, null));
	}

	private ChannelPullProtocol.ProcessResult process(byte[] reply)
			throws Exception {
		return protocol(reader).processSubscriberResponse(reply,
				reader.store.getChannel(id()), (ChannelChainTip) null,
				null);
	}

	private ChannelPullProtocol protocol(ChannelTestNode n) {
		return new ChannelPullProtocol(n.codec, n.pullCodec,
				new ChannelHmacChallenge(crypto), n.contentKey,
				new ChannelPostValidator(n.codec, n.signatures, n.chain),
				n.signatures, crypto);
	}

	private byte[] memberRequest(long submissionId, String body, String name,
			long ts) throws Exception {
		return signedRequest(member, channel.channelId, submissionId, body,
				name, ts, null, null);
	}

	private byte[] memberRequestFor(ChannelTestNode n,
			ChannelTestNode.TestChannel c, long submissionId, String body)
			throws Exception {
		return signedRequest(n, c.channelId, submissionId, body, "Robin",
				clock.currentTimeMillis() / HOUR * HOUR, null, null);
	}

	private byte[] signedRequest(ChannelTestNode n, byte[] channelId,
			long submissionId, String body, String name, long ts,
			@Nullable byte[] nonce, @Nullable byte[] hmac) throws Exception {
		return signedRequestBy(memberIdentity, n, channelId, submissionId,
				body, name, ts, nonce, hmac);
	}

	private KeyPair memberKeysOf(KeyPair identityKeys, byte[] channelId) {
		HybridSignaturePrivateKey identity =
				(HybridSignaturePrivateKey) identityKeys.getPrivate();
		return ChannelMemberKeys.derive(crypto,
				identity.getEd25519Component().getEncoded(), channelId);
	}

	private byte[] signedRequestBy(KeyPair identityKeys, ChannelTestNode n,
			byte[] channelId, long submissionId, String body, String name,
			long ts, @Nullable byte[] nonce, @Nullable byte[] hmac)
			throws Exception {
		KeyPair keys = memberKeysOf(identityKeys, channelId);
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) keys.getPublic();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keys.getPrivate();
		byte[] sig = n.signatures.signUserMemberPost(
				n.codec.memberPostSignedInput(channelId, submissionId, body,
						name, ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		return n.pullCodec.encodeMemberPostRequest(channelId, submissionId,
				body, name, ts, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), sig, nonce, hmac);
	}

	private String status(byte[] ack) {
		return publisher.pullCodec.decodeSubmitPostAck(ack);
	}

	private long tip(byte[] ack) {
		return publisher.pullCodec.decodeSubmitPostAckTip(ack);
	}

	private byte[] withVersion(byte[] request, long v) throws IOException {
		BdfDictionary d = dict(request);
		d.put("v", v);
		return bytes(d);
	}

	private static BdfDictionary dict(byte[] data) throws IOException {
		BdfReader r = DaggerChannelCodecTestComponent.create()
				.getBdfReaderFactory()
				.createReader(new ByteArrayInputStream(data));
		return r.readDictionary();
	}

	private static byte[] bytes(BdfDictionary d) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}
}
