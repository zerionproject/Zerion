package org.zerionproject.app.grouptr;

import org.junit.Test;
import org.zerionproject.app.api.grouptr.GroupTrMeshSink;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrPostDelivery;
import org.zerionproject.app.api.grouptr.GroupTrSendResult;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.app.api.messaging.event.GroupTrOwnPostStateChangedEvent;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.event.MessagesAckedEvent;
import org.zerionproject.core.api.sync.event.MessagesSentEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupOwnPostDeliveryTest {

	private static final long EPOCH = 4L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] a = key((byte) 3);
	private final byte[] b = key((byte) 4);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	private String send() throws Exception {
		GroupTrSendResult r = node.manager.sendGroupPostReporting(groupId,
				"hello".getBytes(StandardCharsets.UTF_8), 0L);
		return toHexString(r.getPostId());
	}

	private GroupTrPostDelivery state(String postId) throws Exception {
		return node.manager.getOwnPostDeliveries(groupId).get(postId);
	}

	private MessageId copyTo(ContactId c) {
		List<MessageId> copies = node.copiesTo(c);
		assertEquals(1, copies.size());
		return copies.get(0);
	}

	@Test
	public void anOwnPostWaitsThenIsSentThenDeliveredToEveryMember()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L), member(b, 1L));
		ContactId toA = node.addContact(a);
		ContactId toB = node.addContact(b);
		ContactId toCreator = node.addContact(creatorKey);

		String id = send();
		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertEquals(1, shown.size());
		assertEquals("the state is told for the post the screen shows", id,
				toHexString(shown.get(0).getPostId()));
		assertEquals(GroupTrPostDelivery.WAITING, state(id));

		node.markSent(copyTo(toA));
		node.markSent(copyTo(toB));
		assertEquals("one copy has not left yet", GroupTrPostDelivery.WAITING,
				state(id));
		node.markSent(copyTo(toCreator));
		assertEquals(GroupTrPostDelivery.SENT, state(id));

		node.markAcknowledged(copyTo(toA));
		node.markAcknowledged(copyTo(toB));
		assertEquals("one member has not confirmed it",
				GroupTrPostDelivery.SENT, state(id));
		node.markAcknowledged(copyTo(toCreator));
		assertEquals(GroupTrPostDelivery.DELIVERED, state(id));
	}

	@Test
	public void aMemberThePostCannotReachKeepsItFromDelivered()
			throws Exception {
		byte[] stranger = key((byte) 7);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L), member(stranger, 1L));
		ContactId toCreator = node.addContact(creatorKey);
		ContactId toA = node.addContact(a);

		String id = send();
		node.markAcknowledged(copyTo(toA));
		node.markAcknowledged(copyTo(toCreator));

		assertEquals("a member that is no contact never got it",
				GroupTrPostDelivery.SENT, state(id));
	}

	@Test
	public void aMemberWhoJoinedAfterThePostDoesNotHoldItBack()
			throws Exception {
		byte[] later = key((byte) 8);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		ContactId toA = node.addContact(a);
		ContactId toCreator = node.addContact(creatorKey);
		String id = send();
		node.markAcknowledged(copyTo(toA));
		node.markAcknowledged(copyTo(toCreator));
		node.putGroup(groupId, creatorKey, EPOCH + 1, creator(creatorKey),
				member(localKey, 1L), member(a, 1L),
				member(later, EPOCH + 1));
		node.addContact(later);

		assertEquals(GroupTrPostDelivery.DELIVERED, state(id));
	}

	@Test
	public void theStateComesFromWhatIsStoredAndStoresNothing()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		ContactId toA = node.addContact(a);
		ContactId toCreator = node.addContact(creatorKey);
		String id = send();
		node.markAcknowledged(copyTo(toA));
		node.markAcknowledged(copyTo(toCreator));
		MessageId copy = copyTo(toA);
		node.dropFromMemory(groupId);
		node.restart();
		node.untransactedWrites.clear();
		Map<String, Object> before = new HashMap<>(node.metadata(copy));
		int writes = node.writeTxns();

		assertEquals(GroupTrPostDelivery.DELIVERED, state(id));
		assertEquals("no write to work out the state", writes,
				node.writeTxns());
		assertTrue(node.untransactedWrites.isEmpty());
		BdfDictionary after = node.metadata(copy);
		assertEquals(before.keySet(), after.keySet());
		for (Map.Entry<String, Object> e : before.entrySet()) {
			Object v = after.get(e.getKey());
			if (v instanceof byte[]) {
				assertArrayEquals((byte[]) e.getValue(), (byte[]) v);
			} else {
				assertEquals(e.getValue(), v);
			}
		}
	}

	@Test
	public void onlyOwnPostsHaveAState() throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		ContactId fromA = node.addContact(a);
		node.storeAndPost(fromA, groupId, a, EPOCH,
				"from a".getBytes(StandardCharsets.UTF_8), node.now);
		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertEquals(1, shown.size());

		assertNull(state(toHexString(shown.get(0).getPostId())));
		assertTrue(node.manager.getOwnPostDeliveries(groupId).isEmpty());
	}

	@Test
	public void aRemovedGroupHasNoStates() throws Exception {
		assertTrue(node.manager.getOwnPostDeliveries(getRandomId()).isEmpty());
	}

	@Test
	public void aMemberRemovedAfterThePostWhoNeverGotItKeepsItSent()
			throws Exception {
		node.putGroup(groupId, creatorKey, 5L, creator(creatorKey),
				member(localKey, 1L), member(a, 1L), member(b, 1L));
		ContactId toCreator = node.addContact(creatorKey);
		ContactId toA = node.addContact(a);
		ContactId toB = node.addContact(b);
		String id = send();
		node.markSent(copyTo(toB));
		node.putGroup(groupId, creatorKey, 6L, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.markAcknowledged(copyTo(toCreator));
		node.markAcknowledged(copyTo(toA));

		assertEquals("a member removed after the post never confirmed it",
				GroupTrPostDelivery.SENT, state(id));
		node.dropFromMemory(groupId);
		node.restart();
		assertEquals("read back from storage the copy still counts",
				GroupTrPostDelivery.SENT, state(id));

		node.markAcknowledged(copyTo(toB));
		assertEquals(GroupTrPostDelivery.DELIVERED, state(id));
	}

	@Test
	public void aCopyFloodedOverTheMeshStillWaitsToBeSent() throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		ContactId toCreator = node.addContact(creatorKey);
		ContactId toA = node.addContact(a);
		List<Integer> flooded = new ArrayList<>();
		node.manager.setMeshSink(new GroupTrMeshSink() {
			@Override
			public boolean isOfflineMode() {
				return true;
			}

			@Override
			public void floodRecord(int contactId, byte[] record,
					long timestamp) {
				flooded.add(contactId);
			}
		});

		String id = send();
		assertEquals(2, flooded.size());
		assertEquals("the mesh does not say whether anyone got the flood",
				GroupTrPostDelivery.WAITING, state(id));
		node.dropFromMemory(groupId);
		node.restart();
		assertEquals(GroupTrPostDelivery.WAITING, state(id));
		node.markSent(copyTo(toCreator));
		node.markSent(copyTo(toA));
		assertEquals(GroupTrPostDelivery.SENT, state(id));
	}

	@Test
	public void onlyTrafficOfAnOwnCopyTellsTheGroupScreen() throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.addContact(creatorKey);
		ContactId toA = node.addContact(a);
		send();
		node.broadcasts.clear();

		node.manager.eventOccurred(new MessagesAckedEvent(toA,
				Collections.singletonList(new MessageId(getRandomId()))));
		node.manager.eventOccurred(new MessagesSentEvent(toA,
				Collections.singletonList(new MessageId(getRandomId())), 9L));
		assertEquals("traffic that is not an own copy", 0, stateEvents());

		node.manager.eventOccurred(new MessagesSentEvent(toA,
				Collections.singletonList(copyTo(toA)), 9L));
		assertEquals(1, stateEvents());
		node.manager.eventOccurred(new MessagesAckedEvent(toA,
				Collections.singletonList(copyTo(toA))));
		assertEquals(2, stateEvents());

		node.dropFromMemory(groupId);
		node.restart();
		node.broadcasts.clear();
		node.manager.getRecentPosts(groupId);
		node.manager.eventOccurred(new MessagesAckedEvent(toA,
				Collections.singletonList(copyTo(toA))));
		assertEquals("copies read back from storage are known again", 1,
				stateEvents());
	}

	@Test
	public void aCopySentBeforeTheOthersAreStoredStillTellsTheGroupScreen()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		ContactId toCreator = node.addContact(creatorKey);
		ContactId toA = node.addContact(a);
		node.afterNextCopyStored(() -> {
			ContactId holder = node.copiesTo(toCreator).isEmpty() ? toA
					: toCreator;
			node.manager.eventOccurred(new MessagesSentEvent(holder,
					node.copiesTo(holder), 9L));
		});

		send();

		assertEquals("the first copy left while the next was being stored",
				1, stateEvents());
	}

	@Test
	public void workPerLookupIsBoundedByThePostsShown() throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.addContact(creatorKey);
		node.addContact(a);
		int sent = 250;
		for (int i = 0; i < sent; i++) {
			node.manager.sendGroupPostReporting(groupId,
					("post " + i).getBytes(StandardCharsets.UTF_8), 0L);
			node.now += 1000L;
		}
		node.dropFromMemory(groupId);
		node.restart();
		WatchedCopies watched = new WatchedCopies();
		node.useOwnCopies(watched);
		int shown = node.manager.getRecentPosts(groupId).size();
		assertTrue(shown < sent);
		assertEquals("only the copies of posts shown are remembered",
				2 * shown, node.ownCopiesHeld());
		assertTrue("copies of posts the bound drops are never noted: "
						+ watched.peak(), watched.peak() <= 2 * shown);
		assertEquals(2 * shown, watched.peak());
		node.forgetReads();
		int queries = node.queries();
		int statusReads = node.statusReads();
		int readTxns = node.readTxns();
		int hashes = node.hashes();

		Map<String, GroupTrPostDelivery> states =
				node.manager.getOwnPostDeliveries(groupId);

		assertEquals(shown, states.size());
		assertEquals("no stored record is read again", 0,
				node.metadataReads());
		assertEquals("no conversation is searched", queries, node.queries());
		assertEquals("nothing is hashed", hashes, node.hashes());
		assertEquals("one status per copy of a post shown", 2 * shown,
				node.statusReads() - statusReads);
		int chunks = (2 * shown + 63) / 64;
		assertTrue("statuses are read in chunks: "
						+ (node.readTxns() - readTxns),
				node.readTxns() - readTxns <= chunks + 1);
		assertTrue(node.readTxns() - readTxns >= chunks);
	}

	private static final class WatchedCopies extends GroupTrOwnCopies {

		private int peak = 0;
		private Runnable beforeRetain = null;

		synchronized int peak() {
			return peak;
		}

		synchronized void beforeRetain(Runnable hook) {
			beforeRetain = hook;
		}

		@Override
		void add(byte[] groupId, byte[] postId, ContactId contact,
				byte[] memberKey, MessageId id, boolean flooded) {
			super.add(groupId, postId, contact, memberKey, id, flooded);
			try {
				int held = GroupTrTestNode.heldCopies(this);
				synchronized (this) {
					peak = Math.max(peak, held);
				}
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}

		@Override
		void retain(String group, java.util.Set<String> posts) {
			Runnable hook;
			synchronized (this) {
				hook = beforeRetain;
				beforeRetain = null;
			}
			if (hook != null) hook.run();
			super.retain(group, posts);
		}
	}

	private int stateEvents() {
		int n = 0;
		for (Object o : node.broadcasts) {
			if (o instanceof GroupTrOwnPostStateChangedEvent && Arrays.equals(
					((GroupTrOwnPostStateChangedEvent) o).getGroupId(),
					groupId)) {
				n++;
			}
		}
		return n;
	}

	@Test
	public void eachMemberKeyIsHeldOnceWhateverTheNumberOfCopies()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.addContact(creatorKey);
		node.addContact(a);
		int posts = 60;
		for (int i = 0; i < posts; i++) {
			node.manager.sendGroupPostReporting(groupId,
					("post " + i).getBytes(StandardCharsets.UTF_8), 0L);
			node.now += 1000L;
		}
		assertEquals(2 * posts, node.ownCopiesHeld());
		assertEquals("one key string per post, group and member",
				posts + 1 + 2, node.distinctHexStrings());

		node.dropFromMemory(groupId);
		node.restart();
		node.manager.getRecentPosts(groupId);
		assertEquals(2 * posts, node.ownCopiesHeld());
		assertEquals(posts + 1 + 2, node.distinctHexStrings());
	}

	@Test
	public void anEventNamesOnlyThePostsWhoseCopiesItCarries()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.addContact(creatorKey);
		ContactId toA = node.addContact(a);
		List<byte[]> ids = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			ids.add(node.manager.sendGroupPostReporting(groupId,
					("post " + i).getBytes(StandardCharsets.UTF_8), 0L)
					.getPostId());
			node.now += 1000L;
		}
		node.broadcasts.clear();

		node.manager.eventOccurred(new MessagesAckedEvent(toA,
				Collections.singletonList(node.copiesTo(toA).get(7))));

		List<GroupTrOwnPostStateChangedEvent> told = new ArrayList<>();
		for (Object o : node.broadcasts) {
			if (o instanceof GroupTrOwnPostStateChangedEvent) {
				told.add((GroupTrOwnPostStateChangedEvent) o);
			}
		}
		assertEquals(1, told.size());
		assertEquals(1, told.get(0).getPostIds().size());
		assertArrayEquals(ids.get(7), told.get(0).getPostIds().get(0));

		int reads = node.statusReads();
		Map<String, GroupTrPostDelivery> named =
				node.manager.getOwnPostDeliveries(groupId,
						told.get(0).getPostIds());
		assertEquals("only the copies of the named post are read", 2,
				node.statusReads() - reads);
		assertEquals(Collections.singleton(toHexString(ids.get(7))),
				named.keySet());
	}

	@Test
	public void aPostSentWhileTheStatesAreWorkedOutStaysKnown()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.addContact(creatorKey);
		ContactId toA = node.addContact(a);
		node.storeAndPost(toA, groupId, a, EPOCH,
				"from a".getBytes(StandardCharsets.UTF_8), node.now);
		assertEquals(1, node.manager.getRecentPosts(groupId).size());
		CountDownLatch sent = new CountDownLatch(1);
		String[] second = new String[1];
		Thread sender = new Thread(() -> {
			try {
				node.now += 1000L;
				second[0] = toHexString(node.manager.sendGroupPostReporting(
						groupId, "later".getBytes(StandardCharsets.UTF_8),
						0L).getPostId());
			} catch (Exception e) {
				second[0] = null;
			}
			sent.countDown();
		});
		WatchedCopies watched = new WatchedCopies();
		watched.beforeRetain(() -> {
			sender.start();
			try {
				sent.await(1, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		node.useOwnCopies(watched);

		node.manager.getOwnPostDeliveries(groupId);

		assertTrue(sent.await(10, TimeUnit.SECONDS));
		sender.join(10_000L);
		assertNotNull(second[0]);
		List<MessageId> copies = node.copiesTo(toA);
		node.broadcasts.clear();
		node.manager.eventOccurred(new MessagesAckedEvent(toA,
				Collections.singletonList(copies.get(copies.size() - 1))));
		assertEquals("the copies of the post sent meanwhile are still known",
				1, stateEvents());
		assertEquals(GroupTrPostDelivery.WAITING, state(second[0]));
	}

	@Test
	public void theRuleWeighsOnlyMembersThatWereThereAtThePost() {
		String m1 = toHexString(a);
		String m2 = toHexString(b);
		List<GroupTrPostDeliveries.Copy> copies = Arrays.asList(
				new GroupTrPostDeliveries.Copy("p", 3L, m1, true, true),
				new GroupTrPostDeliveries.Copy("q", 3L, m1, true, true),
				new GroupTrPostDeliveries.Copy("q", 3L, m2, true, false),
				new GroupTrPostDeliveries.Copy("r", 3L, m1, false, false));
		Map<String, GroupTrPostDelivery> d = GroupTrPostDeliveries.of(copies,
				Arrays.asList(member(a, 1L), member(b, 4L)));
		assertEquals(GroupTrPostDelivery.DELIVERED, d.get("p"));
		assertEquals(GroupTrPostDelivery.SENT, d.get("q"));
		assertEquals(GroupTrPostDelivery.WAITING, d.get("r"));
		assertFalse(d.containsKey("s"));
		Map<String, GroupTrPostDelivery> missing = GroupTrPostDeliveries.of(
				copies.subList(0, 1),
				Arrays.asList(member(a, 1L), member(b, 2L)));
		assertEquals("a member without a copy was never reached",
				GroupTrPostDelivery.SENT, missing.get("p"));
	}

	private static byte[] key(byte fill) {
		byte[] k = new byte[32];
		Arrays.fill(k, fill);
		return k;
	}
}
