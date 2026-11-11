package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrSendResult;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupPostDeleteReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrRelayRecordEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.ML_DSA_PUB;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupPostRelayTest {

	private static final long EPOCH = 5L;
	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] cKey = key((byte) 5);
	private final byte[] dKey = key((byte) 6);
	private final byte[] strangerKey = key((byte) 9);

	@Test
	public void aPostIsHandedToTheOwnerForTheMembersOutOfReach()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId owner = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);

		node.manager.sendGroupPost(groupId, bytes("hello"), 0L);

		List<BdfList> requests = recordsTo(node, owner, 60L);
		assertEquals(1, requests.size());
		BdfList request = requests.get(0);
		assertArrayEquals(groupId, request.getRaw(1));
		assertEquals(keys(bKey, cKey), keys(request.getList(2)));
		List<BdfList> direct = recordsTo(node, a, 32L);
		assertEquals(1, direct.size());
		assertSameRecord(direct.get(0), request.getList(3));
		assertEquals(0, recordsTo(node, a, 60L).size());
		assertEquals(1, recordsTo(node, owner, 32L).size());
	}

	@Test
	public void nothingIsHandedOnWhenEveryMemberIsReached() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId owner = node.addContact(creatorKey);
		node.addContact(aKey);
		node.addContact(bKey);
		node.addContact(cKey);

		node.manager.sendGroupPost(groupId, bytes("hello"), 0L);

		assertEquals(0, recordsTo(node, owner, 60L).size());
	}

	@Test
	public void theOwnerHandsNothingOnForItself() throws Exception {
		GroupTrTestNode node = ownerDevice();
		node.addContact(aKey);

		node.manager.sendGroupPost(groupId, bytes("hello"), 0L);

		assertEquals(0, sentOfType(node, 60L));
	}

	@Test
	public void anOwnerOnAnEarlierVersionIsNotAsked() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId owner = node.addContact(creatorKey);
		node.addContact(aKey);
		node.announceMinorVersion(owner,
				MessagingManager.GROUP_POST_RELAY_MIN_VERSION - 1);

		node.manager.sendGroupPost(groupId, bytes("hello"), 0L);

		assertEquals(0, recordsTo(node, owner, 60L).size());
		assertEquals(1, recordsTo(node, owner, 32L).size());
	}

	@Test
	public void aDeleteForEveryoneIsHandedToTheOwnerToo() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId owner = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		GroupTrSendResult sent = node.manager.sendGroupPostReporting(groupId,
				bytes("hello"), 0L);

		node.manager.deletePostsForEveryone(groupId,
				Collections.singletonList(sent.getPostId()));

		List<BdfList> requests = recordsTo(node, owner, 60L);
		assertEquals(2, requests.size());
		BdfList request = requests.get(1);
		assertEquals(keys(bKey, cKey), keys(request.getList(2)));
		BdfList inner = request.getList(3);
		assertEquals(47L, (long) inner.getLong(0));
		assertArrayEquals(localKey, inner.getRaw(3));
		assertArrayEquals(sent.getPostId(), inner.getRaw(4));
		List<BdfList> direct = recordsTo(node, a, 47L);
		assertEquals(1, direct.size());
		assertSameRecord(direct.get(0), inner);
	}

	@Test
	public void theOwnerPassesAVerifiedPostOnToTheListedMembersItReaches()
			throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		ContactId c = node.addContact(cKey);
		node.announceMinorVersion(c,
				MessagingManager.GROUP_POST_RELAY_MIN_VERSION - 1);
		BdfList inner = post(aKey, "hello", node.now, 0L, SIG);

		relay(node, a, inner, bKey, cKey, dKey, aKey, creatorKey);

		List<BdfList> copies = recordsTo(node, b, 32L);
		assertEquals(1, copies.size());
		assertSameRecord(inner, copies.get(0));
		List<BdfList> keys = recordsTo(node, b, 61L);
		assertEquals(1, keys.size());
		assertArrayEquals(groupId, keys.get(0).getRaw(1));
		assertArrayEquals(aKey, keys.get(0).getRaw(2));
		assertArrayEquals(ML_DSA_PUB, keys.get(0).getRaw(3));
		assertArrayEquals(SIG, keys.get(0).getRaw(5));
		assertEquals("an app on an earlier version is sent no copy", 0,
				recordsTo(node, c, 32L).size());
		assertEquals(0, recordsTo(node, c, 61L).size());
		assertEquals("the author gets nothing back", 0,
				recordsTo(node, a, 32L).size());
		assertEquals(0, recordsTo(node, a, 61L).size());
		assertEquals(2, node.sent.size());
	}

	@Test
	public void theSameRecordIsPassedOnOnceAndTheKeysOncePerMember()
			throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		BdfList first = post(aKey, "one", node.now, 0L, SIG);

		relay(node, a, first, bKey);
		relay(node, a, first, bKey);
		assertEquals(1, recordsTo(node, b, 32L).size());

		relay(node, a, post(aKey, "two", node.now + 1, 0L, SIG), bKey);
		assertEquals(2, recordsTo(node, b, 32L).size());
		assertEquals(1, recordsTo(node, b, 61L).size());
	}

	@Test
	public void theKeysRecordIsQueuedBeforeThePassedOnCopy() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now, 0L, SIG), bKey);

		MessageId keys = copyOfType(node, b, 61L);
		MessageId copy = copyOfType(node, b, 32L);
		assertEquals(node.now, node.timestampOf(copy));
		assertTrue("the keys must be sent before the copy",
				node.timestampOf(keys) < node.timestampOf(copy));
	}

	@Test
	public void aKeyListedMoreThanOnceGetsOneCopy() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now, 0L, SIG), bKey, bKey,
				bKey, bKey);

		assertEquals(1, recordsTo(node, b, 32L).size());
		assertEquals(1, recordsTo(node, b, 61L).size());
		assertEquals(2, node.sent.size());
	}

	@Test
	public void aMemberAddedAgainIsSentTheKeysAgain() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		relay(node, a, post(aKey, "one", node.now, 0L, SIG), bKey);
		assertEquals(1, recordsTo(node, b, 61L).size());
		relay(node, a, post(aKey, "two", node.now + 1, 0L, SIG), bKey);
		assertEquals(1, recordsTo(node, b, 61L).size());

		node.manager.removeMember(groupId, bKey);
		Settings relayed = node.namespace(
				GroupTrManagerImpl.RELAY_NAMESPACE_PREFIX
						+ toHexString(groupId));
		assertNotNull(relayed);
		String pairs = relayed.get(GroupTrManagerImpl.KEYS_SENT_KEY);
		assertFalse(pairs != null && pairs.contains(toHexString(bKey)));
		node.putGroup(groupId, creatorKey, EPOCH + 2, creator(creatorKey),
				member(aKey, 1L), member(bKey, EPOCH + 2), member(cKey, 1L),
				member(dKey, 1L));

		relay(node, a, post(aKey, "three", node.now + 2, 0L, SIG), bKey);

		assertEquals(2, recordsTo(node, b, 61L).size());
	}

	@Test
	public void aPendingPostShowsAtOnceWhenTheKeysArrive() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.withoutMlDsaKey(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		ContactId owner = node.addContact(creatorKey);
		MessageId copy = node.receive(owner, groupId, bKey, EPOCH,
				bytes("hello"), node.now, 0L, SIG);
		assertEquals(0, accepted(node));
		assertEquals(0, node.manager.getUnreadCount(groupId));

		memberKeys(node, owner, bKey, ML_DSA_PUB, SIG);

		assertEquals("the post is announced without opening the group", 1,
				accepted(node));
		assertEquals(1, node.manager.getUnreadCount(groupId));
		BdfDictionary meta = node.metadata(copy);
		assertNotNull(meta);
		assertEquals(Long.valueOf(MessagingManager.GROUP_POST_STATE_ACCEPTED),
				meta.getOptionalLong(MessagingManager.MSG_KEY_GROUP_POST_STATE));
		assertEquals(1, node.manager.getRecentPosts(groupId).size());
	}

	@Test
	public void aDeleteWhoseAuthorIsUnknownWaitsForTheKeys() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.withoutMlDsaKey(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		ContactId owner = node.addContact(creatorKey);
		MessageId copy = node.receive(owner, groupId, bKey, EPOCH,
				bytes("hello"), 1_000L, 0L, SIG);

		delete(node, owner, bKey, postId(bKey, "hello", 1_000L), SIG);

		assertTrue("an unverifiable delete is held, not applied",
				node.isStored(copy));
		assertNotNull(removals(node).get(GroupTrPostRemovals.KEY_HELD));

		memberKeys(node, owner, bKey, ML_DSA_PUB, SIG);

		assertFalse(node.isStored(copy));
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
		assertTrue(nothingHeld(node));
	}

	@Test
	public void aHeldDeleteDropsItsPostWhenThePostArrivesAfterTheKeys()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.withoutMlDsaKey(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		ContactId owner = node.addContact(creatorKey);
		delete(node, owner, bKey, postId(bKey, "early", 1_000L), SIG);
		memberKeys(node, owner, bKey, ML_DSA_PUB, SIG);

		MessageId copy = node.receive(owner, groupId, bKey, EPOCH,
				bytes("early"), 1_000L, 0L, SIG);

		assertFalse(node.isStored(copy));
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
	}

	@Test
	public void aHeldDeleteWithAForgedSignatureIsDroppedWhenTheKeysArrive()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.withoutMlDsaKey(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		ContactId owner = node.addContact(creatorKey);
		delete(node, owner, bKey, postId(bKey, "early", 1_000L), FORGED_SIG);
		memberKeys(node, owner, bKey, ML_DSA_PUB, SIG);

		MessageId copy = node.receive(owner, groupId, bKey, EPOCH,
				bytes("early"), 1_000L, 0L, SIG);

		assertTrue(node.isStored(copy));
		assertEquals(1, node.manager.getRecentPosts(groupId).size());
		assertTrue(nothingHeld(node));
	}

	@Test
	public void heldDeletesAreBounded() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.withoutMlDsaKey(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		ContactId owner = node.addContact(creatorKey);

		for (int i = 0; i < GroupTrPostRemovals.MAX_HELD_PER_GROUP + 5; i++) {
			delete(node, owner, bKey, postId(bKey, "p" + i, 1_000L), SIG);
		}

		String held = removals(node).get(GroupTrPostRemovals.KEY_HELD);
		assertNotNull(held);
		assertEquals(GroupTrPostRemovals.MAX_HELD_PER_GROUP,
				held.split(",").length);
	}

	@Test
	public void aRequestForSomeoneElsesRecordIsIgnored() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		node.addContact(bKey);

		relay(node, a, post(cKey, "hello", node.now, 0L, SIG), bKey);

		assertEquals(0, node.sent.size());
	}

	@Test
	public void aRequestWhoseSignatureFailsIsIgnored() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now, 0L, FORGED_SIG), bKey);

		assertEquals(0, node.sent.size());
	}

	@Test
	public void aRequestFromOutsideTheGroupIsIgnored() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId stranger = node.addContact(strangerKey);
		node.addContact(bKey);

		relay(node, stranger, post(strangerKey, "hello", node.now, 0L, SIG),
				bKey);

		assertEquals(0, node.sent.size());
	}

	@Test
	public void aRequestListingMoreKeysThanTheGroupHasIsIgnored()
			throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now, 0L, SIG), bKey, cKey,
				dKey, key((byte) 11), key((byte) 12), key((byte) 13),
				key((byte) 14));

		assertEquals(0, node.sent.size());
	}

	@Test
	public void anExpiredPostIsNotPassedOn() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now - 2 * HOUR, HOUR, SIG),
				node.now - 2 * HOUR, bKey);

		assertEquals(0, node.sent.size());
	}

	@Test
	public void aMemberDeviceDoesNotPassRecordsOn() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId a = node.addContact(aKey);
		node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now, 0L, SIG), bKey);

		assertEquals(0, node.sent.size());
	}

	@Test
	public void thePassedOnCopyLivesAtMostSevenDays() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now, 0L, SIG), bKey);

		MessageId copy = copyOfType(node, b, 32L);
		assertEquals(Long.valueOf(GroupTrManagerImpl.RELAYED_COPY_KEPT_MS),
				node.cleanupTimerDuration(copy));
		assertEquals(Long.valueOf(node.now
				+ GroupTrManagerImpl.RELAYED_COPY_KEPT_MS),
				node.cleanupDeadline(copy));
		assertEquals(7L * DAY, GroupTrManagerImpl.RELAYED_COPY_KEPT_MS);
	}

	@Test
	public void thePassedOnCopyLivesNoLongerThanThePost() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now, HOUR, SIG), bKey);

		MessageId copy = copyOfType(node, b, 32L);
		assertEquals(Long.valueOf(HOUR), node.cleanupTimerDuration(copy));
		assertEquals(Long.valueOf(node.now + HOUR), node.cleanupDeadline(copy));
	}

	@Test
	public void thePassedOnCopyFollowsTheGroupTimer() throws Exception {
		GroupTrTestNode node = ownerDevice();
		node.setGroupTimer(groupId, 2 * HOUR);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		relay(node, a, post(aKey, "hello", node.now, 0L, SIG), bKey);

		MessageId copy = copyOfType(node, b, 32L);
		assertEquals(Long.valueOf(2 * HOUR), node.cleanupTimerDuration(copy));
	}

	@Test
	public void aPostOfAMemberOutOfReachIsTakenFromTheOwner()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId owner = node.addContact(creatorKey);

		MessageId copy = node.receive(owner, groupId, bKey, EPOCH,
				bytes("hello"), node.now, 0L, SIG);

		List<GroupTrPost> posts = node.manager.getRecentPosts(groupId);
		assertEquals(1, posts.size());
		assertArrayEquals(bKey, posts.get(0).getSenderPubKey());
		assertTrue(node.isStored(copy));
	}

	@Test
	public void aPostWaitsUntilTheOwnerHandsOverTheAuthorsKeys()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.withoutMlDsaKey(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		ContactId owner = node.addContact(creatorKey);
		MessageId copy = node.receive(owner, groupId, bKey, EPOCH,
				bytes("hello"), node.now, 0L, SIG);
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
		assertTrue(node.isStored(copy));

		memberKeys(node, owner, bKey, ML_DSA_PUB, SIG);

		GroupTrMember b = memberEntry(node, bKey);
		assertArrayEquals(ML_DSA_PUB, b.getMlDsaPubKey());
		List<GroupTrPost> posts = node.manager.getRecentPosts(groupId);
		assertEquals(1, posts.size());
		assertArrayEquals(bKey, posts.get(0).getSenderPubKey());
	}

	@Test
	public void memberKeysAreTakenOnlyFromTheOwnerAndOnlyWhenSigned()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.withoutMlDsaKey(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		ContactId owner = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);

		memberKeys(node, a, bKey, ML_DSA_PUB, SIG);
		assertNull(memberEntry(node, bKey).getMlDsaPubKey());

		memberKeys(node, owner, bKey, ML_DSA_PUB, FORGED_SIG);
		assertNull(memberEntry(node, bKey).getMlDsaPubKey());

		memberKeys(node, owner, strangerKey, ML_DSA_PUB, SIG);
		assertEquals(4, node.group(groupId).getMembers().size());
		assertFalse(node.isMember(groupId, strangerKey));
	}

	@Test
	public void aKeyThisDeviceAlreadyKnowsIsNeverReplaced() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId owner = node.addContact(creatorKey);
		byte[] other = ML_DSA_PUB.clone();
		other[0] = 1;

		memberKeys(node, owner, aKey, other, SIG);

		assertArrayEquals(ML_DSA_PUB, memberEntry(node, aKey).getMlDsaPubKey());
	}

	@Test
	public void aCopyFromTheAuthorAndOneFromTheOwnerShowOnce()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId owner = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);

		MessageId direct = node.receive(a, groupId, aKey, EPOCH,
				bytes("hello"), node.now, 0L, SIG);
		MessageId passedOn = node.receive(owner, groupId, aKey, EPOCH,
				bytes("hello"), node.now, 0L, SIG);

		assertEquals(1, node.manager.getRecentPosts(groupId).size());
		assertTrue(node.isStored(direct));
		assertFalse(node.isStored(passedOn));
	}

	@Test
	public void theDeviceKnowsWhetherTheOwnerPassesPostsOn() throws Exception {
		GroupTrTestNode node = memberDevice();
		assertFalse(node.manager.ownerPassesPostsOn(groupId));

		ContactId owner = node.addContact(creatorKey);
		assertTrue(node.manager.ownerPassesPostsOn(groupId));

		node.announceMinorVersion(owner,
				MessagingManager.GROUP_POST_RELAY_MIN_VERSION - 1);
		assertFalse(node.manager.ownerPassesPostsOn(groupId));

		GroupTrTestNode ownerNode = ownerDevice();
		ownerNode.addContact(aKey);
		assertFalse(ownerNode.manager.ownerPassesPostsOn(groupId));
	}

	@Test
	public void theSignedInputOfTheMemberKeysIsByteExact() throws Exception {
		GroupTrTestNode node = ownerDevice();
		byte[] signed = node.manager.memberKeysSignedInput(groupId, aKey,
				ML_DSA_PUB, 0x0102030405060708L);
		assertEquals(32 + 32 + 32 + 8 + 1, signed.length);
		assertArrayEquals(groupId, Arrays.copyOfRange(signed, 0, 32));
		assertArrayEquals(aKey, Arrays.copyOfRange(signed, 32, 64));
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
				Arrays.copyOfRange(signed, 96, 104));
		assertEquals((byte) 0x0E, signed[104]);
		assertEquals("org.zerionproject/GROUP_MEMBER_KEYS",
				GroupTrConstants.SIGNING_LABEL_GROUP_MEMBER_KEYS);
	}

	private GroupTrTestNode memberDevice() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L),
				member(cKey, 1L));
		return node;
	}

	private GroupTrTestNode ownerDevice() {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L), member(bKey, 1L), member(cKey, 1L),
				member(dKey, 1L));
		return node;
	}

	private BdfList post(byte[] sender, String text, long timestamp, long ttl,
			byte[] sig) {
		return ttl > 0
				? BdfList.of(32L, groupId, EPOCH, sender, "Name", bytes(text),
						sig, ttl)
				: BdfList.of(32L, groupId, EPOCH, sender, "Name", bytes(text),
						sig);
	}

	private void relay(GroupTrTestNode node, ContactId from, BdfList inner,
			byte[]... listed) {
		relay(node, from, inner, node.now, listed);
	}

	private void relay(GroupTrTestNode node, ContactId from, BdfList inner,
			long timestamp, byte[]... listed) {
		BdfList keys = new BdfList();
		for (byte[] k : listed) keys.add(k);
		BdfList record = BdfList.of((long) MessageTypes.GROUP_RELAY_REQUEST,
				groupId, keys, inner);
		node.manager.eventOccurred(new GroupTrRelayRecordEvent(from,
				new MessageId(getRandomId()), MessageTypes.GROUP_RELAY_REQUEST,
				record, timestamp));
	}

	private void memberKeys(GroupTrTestNode node, ContactId from,
			byte[] memberKey, byte[] mlDsa, byte[] sig) {
		BdfList record = BdfList.of((long) MessageTypes.GROUP_MEMBER_KEYS,
				groupId, memberKey, mlDsa, node.now, sig);
		node.manager.eventOccurred(new GroupTrRelayRecordEvent(from,
				new MessageId(getRandomId()), MessageTypes.GROUP_MEMBER_KEYS,
				record, node.now));
	}

	private void delete(GroupTrTestNode node, ContactId from, byte[] author,
			byte[] target, byte[] sig) {
		node.manager.eventOccurred(new GroupPostDeleteReceivedEvent(from,
				new MessageId(getRandomId()), groupId, EPOCH, author, target,
				node.now, sig));
	}

	private boolean nothingHeld(GroupTrTestNode node) {
		String held = removals(node).get(GroupTrPostRemovals.KEY_HELD);
		return held == null || held.isEmpty();
	}

	private Settings removals(GroupTrTestNode node) {
		Settings s = node.namespace(GroupTrPostRemovals.NAMESPACE_PREFIX
				+ toHexString(groupId));
		return s == null ? new Settings() : s;
	}

	private static int accepted(GroupTrTestNode node) {
		int n = 0;
		for (Object b : node.broadcasts) {
			if (b instanceof GroupTrPostAcceptedEvent
					&& !((GroupTrPostAcceptedEvent) b).isLocal()) {
				n++;
			}
		}
		return n;
	}

	private byte[] postId(byte[] author, String text, long timestamp) {
		byte[] body = bytes(text);
		ByteBuffer signed = ByteBuffer.allocate(32 + 4 + 32 + 32 + 32 + 8 + 8);
		signed.put(groupId).putInt((int) EPOCH).put(author)
				.put(sha256("org.zerionproject/GROUP_POST_NAME", bytes("Name")))
				.put(sha256(MessagingManager.GROUP_POST_BODY_HASH_LABEL, body))
				.putLong(timestamp).putLong(0L);
		return sha256("org.zerionproject/GROUP_POST_SEEN", signed.array());
	}

	private static byte[] sha256(String label, byte[] input) {
		try {
			MessageDigest d = MessageDigest.getInstance("SHA-256");
			d.update(label.getBytes(StandardCharsets.UTF_8));
			d.update(input);
			return d.digest();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private GroupTrMember memberEntry(GroupTrTestNode node, byte[] pub)
			throws Exception {
		for (GroupTrMember m : node.group(groupId).getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return m;
		}
		throw new AssertionError("not a member");
	}

	private static MessageId copyOfType(GroupTrTestNode node, ContactId to,
			long type) throws Exception {
		for (MessageId id : node.copiesTo(to)) {
			BdfDictionary meta = node.metadata(id);
			assertNotNull(meta);
			Long t = meta.getOptionalLong("messageType");
			if (t != null && t == type) return id;
		}
		throw new AssertionError("no copy of type " + type);
	}

	private static void assertSameRecord(BdfList expected, BdfList actual)
			throws Exception {
		assertEquals(expected.size(), actual.size());
		for (int i = 0; i < expected.size(); i++) {
			Object e = expected.get(i);
			Object a = actual.get(i);
			if (e instanceof byte[]) {
				assertArrayEquals((byte[]) e, (byte[]) a);
			} else {
				assertEquals(e, a);
			}
		}
	}

	private static Set<String> keys(byte[]... keys) {
		Set<String> out = new HashSet<>();
		for (byte[] k : keys) out.add(toHexString(k));
		return out;
	}

	private static Set<String> keys(BdfList list) throws Exception {
		Set<String> out = new HashSet<>();
		for (int i = 0; i < list.size(); i++) out.add(toHexString(list.getRaw(i)));
		return out;
	}

	private static int sentOfType(GroupTrTestNode node, long type)
			throws Exception {
		int n = 0;
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.body.getLong(0) == type) n++;
		}
		return n;
	}

	private static List<BdfList> recordsTo(GroupTrTestNode node, ContactId to,
			long type) throws Exception {
		List<BdfList> out = new ArrayList<>();
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(to) && s.body.getLong(0) == type) out.add(s.body);
		}
		return out;
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
