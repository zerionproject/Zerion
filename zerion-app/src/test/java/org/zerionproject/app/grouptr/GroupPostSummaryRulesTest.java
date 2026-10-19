package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrPostSummary;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupPostSummaryRulesTest {

	private static final long EPOCH = 3L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] authorKey = key((byte) 3);
	private final byte[] otherKey = key((byte) 5);

	private GroupTrTestNode node;
	private ContactId fromAuthor;
	private ContactId fromOther;

	@Before
	public void setUp() {
		node = new GroupTrTestNode(localKey);
		node.addContact(creatorKey);
		fromAuthor = node.addContact(authorKey);
		fromOther = node.addContact(otherKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 0L), member(authorKey, 0L),
				member(otherKey, 0L));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}

	private static byte[] text(String s) {
		return GroupTrBody.encodeText(s);
	}

	private MessageId receive(ContactId from, byte[] sender, byte[] body,
			long timestamp) {
		return node.receive(from, groupId, sender, EPOCH, body, timestamp, 0L,
				SIG);
	}

	@Test
	public void aRemovedMembersQueuedPostLeavesThePreview() throws Exception {
		receive(fromOther, otherKey, text("still here"), 1_000L);
		node.now += 10L;
		receive(fromAuthor, authorKey, text("about to go"), 2_000L);
		assertEquals("about to go",
				node.manager.getLatestPostSummary(groupId).getText());

		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 0L), member(otherKey, 0L));

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);
		assertNotNull(s);
		assertEquals("a removed member's post is not shown", "still here",
				s.getText());
	}

	@Test
	public void aMemberWhoLeftIsNotPreviewedBeforeTheEpochMoves()
			throws Exception {
		receive(fromOther, otherKey, text("staying"), 1_000L);
		node.now += 10L;
		receive(fromAuthor, authorKey, text("leaving"), 2_000L);
		assertEquals("leaving",
				node.manager.getLatestPostSummary(groupId).getText());

		node.left(fromAuthor, groupId, authorKey, EPOCH + 1);
		assertEquals("the leaving waits for the creator", EPOCH,
				node.group(groupId).getEpoch());
		assertTrue(!node.isMember(groupId, authorKey));

		assertEquals("staying",
				node.manager.getLatestPostSummary(groupId).getText());
	}

	@Test
	public void aNewEpochIsAnsweredFromTheStoreAgain() throws Exception {
		receive(fromAuthor, authorKey, text("hello"), 1_000L);
		node.dropFromMemory(groupId);
		assertEquals("hello",
				node.manager.getLatestPostSummary(groupId).getText());
		int queries = node.queries();

		node.putGroup(groupId, creatorKey, EPOCH + 1, creator(creatorKey),
				member(localKey, 0L), member(authorKey, 0L),
				member(otherKey, 0L));

		assertEquals("hello",
				node.manager.getLatestPostSummary(groupId).getText());
		assertTrue("the store was asked again", node.queries() > queries);
	}

	@Test
	public void aNewPostUpdatesTheRememberedSummaryWithoutAScan()
			throws Exception {
		receive(fromAuthor, authorKey, text("first"), 1_000L);
		node.dropFromMemory(groupId);
		assertEquals("first",
				node.manager.getLatestPostSummary(groupId).getText());
		int queries = node.queries();

		node.now += 10L;
		receive(fromAuthor, authorKey, text("second"), 2_000L);
		node.manager.sendGroupPost(groupId,
				"mine".getBytes(StandardCharsets.UTF_8), 0L);
		int afterPosts = node.queries();

		assertEquals("mine",
				node.manager.getLatestPostSummary(groupId).getText());
		assertEquals("no scan of the store for the new posts", afterPosts,
				node.queries());
		assertTrue(afterPosts >= queries);
	}

	@Test
	public void anOldPostWithoutAStateShowsAfterItsSignatureChecks()
			throws Exception {
		node.store(fromAuthor, groupId, authorKey, EPOCH, text("from before"),
				1_000L, 0L);

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);

		assertNotNull("the conversation shows this post", s);
		assertEquals("from before", s.getText());
	}

	@Test
	public void anOldPostWithABadSignatureIsNotShown() throws Exception {
		receive(fromOther, otherKey, text("good"), 1_000L);
		node.dropFromMemory(groupId);
		node.storeWithSig(fromAuthor, groupId, authorKey, EPOCH,
				text("forged"), 2_000L, FORGED_SIG);

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);

		assertNotNull(s);
		assertEquals("good", s.getText());
	}

	@Test
	public void aLongOldTextIsNotCalledAnAttachment() throws Exception {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 2_000; i++) sb.append("long text ");
		MessageId id = receive(fromAuthor, authorKey, text(sb.toString()),
				1_000L);
		node.metadata(id).remove(GroupTrConstants.MSG_KEY_GROUP_BODY_KIND);
		node.dropFromMemory(groupId);

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);

		assertNotNull(s);
		assertEquals(GroupTrPostSummary.Kind.TEXT, s.getKind());
		assertTrue(s.getText().startsWith("long text"));
		assertNotNull("the kind is kept for next time",
				node.metadata(id).getOptionalLong(
						GroupTrConstants.MSG_KEY_GROUP_BODY_KIND));
	}

	@Test
	public void aBodyOfAnUnknownKindIsAnAttachmentNotRawText()
			throws Exception {
		byte[] unknown = {GroupTrBody.MAGIC_BYTE, GroupTrBody.VERSION_1, 0x42,
				'r', 'a', 'w'};
		MessageId id = receive(fromAuthor, authorKey, unknown, 1_000L);
		assertEquals(0L, (long) node.metadata(id).getOptionalLong(
				GroupTrConstants.MSG_KEY_GROUP_BODY_KIND));

		GroupTrPostSummary cached =
				node.manager.getLatestPostSummary(groupId);
		node.dropFromMemory(groupId);
		GroupTrPostSummary stored =
				node.manager.getLatestPostSummary(groupId);

		assertEquals(GroupTrPostSummary.Kind.MEDIA, cached.getKind());
		assertEquals(GroupTrPostSummary.Kind.MEDIA, stored.getKind());
		assertEquals("", stored.getText());
	}

	@Test
	public void aVanishedBodyFallsBackToTheNextPost() throws Exception {
		receive(fromOther, otherKey, text("older"), 1_000L);
		node.now += 10L;
		MessageId newest = receive(fromAuthor, authorKey, text("gone"),
				2_000L);
		node.dropFromMemory(groupId);
		node.deleteBody(newest);

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);

		assertNotNull(s);
		assertEquals("older", s.getText());
	}

	@Test
	public void aVanishedOnlyBodyIsNotRememberedAsNoPosts() throws Exception {
		MessageId only = receive(fromAuthor, authorKey, text("hello"),
				1_000L);
		node.dropFromMemory(groupId);
		node.deleteBody(only);
		assertNull(node.manager.getLatestPostSummary(groupId));
		int queries = node.queries();

		node.manager.getLatestPostSummary(groupId);

		assertTrue("an unsure answer is not remembered",
				node.queries() > queries);
	}

	@Test
	public void removingAContactForgetsWhatTheyDelivered() throws Exception {
		receive(fromAuthor, authorKey, text("through them"), 1_000L);
		node.dropFromMemory(groupId);
		assertEquals("through them",
				node.manager.getLatestPostSummary(groupId).getText());

		node.removeContact(fromAuthor);

		assertNull("a removed contact's copy is not shown",
				node.manager.getLatestPostSummary(groupId));
	}

	@Test
	public void aContactRemovedWhileASummaryIsWorkedOutIsNotRemembered()
			throws Exception {
		receive(fromAuthor, authorKey, text("through them"), 1_000L);
		node.dropFromMemory(groupId);
		node.afterNextBodyRead(() -> node.removeContact(fromAuthor));

		GroupTrPostSummary during =
				node.manager.getLatestPostSummary(groupId);
		assertNotNull(during);
		assertEquals("through them", during.getText());

		assertNull("the answer worked out across the removal is not kept",
				node.manager.getLatestPostSummary(groupId));
	}

	@Test
	public void addingAContactAsksTheStoreAgain() throws Exception {
		receive(fromAuthor, authorKey, text("hello"), 1_000L);
		node.dropFromMemory(groupId);
		node.manager.getLatestPostSummary(groupId);
		int queries = node.queries();

		ContactId added = node.addContact(key((byte) 7));
		node.manager.eventOccurred(new org.zerionproject.core.api.contact
				.event.ContactAddedEvent(added, true));
		node.manager.getLatestPostSummary(groupId);

		assertTrue(node.queries() > queries);
	}

	@Test
	public void aPostWithoutABodyIsSummarisedWithoutFailing()
			throws Exception {
		Method m = GroupTrManagerImpl.class.getDeclaredMethod("summarise",
				GroupTrPost.class);
		m.setAccessible(true);
		GroupTrPost bodiless = new GroupTrPost(groupId, authorKey, "Name",
				null, 1_000L, EPOCH, false);
		GroupTrPostSummary s = (GroupTrPostSummary) m.invoke(null, bodiless);
		assertNotNull(s);
		assertEquals("", s.getText());
	}

	@Test
	public void anOldPostFromASenderWithoutAKnownKeyIsNotShown()
			throws Exception {
		byte[] strangerKey = key((byte) 9);
		node.withoutMlDsaKey(strangerKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 0L), member(authorKey, 0L),
				member(otherKey, 0L), member(strangerKey, 0L));
		receive(fromOther, otherKey, text("checked"), 1_000L);
		node.dropFromMemory(groupId);
		node.store(fromOther, groupId, strangerKey, EPOCH,
				text("cannot be checked"), 2_000L, 0L);

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);

		assertNotNull(s);
		assertEquals("checked", s.getText());
	}

	@Test
	public void anOldPostRelayedFromAKnownMemberIsShown() throws Exception {
		byte[] relayedKey = key((byte) 9);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 0L), member(authorKey, 0L),
				member(otherKey, 0L), member(relayedKey, 0L));
		node.store(fromOther, groupId, relayedKey, EPOCH, text("relayed"),
				2_000L, 0L);

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);

		assertNotNull(s);
		assertEquals("relayed", s.getText());
	}

	@Test
	public void aPostRemovedWhileItsKindIsKeptStillGivesAnAnswer()
			throws Exception {
		MessageId id = node.store(fromAuthor, groupId, authorKey, EPOCH,
				text("short lived"), 1_000L, 0L);
		node.removeOnFirstRead(id);

		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);

		assertNotNull(s);
		assertEquals("short lived", s.getText());
		assertTrue(!node.isStored(id));
	}

	@Test
	public void theSummaryAndTheStoredPostConversionAgree() throws Exception {
		MessageId id = node.store(fromAuthor, groupId, authorKey, EPOCH,
				text("from before"), 1_000L, 0L);
		assertEquals("from before",
				node.manager.getLatestPostSummary(groupId).getText());

		node.manager.onDatabaseOpened(new Transaction(null, false));
		node.dropFromMemory(groupId);

		assertTrue(node.metadata(id).get("groupCiphertext") == null);
		assertEquals((long) GroupTrBody.TYPE_TEXT, (long) node.metadata(id)
				.getOptionalLong(GroupTrConstants.MSG_KEY_GROUP_BODY_KIND));
		GroupTrPostSummary s = node.manager.getLatestPostSummary(groupId);
		assertNotNull(s);
		assertEquals("from before", s.getText());
	}

	@Test
	public void everySentCopyRecordsItsKind() throws Exception {
		node.manager.sendGroupPost(groupId,
				GroupTrBody.encodeImage(new byte[64], "image/png"), 0L);
		int copies = 0;
		for (MessageId id : node.storedIds()) {
			Long kind = node.metadata(id).getOptionalLong(
					GroupTrConstants.MSG_KEY_GROUP_BODY_KIND);
			assertNotNull(kind);
			assertEquals((long) GroupTrBody.TYPE_IMAGE, (long) kind);
			copies++;
		}
		assertEquals("one copy for each member who is a contact", 3, copies);
	}
}
