package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupReloadRefusalWorkTest {

	private static final long EPOCH = 5L;
	private static final int COUNT_BOUND = 200;
	private static final int FAILING = 20_000;
	private static final int SHOWN_OF_A = 188;
	private static final int SHOWN_OF_B = 11;
	private static final int BODY = 16;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] groupId = getRandomId();
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);
	private final List<MessageId> ids = new ArrayList<>();
	private final List<Integer> senders = new ArrayList<>();
	private long clock = 1_000L;

	@Test
	public void eachRefusedPostCostsOneSmallChoiceNotALookAtEveryPost()
			throws Exception {
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		for (int i = 0; i < SHOWN_OF_B; i++) {
			keep(node.store(b, groupId, bKey, EPOCH, new byte[BODY], clock++,
					0L), 0);
		}
		for (int i = 0; i < FAILING; i++) {
			keep(node.storeWithSig(a, groupId, aKey, EPOCH, new byte[BODY],
					clock++, FORGED_SIG), 1);
		}
		for (int i = 0; i < SHOWN_OF_A; i++) {
			keep(node.store(a, groupId, aKey, EPOCH, new byte[BODY], clock++,
					0L), 1);
		}
		int stored = ids.size();
		int checksBefore = node.signatureChecks();

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(SHOWN_OF_A, countFrom(loaded, aKey));
		assertEquals(SHOWN_OF_B, countFrom(loaded, bKey));
		assertEquals("signatures checked by the load", stored,
				node.signatureChecks() - checksBefore);

		VisitedPosts visited = storedPosts();
		List<GroupTrPost> chosen = take(visited);

		assertEquals(SHOWN_OF_A, countFrom(chosen, aKey));
		assertEquals(SHOWN_OF_B, countFrom(chosen, bKey));
		long allowed = 4L * stored
				+ 2L * (FAILING + 1) * (COUNT_BOUND + 2);
		assertTrue("the choice looked at stored posts " + visited.visits
						+ " times for " + stored + " stored posts of which "
						+ FAILING + " were refused, more than " + allowed,
				visited.visits <= allowed);
	}

	private void keep(MessageId id, int sender) {
		ids.add(id);
		senders.add(sender);
	}

	private VisitedPosts storedPosts() throws Exception {
		Class<?> type = Class.forName(
				GroupTrManagerImpl.class.getName() + "$StoredPost");
		Constructor<?> make = type.getDeclaredConstructor(MessageId.class,
				long.class, long.class, int.class, long.class);
		make.setAccessible(true);
		VisitedPosts posts = new VisitedPosts();
		for (int i = 0; i < ids.size(); i++) {
			posts.posts.add(make.newInstance(ids.get(i), 1_000L + i, (long) i,
					senders.get(i), (long) BODY));
		}
		return posts;
	}

	@SuppressWarnings("unchecked")
	private List<GroupTrPost> take(List<Object> stored) throws Exception {
		Method take = GroupTrManagerImpl.class.getDeclaredMethod(
				"takeStoredPosts", byte[].class, List.class, int.class,
				byte[].class);
		take.setAccessible(true);
		return (List<GroupTrPost>) take.invoke(node.manager, groupId, stored,
				2, localKey);
	}

	private static final class VisitedPosts extends AbstractList<Object> {

		private final List<Object> posts = new ArrayList<>();
		private long visits = 0;

		@Override
		public Object get(int index) {
			visits++;
			return posts.get(index);
		}

		@Override
		public int size() {
			return posts.size();
		}
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
