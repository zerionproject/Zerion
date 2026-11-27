package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrSendResult;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupKeysKit.join;
import static org.zerionproject.app.grouptr.GroupKeysKit.key;
import static org.zerionproject.app.grouptr.GroupKeysKit.mlOf;
import static org.zerionproject.app.grouptr.GroupKeysKit.post;
import static org.zerionproject.app.grouptr.GroupKeysKit.remotePostsAccepted;
import static org.zerionproject.app.grouptr.GroupKeysKit.sentOfType;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupPostRelayNetTest {

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node peter = net.node("Peter", 1);
	private final InviteTestNet.Node vince = net.node("Vince", 2);
	private final InviteTestNet.Node dirk = net.node("Dirk", 3);
	private final InviteTestNet.Node dave = net.node("Dave", 4);
	private byte[] groupId;

	@Before
	public void setUp() throws Exception {
		net.connect(peter, vince);
		net.connect(peter, dirk);
		net.connect(peter, dave);
		net.connect(vince, dave);
		net.connect(dirk, dave);
		groupId = peter.manager.createGroup("Family").getGroupId();
		join(net, peter, groupId, vince);
		join(net, peter, groupId, dirk);
		join(net, peter, groupId, dave);
		assertFalse(vince.hasContact(dirk));
	}

	@Test
	public void membersWhoAreNotContactsReachEachOtherThroughTheOwner()
			throws Exception {
		vince.manager.sendGroupPost(groupId, new byte[] {1}, 0L);
		net.pump();

		assertEquals(1, remotePostsAccepted(peter, groupId));
		assertEquals(1, remotePostsAccepted(dave, groupId));
		assertEquals("Dirk takes Vince's post from Peter", 1,
				remotePostsAccepted(dirk, groupId));
		List<InviteTestNet.Packet> requests = sentOfType(vince,
				MessageTypes.GROUP_RELAY_REQUEST);
		assertEquals(1, requests.size());
		assertEquals(peter, requests.get(0).to);
		BdfList listed = requests.get(0).body.getList(2);
		assertEquals(1, listed.size());
		assertArrayEquals(key(dirk, groupId), listed.getRaw(0));
		List<InviteTestNet.Packet> copies = sentOfType(peter,
				MessageTypes.GROUP_POST);
		assertEquals(1, copies.size());
		assertEquals(dirk, copies.get(0).to);
		assertEquals(requests.get(0).timestamp, copies.get(0).timestamp);
		List<InviteTestNet.Packet> keys = sentOfType(peter,
				MessageTypes.GROUP_MEMBER_KEYS);
		assertEquals(1, keys.size());
		assertEquals(dirk, keys.get(0).to);
		assertArrayEquals(key(vince, groupId), keys.get(0).body.getRaw(2));

		dirk.manager.sendGroupPost(groupId, new byte[] {2}, 0L);
		vince.manager.sendGroupPost(groupId, new byte[] {3}, 0L);
		net.pump();

		assertEquals("Vince takes Dirk's post from Peter", 1,
				remotePostsAccepted(vince, groupId));
		assertEquals(2, remotePostsAccepted(dirk, groupId));
		assertEquals("the keys of an author go to a member once", 2,
				sentOfType(peter, MessageTypes.GROUP_MEMBER_KEYS).size());
		assertEquals(0, sentOfType(dave, MessageTypes.GROUP_RELAY_REQUEST)
				.size() + sentOfType(peter, MessageTypes.GROUP_RELAY_REQUEST)
				.size());
	}

	@Test
	public void onlyTheOwnerMayDeliverAnotherMembersPost() throws Exception {
		byte[] vinceKey = key(vince, groupId);
		long epoch = peter.manager.getGroup(groupId).getEpoch();
		BdfList record = post(groupId, epoch, vinceKey, vinceKey,
				mlOf(vinceKey), new byte[] {7}, net.now);
		vince.manager.sendGroupPost(groupId, new byte[] {1}, 0L);
		net.pump();
		assertEquals(1, remotePostsAccepted(dirk, groupId));

		dirk.receive(net.packet(dave, dirk, record));
		assertEquals("a copy from another member is refused", 1,
				remotePostsAccepted(dirk, groupId));

		dirk.receive(net.packet(peter, dirk, record));
		assertEquals("the same copy from the owner is taken", 2,
				remotePostsAccepted(dirk, groupId));
	}

	@Test
	public void anOwnerOrMemberOnAnEarlierVersionIsLeftAsBefore()
			throws Exception {
		vince.setVersion(peter, MessagingManager.GROUP_POST_RELAY_MIN_VERSION
				- 1);
		vince.manager.sendGroupPost(groupId, new byte[] {1}, 0L);
		net.pump();
		assertEquals(0, sentOfType(vince, MessageTypes.GROUP_RELAY_REQUEST)
				.size());
		assertEquals(0, remotePostsAccepted(dirk, groupId));

		vince.setVersion(peter, MessagingManager.GROUP_POST_RELAY_MIN_VERSION);
		peter.setVersion(dirk, MessagingManager.GROUP_POST_RELAY_MIN_VERSION
				- 1);
		vince.manager.sendGroupPost(groupId, new byte[] {2}, 0L);
		net.pump();
		assertEquals(1, sentOfType(vince, MessageTypes.GROUP_RELAY_REQUEST)
				.size());
		assertEquals(0, sentOfType(peter, MessageTypes.GROUP_POST).size());
		assertEquals(0, remotePostsAccepted(dirk, groupId));

		peter.setVersion(dirk, MessagingManager.GROUP_POST_RELAY_MIN_VERSION);
		vince.manager.sendGroupPost(groupId, new byte[] {3}, 0L);
		net.pump();
		assertEquals(1, remotePostsAccepted(dirk, groupId));
	}

	@Test
	public void aDeleteForEveryoneReachesTheMemberThroughTheOwner()
			throws Exception {
		GroupTrSendResult sent = vince.manager.sendGroupPostReporting(groupId,
				new byte[] {1}, 0L);
		net.pump();
		assertEquals(1, remotePostsAccepted(dirk, groupId));
		assertFalse(removed(dirk, sent.getPostId()));
		BdfList request = deleteRequestFromVince(sent.getPostId());

		peter.receive(net.packet(vince, peter, request));
		net.pump();

		List<InviteTestNet.Packet> copies = sentOfType(peter,
				MessageTypes.GROUP_POST_DELETE);
		assertEquals(1, copies.size());
		assertEquals(dirk, copies.get(0).to);
		assertTrue("Dirk drops the post on the delete Peter passed on",
				removed(dirk, sent.getPostId()));

		peter.receive(net.packet(vince, peter, request));
		net.pump();
		assertEquals(1, sentOfType(peter, MessageTypes.GROUP_POST_DELETE)
				.size());
	}

	@Test
	public void theKeysAreSentFirstAndAHeldDeleteIsAppliedWhenTheyArrive()
			throws Exception {
		GroupTrSendResult sent = vince.manager.sendGroupPostReporting(groupId,
				new byte[] {1}, 0L);
		net.pump(p -> p.from == peter && p.to == dirk);
		List<InviteTestNet.Packet> toDirk = new ArrayList<>(net.heldPackets());
		assertEquals(2, toDirk.size());
		toDirk.sort((x, y) -> Long.compare(x.timestamp, y.timestamp));
		assertEquals(MessageTypes.GROUP_MEMBER_KEYS, toDirk.get(0).type());
		assertEquals(MessageTypes.GROUP_POST, toDirk.get(1).type());
		assertTrue(toDirk.get(0).timestamp < toDirk.get(1).timestamp);

		dirk.receive(toDirk.get(1));
		assertEquals("without the keys the post waits", 0,
				remotePostsAccepted(dirk, groupId));

		peter.receive(net.packet(vince, peter,
				deleteRequestFromVince(sent.getPostId())));
		net.pump(p -> p.to == dirk);
		InviteTestNet.Packet deleteCopy = null;
		for (InviteTestNet.Packet p : net.heldPackets()) {
			if (p.type() == MessageTypes.GROUP_POST_DELETE) deleteCopy = p;
		}
		assertNotNull(deleteCopy);
		dirk.receive(deleteCopy);
		String ns = GroupTrPostRemovals.NAMESPACE_PREFIX + toHexString(groupId);
		assertNotNull("the delete waits for the author's keys",
				dirk.namespace(ns).get(GroupTrPostRemovals.KEY_HELD));

		dirk.receive(toDirk.get(0));

		assertNull(dirk.namespace(ns).get(GroupTrPostRemovals.KEY_HELD));
		String pending = dirk.namespace(ns).get("pending");
		assertNotNull(pending);
		assertTrue(pending.contains(toHexString(sent.getPostId())));
		assertEquals(0, remotePostsAccepted(dirk, groupId));
	}

	private BdfList deleteRequestFromVince(byte[] postId) throws Exception {
		byte[] vinceKey = key(vince, groupId);
		long epoch = vince.manager.getGroup(groupId).getEpoch();
		byte[] signed = GroupTrPostRemovals.signedInput(groupId, epoch,
				vinceKey, postId, net.now);
		BdfList delete = BdfList.of((long) MessageTypes.GROUP_POST_DELETE,
				groupId, epoch, vinceKey, postId, net.now,
				GroupKeysKit.signAs(vinceKey,
						GroupTrConstants.SIGNING_LABEL_GROUP_POST_DELETE,
						signed));
		return BdfList.of((long) MessageTypes.GROUP_RELAY_REQUEST, groupId,
				BdfList.of(key(dirk, groupId)), delete);
	}

	private boolean removed(InviteTestNet.Node n, byte[] postId) {
		for (Object o : n.broadcasts) {
			if (!(o instanceof GroupTrPostsRemovedEvent)) continue;
			GroupTrPostsRemovedEvent e = (GroupTrPostsRemovedEvent) o;
			if (!Arrays.equals(e.getGroupId(), groupId)) continue;
			for (byte[] id : e.getPostIds()) {
				if (Arrays.equals(id, postId)) return true;
			}
		}
		return false;
	}

	@Test
	public void theOwnerKnowsItPassesNothingOnAndMembersKnowItDoes()
			throws Exception {
		assertFalse(peter.manager.ownerPassesPostsOn(groupId));
		assertTrue(vince.manager.ownerPassesPostsOn(groupId));
		assertEquals(1, vince.manager.getMembersOutOfReach(groupId).size());
	}
}
