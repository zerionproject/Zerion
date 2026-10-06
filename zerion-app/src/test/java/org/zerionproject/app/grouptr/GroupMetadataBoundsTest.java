package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.bytesFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupMetadataBoundsTest {

	private static final long EPOCH = 5L;
	private static final long FUTURE_EPOCH = EPOCH + 6;
	private static final int MB = 1024 * 1024;

	private static final int CACHE_POSTS_PER_GROUP = 200;
	private static final long CACHE_BYTES_PER_GROUP = 24L * MB;
	private static final long CACHE_BYTES_TOTAL = 64L * MB;
	private static final int BUFFER_POSTS_PER_GROUP = 500;
	private static final int BUFFER_POSTS_PER_SENDER = 125;
	private static final long BUFFER_BYTES_PER_SENDER = 2L * MB;
	private static final long BUFFER_BYTES_PER_GROUP = 8L * MB;
	private static final long BUFFER_BYTES_TOTAL = 16L * MB;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] honestKey = key((byte) 3);
	private final byte[] floodKey = key((byte) 4);
	private long clock = 1_000L;

	@Test
	public void aFloodCannotPushOtherMembersOutOfRecentPosts()
			throws Exception {
		byte[] groupId = getRandomId();
		GroupTrTestNode node = node(groupId);
		ContactId honest = node.addContact(honestKey);
		ContactId flood = node.addContact(floodKey);
		node.manager.getRecentPosts(groupId);

		for (int i = 0; i < 5; i++) post(node, honest, groupId, honestKey, 16);
		for (int i = 0; i < 300; i++) post(node, flood, groupId, floodKey, 16);
		post(node, honest, groupId, honestKey, 16);

		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertEquals("every recent post of the other member still shows",
				6, countFrom(shown, honestKey));
		assertEquals("the flood gave up only its own older posts",
				CACHE_POSTS_PER_GROUP - 6, countFrom(shown, floodKey));
		assertEquals(CACHE_POSTS_PER_GROUP, shown.size());
	}

	@Test
	public void aFloodIsBoundedInBytesInRecentPosts() throws Exception {
		byte[] groupId = getRandomId();
		GroupTrTestNode node = node(groupId);
		ContactId honest = node.addContact(honestKey);
		ContactId flood = node.addContact(floodKey);
		node.manager.getRecentPosts(groupId);
		byte[] big = new byte[MB];

		post(node, honest, groupId, honestKey, 16);
		for (int i = 0; i < 30; i++) {
			node.post(flood, groupId, floodKey, EPOCH, big, clock++);
		}

		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		long bytes = bytesFrom(shown, null);
		assertTrue("the group holds " + bytes + " bytes",
				bytes <= CACHE_BYTES_PER_GROUP);
		assertEquals("the flood gave up only its own older posts",
				(CACHE_BYTES_PER_GROUP - 16) / MB, countFrom(shown, floodKey));
		assertEquals("the other member's post still shows",
				1, countFrom(shown, honestKey));
	}

	@Test
	public void recentPostsOfAllGroupsStayWithinTheTotalBudget()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		List<byte[]> groups = new ArrayList<>();
		for (int g = 0; g < 8; g++) {
			byte[] groupId = getRandomId();
			groups.add(groupId);
			node.putGroup(groupId, creatorKey, EPOCH, members());
		}
		ContactId honest = node.addContact(honestKey);
		for (byte[] groupId : groups) node.manager.getRecentPosts(groupId);
		byte[] big = new byte[MB];

		for (byte[] groupId : groups) {
			for (int i = 0; i < 12; i++) {
				node.storeAndPost(honest, groupId, honestKey, EPOCH, big,
						clock++);
				long held = node.heldBytes();
				assertTrue("all groups together hold " + held + " bytes",
						held <= CACHE_BYTES_TOTAL);
			}
		}

		for (byte[] groupId : groups) {
			assertEquals("a group dropped from memory loads again in full",
					12, node.manager.getRecentPosts(groupId).size());
			long held = node.heldBytes();
			assertTrue("all groups together hold " + held + " bytes",
					held <= CACHE_BYTES_TOTAL);
		}
	}

	@Test
	public void aFloodCannotDisplaceOtherBufferedPosts() throws Exception {
		byte[] groupId = getRandomId();
		GroupTrTestNode node = node(groupId);
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId honest = node.addContact(honestKey);
		ContactId flood = node.addContact(floodKey);
		node.manager.getRecentPosts(groupId);
		byte[] chunk = new byte[64 * 1024];

		for (int i = 0; i < 3; i++) {
			node.post(honest, groupId, honestKey, FUTURE_EPOCH,
					("early " + i).getBytes("UTF-8"), clock++);
		}
		for (int i = 0; i < 600; i++) {
			node.post(flood, groupId, floodKey, FUTURE_EPOCH, chunk, clock++);
		}

		List<GroupTrPost> buffered = node.buffered();
		long bytes = bytesFrom(buffered, null);
		int floodCount = countFrom(buffered, floodKey);
		long floodBytes = bytesFrom(buffered, floodKey);
		assertTrue("the group buffers " + buffered.size() + " posts",
				buffered.size() <= BUFFER_POSTS_PER_GROUP);
		assertTrue("the group buffers " + bytes + " bytes",
				bytes <= BUFFER_BYTES_PER_GROUP);
		assertTrue("the flooding member has " + floodCount + " posts buffered",
				floodCount <= BUFFER_POSTS_PER_SENDER);
		assertTrue("the flooding member has " + floodBytes + " bytes buffered",
				floodBytes <= BUFFER_BYTES_PER_SENDER);
		assertEquals("the other member's buffered posts are kept",
				3, countFrom(buffered, honestKey));

		node.commit(fromCreator, groupId, EPOCH);

		assertEquals("the other member's buffered posts are released",
				3, countFrom(node.manager.getRecentPosts(groupId), honestKey));
	}

	@Test
	public void bufferedPostsOfAllGroupsStayWithinTheTotalBudget()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		List<byte[]> floods = new ArrayList<>();
		for (int s = 0; s < 4; s++) floods.add(key((byte) (10 + s)));
		List<ContactId> senders = new ArrayList<>();
		for (byte[] k : floods) senders.add(node.addContact(k));
		byte[] chunk = new byte[256 * 1024];

		for (int g = 0; g < 5; g++) {
			byte[] groupId = getRandomId();
			List<GroupTrMember> members = new ArrayList<>(
					Arrays.asList(members()));
			for (byte[] k : floods) members.add(member(k, 1L));
			node.putGroup(groupId, creatorKey, EPOCH,
					members.toArray(new GroupTrMember[0]));
			for (int s = 0; s < floods.size(); s++) {
				for (int i = 0; i < 8; i++) {
					node.post(senders.get(s), groupId, floods.get(s),
							FUTURE_EPOCH, chunk, clock++);
				}
			}
		}

		long total = bytesFrom(node.buffered(), null);
		assertTrue("all groups together buffer " + total + " bytes",
				total <= BUFFER_BYTES_TOTAL);
	}

	@Test
	public void nothingBufferedOutlivesTheGroup() throws Exception {
		byte[] groupId = getRandomId();
		GroupTrTestNode node = node(groupId);
		ContactId flood = node.addContact(floodKey);
		for (int i = 0; i < 50; i++) {
			node.post(flood, groupId, floodKey, FUTURE_EPOCH, new byte[1024],
					clock++);
		}
		assertTrue(node.buffered().size() > 0);

		node.manager.removeFromDevice(groupId);

		assertEquals(0, node.buffered().size());
		assertEquals("no buffered post is kept after its group is gone",
				0, node.retainedBufferIdentities());
	}

	@Test
	public void recordsForGroupsNotHeldAddNoLocks() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		ContactId stranger = node.addContact(floodKey);
		int before = node.groupLockCount();

		for (int i = 0; i < 5000; i++) {
			node.added(stranger, getRandomId(), floodKey, 1L);
		}

		assertEquals("locks must not grow with unknown group ids",
				before, node.groupLockCount());
	}

	private GroupTrTestNode node(byte[] groupId) {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, members());
		return node;
	}

	private GroupTrMember[] members() {
		return new GroupTrMember[] {creator(creatorKey), member(localKey, 1L),
				member(honestKey, 2L), member(floodKey, 3L)};
	}

	private void post(GroupTrTestNode node, ContactId from, byte[] groupId,
			byte[] sender, int size) {
		node.post(from, groupId, sender, EPOCH, new byte[size], clock++);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
