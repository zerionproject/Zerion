package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrPostSummary;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupPostSummaryTest {

	private static final long EPOCH = 3L;
	private static final int MB = 1024 * 1024;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] authorKey = key((byte) 3);
	private final byte[] strangerKey = key((byte) 4);

	private GroupTrTestNode node;
	private ContactId fromAuthor;

	@Before
	public void setUp() {
		node = new GroupTrTestNode(localKey);
		node.addContact(creatorKey);
		fromAuthor = node.addContact(authorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 0L), member(authorKey, 0L));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}

	private MessageId receive(byte[] body, long timestamp, long timer) {
		return node.receive(fromAuthor, groupId, authorKey, EPOCH, body,
				timestamp, timer, SIG);
	}

	private static byte[] text(String s) {
		return GroupTrBody.encodeText(s);
	}

	private GroupTrPostSummary freshSummary() throws Exception {
		node.dropFromMemory(groupId);
		node.forgetReads();
		return node.manager.getLatestPostSummary(groupId);
	}

	@Test
	public void theNewestPostIsSummarisedWithoutLoadingTheHistory()
			throws Exception {
		receive(GroupTrBody.encodeImage(new byte[MB], "image/jpeg"), 1_000L,
				0L);
		node.now += 10L;
		receive(text("Two more boxes arrived"), 2_000L, 0L);

		GroupTrPostSummary s = freshSummary();

		assertNotNull(s);
		assertEquals(GroupTrPostSummary.Kind.TEXT, s.getKind());
		assertEquals("Two more boxes arrived", s.getText());
		assertArrayEquals(authorKey, s.getSenderPubKey());
		assertEquals("the name the post was sent under", "Name",
				s.getSenderName());
		assertEquals(2_000L, s.getTimestamp());
		assertTrue("read " + node.largestBodyRead() + " bytes of bodies",
				node.largestBodyRead() < 1024);
		assertTrue("no history was loaded", node.cached(groupId).isEmpty());
	}

	@Test
	public void mediaPostsAreNamedWithoutReadingTheirBodies()
			throws Exception {
		byte[][] bodies = {
				GroupTrBody.encodeImage(new byte[MB], "image/jpeg"),
				GroupTrBody.encodeVideo(new byte[MB], "video/mp4", 3_000L),
				GroupTrBody.encodeVoice(new byte[MB], 3_000L)};
		GroupTrPostSummary.Kind[] kinds = {GroupTrPostSummary.Kind.IMAGE,
				GroupTrPostSummary.Kind.VIDEO, GroupTrPostSummary.Kind.VOICE};
		for (int i = 0; i < bodies.length; i++) {
			node.now += 10L;
			MessageId id = receive(bodies[i], 1_000L + i, 0L);
			assertNotNull("the kind is kept with the post",
					node.metadata(id).getOptionalLong(
							GroupTrConstants.MSG_KEY_GROUP_BODY_KIND));

			GroupTrPostSummary s = freshSummary();

			assertNotNull(s);
			assertEquals(kinds[i], s.getKind());
			assertEquals("", s.getText());
			assertEquals("no media body was read", 0L,
					node.largestBodyRead());
		}
	}

	@Test
	public void aGroupWithoutPostsIsRemembered() throws Exception {
		assertNull(freshSummary());
		int queries = node.queries();

		assertNull(node.manager.getLatestPostSummary(groupId));
		assertEquals("the empty answer is remembered", queries,
				node.queries());

		receive(text("hello"), 1_000L, 0L);
		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);
		assertNotNull("a new post replaces the remembered answer", s);
		assertEquals("hello", s.getText());
	}

	@Test
	public void aSummaryFromLoadedPostsNeedsNoStoreQuery() throws Exception {
		receive(text("hello"), 1_000L, 0L);
		node.dropFromMemory(groupId);
		node.manager.getRecentPosts(groupId);
		int queries = node.queries();

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);

		assertNotNull(s);
		assertEquals("hello", s.getText());
		assertEquals(queries, node.queries());
	}

	@Test
	public void anExpiredPostIsNotSummarised() throws Exception {
		receive(text("stays"), 1_000L, 0L);
		node.now += 10L;
		receive(text("goes"), 2_000L, 60_000L);
		GroupTrPostSummary s = freshSummary();
		assertNotNull(s);
		assertEquals("goes", s.getText());
		assertTrue(s.getExpiryTime() != Long.MAX_VALUE);

		node.now = s.getExpiryTime() + 1L;

		GroupTrPostSummary later = node.manager.getLatestPostSummary(groupId);
		assertNotNull(later);
		assertEquals("stays", later.getText());
	}

	@Test
	public void aPostDeletedForMeIsNoLongerSummarised() throws Exception {
		receive(text("older"), 1_000L, 0L);
		node.now += 10L;
		receive(text("newer"), 2_000L, 0L);
		assertEquals("newer", freshSummary().getText());
		byte[] newest = null;
		for (GroupTrPost p : node.manager.getRecentPosts(groupId)) {
			if (p.getTimestamp() == 2_000L) newest = p.getPostId();
		}
		assertNotNull(newest);
		node.dropFromMemory(groupId);
		assertEquals("newer",
				node.manager.getLatestPostSummary(groupId).getText());

		node.manager.deletePostsForMe(groupId,
				Collections.singletonList(newest));

		assertEquals("the remembered summary is dropped", "older",
				node.manager.getLatestPostSummary(groupId).getText());
		assertEquals("older", freshSummary().getText());
	}

	@Test
	public void anIncognitoPostKeepsItsPostingName() throws Exception {
		node.receiveNamed(fromAuthor, groupId, authorKey, "Amber Falcon 27",
				EPOCH, text("Thanks"), 1_000L);

		GroupTrPostSummary loaded = node.manager.getLatestPostSummary(groupId);
		GroupTrPostSummary stored = freshSummary();

		assertNotNull(loaded);
		assertNotNull(stored);
		assertEquals("Amber Falcon 27", loaded.getSenderName());
		assertEquals("Amber Falcon 27", stored.getSenderName());
		assertArrayEquals(authorKey, stored.getSenderPubKey());
	}

	@Test
	public void aDissolvedGroupShowsNoMembersPosts() throws Exception {
		receive(text("hello"), 1_000L, 0L);
		node.putSetting(GroupTrConstants.SETTINGS_NS_PREFIX
				+ toHexString(groupId), GroupTrConstants.S_DISSOLVED, "true");

		assertNull(freshSummary());
	}

	@Test
	public void postsOfStrangersAreNotSummarised() throws Exception {
		ContactId stranger = node.addContact(strangerKey);
		receive(text("from a member"), 1_000L, 0L);
		node.now += 10L;
		MessageId fake = node.storeCurrent(stranger, groupId, strangerKey,
				EPOCH, text("from a stranger"), 2_000L, 0L, SIG);
		node.metadata(fake).put("groupPostState", 1L);

		GroupTrPostSummary s = freshSummary();

		assertNotNull(s);
		assertEquals("from a member", s.getText());
	}

	@Test
	public void ownPostsAreSummarised() throws Exception {
		receive(text("theirs"), 1_000L, 0L);
		node.now += 10L;
		node.manager.sendGroupPost(groupId,
				"mine".getBytes(StandardCharsets.UTF_8), 0L);

		GroupTrPostSummary s = freshSummary();

		assertNotNull(s);
		assertEquals("mine", s.getText());
		assertArrayEquals(localKey, s.getSenderPubKey());
		List<GroupTrPost> none = node.cached(groupId);
		assertTrue(none.isEmpty());
	}
}
