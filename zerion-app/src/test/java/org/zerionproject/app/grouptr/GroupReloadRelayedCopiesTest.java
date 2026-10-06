package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupReloadRelayedCopiesTest {

	private static final long EPOCH = 5L;
	private static final int OF_V = 100;
	private static final int OF_W = 150;
	private static final int SHOWN = 100;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] vKey = key((byte) 3);
	private final byte[] wKey = key((byte) 4);
	private final byte[] rKey = key((byte) 5);

	@Test
	public void postsDeliveredAgainShowOnceAfterALoad() throws Exception {
		relayedCopiesShowOnce(1_000L, 10_000L);
	}

	@Test
	public void newerPostsDeliveredAgainShowOnceAfterALoad()
			throws Exception {
		relayedCopiesShowOnce(10_000L, 1_000L);
	}

	@Test
	public void aCopyThatDoesNotVerifyDoesNotHideThePost() throws Exception {
		byte[] groupId = getRandomId();
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		ContactId r = node.addContact(rKey);
		ContactId v = node.addContact(vKey);
		ContactId w = node.addContact(wKey);
		open(node, groupId);
		for (int i = 0; i < OF_W; i++) {
			node.storeAndPost(w, groupId, wKey, EPOCH, body(1, i), 1_000L + i);
		}
		for (int i = 0; i < OF_V; i++) {
			node.storeWithSig(r, groupId, vKey, EPOCH, body(2, i),
					10_000L + i, FORGED_SIG);
			node.storeAndPost(v, groupId, vKey, EPOCH, body(2, i),
					10_000L + i);
		}
		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals(SHOWN, distinctFrom(live, vKey));

		node.dropFromMemory(groupId);
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("posts shown after a load whose copies do not verify",
				SHOWN, distinctFrom(loaded, vKey));
		assertEquals(SHOWN, countFrom(loaded, vKey));
		assertEquals(SHOWN, countFrom(loaded, wKey));
		assertEquals(shown(live), shown(loaded));
	}

	private void relayedCopiesShowOnce(long vFrom, long wFrom)
			throws Exception {
		byte[] groupId = getRandomId();
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		ContactId v = node.addContact(vKey);
		ContactId w = node.addContact(wKey);
		ContactId r = node.addContact(rKey);
		open(node, groupId);
		for (int i = 0; i < OF_W; i++) {
			node.storeAndPost(w, groupId, wKey, EPOCH, body(1, i), wFrom + i);
		}
		for (int i = 0; i < OF_V; i++) {
			node.storeAndPost(v, groupId, vKey, EPOCH, body(2, i), vFrom + i);
			node.storeAndPost(r, groupId, vKey, EPOCH, body(2, i), vFrom + i);
		}
		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals(SHOWN, countFrom(live, vKey));
		assertEquals(SHOWN, distinctFrom(live, vKey));
		assertEquals(SHOWN, countFrom(live, wKey));

		node.dropFromMemory(groupId);
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("distinct posts of the relayed member shown after a"
				+ " load, of " + SHOWN + " shown live",
				SHOWN, distinctFrom(loaded, vKey));
		assertEquals("posts of the relayed member shown after a load",
				SHOWN, countFrom(loaded, vKey));
		assertEquals("posts of the other member shown after a load",
				SHOWN, countFrom(loaded, wKey));
		assertEquals(shown(live), shown(loaded));
	}

	private void open(GroupTrTestNode node, byte[] groupId) {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(vKey, 1L), member(wKey, 1L),
				member(rKey, 1L));
		node.manager.getRecentPosts(groupId);
	}

	private static byte[] body(int tag, int i) {
		byte[] b = new byte[16];
		b[0] = (byte) tag;
		b[1] = (byte) (i >> 8);
		b[2] = (byte) i;
		return b;
	}

	private static int distinctFrom(List<GroupTrPost> posts, byte[] sender) {
		Set<Long> timestamps = new HashSet<>();
		for (GroupTrPost p : posts) {
			if (Arrays.equals(p.getSenderPubKey(), sender)) {
				timestamps.add(p.getTimestamp());
			}
		}
		return timestamps.size();
	}

	private static List<String> shown(List<GroupTrPost> posts) {
		List<String> out = new ArrayList<>();
		for (GroupTrPost p : posts) {
			out.add(p.getTimestamp() + "/" + toHexString(p.getSenderPubKey())
					+ "/" + toHexString(p.getBody()));
		}
		Collections.sort(out);
		return out;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
