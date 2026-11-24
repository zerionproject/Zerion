package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.messaging.event.GroupSettingsChangedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupSettingsPassOnTest {

	private static final long EPOCH = 5L;
	private static final long MINUTE = 60L * 1000L;
	private static final long HOUR = 60L * MINUTE;
	private static final long DAY = 24L * HOUR;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] outsiderKey = key((byte) 9);

	@Test
	public void theOwnersSettingPassedOnByAMemberIsApplied() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromMember = node.addContact(aKey);

		settings(node, fromMember, DAY, 10L, SIG);

		assertEquals(DAY, node.group(groupId).getDefaultAutoDeleteTimerMs());
		assertEquals(Long.valueOf(10L), node.settingsTimestamp(groupId));
	}

	@Test
	public void aPassedOnSettingTheOwnerDidNotSignIsRefused()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromMember = node.addContact(aKey);

		settings(node, fromMember, DAY, 10L, FORGED_SIG);

		assertEquals(0L, node.group(groupId).getDefaultAutoDeleteTimerMs());
		assertNull(node.settingsTimestamp(groupId));
	}

	@Test
	public void aSettingFromSomeoneOutsideTheGroupIsRefused()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId outsider = node.addContact(outsiderKey);

		settings(node, outsider, DAY, 10L, SIG);

		assertEquals(0L, node.group(groupId).getDefaultAutoDeleteTimerMs());
	}

	@Test
	public void theOwnersDeviceTakesNoSettingFromAMember() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		long chosen = node.settingsTimestamp(groupId);

		settings(node, a, HOUR, chosen + 1000L, SIG);

		assertEquals(DAY, node.group(groupId).getDefaultAutoDeleteTimerMs());
		assertEquals(Long.valueOf(chosen), node.settingsTimestamp(groupId));
	}

	@Test
	public void aMemberWhoPostsWithoutTheTimerIsSentTheOwnersSetting()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		node.manager.getRecentPosts(groupId);
		node.sent.clear();

		node.receive(a, groupId, aKey, EPOCH, "one".getBytes("UTF-8"),
				node.now, 0L, SIG);

		List<BdfList> offered = recordsTo(node, a, 46L);
		assertEquals("the member who is behind was not sent the setting", 1,
				offered.size());
		assertArrayEquals(groupId, offered.get(0).getRaw(1));
		assertEquals(DAY, (long) offered.get(0).getLong(2));
		assertEquals(10L, (long) offered.get(0).getLong(3));
		assertArrayEquals(SIG, offered.get(0).getRaw(4));

		node.now += MINUTE;
		node.receive(a, groupId, aKey, EPOCH, "two".getBytes("UTF-8"),
				node.now, 0L, SIG);
		assertEquals("the setting is sent again within the waiting time", 1,
				recordsTo(node, a, 46L).size());

		node.now += GroupTrManagerImpl.SETTINGS_OFFER_INTERVAL_MS;
		node.receive(a, groupId, aKey, EPOCH, "three".getBytes("UTF-8"),
				node.now, 0L, SIG);
		assertEquals(2, recordsTo(node, a, 46L).size());
	}

	@Test
	public void aMemberWhosePostsCarryTheTimerIsSentNothing()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		node.manager.getRecentPosts(groupId);
		node.sent.clear();

		node.receive(a, groupId, aKey, EPOCH, "one".getBytes("UTF-8"),
				node.now, DAY, SIG);

		assertEquals(0, recordsTo(node, a, 46L).size());
	}

	@Test
	public void theOwnerAnswersAMemberWhoPostsWithoutTheTimer()
			throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		node.addContact(bKey);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		node.manager.getRecentPosts(groupId);
		node.sent.clear();

		node.receive(a, groupId, aKey, EPOCH, "one".getBytes("UTF-8"),
				node.now, 0L, SIG);

		assertEquals(1, recordsTo(node, a, 46L).size());
	}

	@Test
	public void anOlderSettingIsAnsweredWithTheOneHeld() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		node.sent.clear();

		settings(node, a, HOUR, 5L, SIG);

		assertEquals(DAY, node.group(groupId).getDefaultAutoDeleteTimerMs());
		List<BdfList> offered = recordsTo(node, a, 46L);
		assertEquals(1, offered.size());
		assertEquals(DAY, (long) offered.get(0).getLong(2));
		assertEquals(10L, (long) offered.get(0).getLong(3));
	}

	@Test
	public void aMemberHandsTheSettingOnAShortWhileAfterItArrives()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		settings(node, fromCreator, DAY, 10L, SIG);

		assertEquals("nothing is handed on at receipt", 0,
				settingsSent(node));
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MIN_DELAY_MS - 1);
		assertEquals("nothing is handed on before the shortest wait", 0,
				settingsSent(node));
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);

		for (ContactId c : Arrays.asList(a, b)) {
			List<BdfList> passed = recordsTo(node, c, 46L);
			assertEquals("handed on without any post", 1, passed.size());
			assertArrayEquals(groupId, passed.get(0).getRaw(1));
			assertEquals(DAY, (long) passed.get(0).getLong(2));
			assertEquals(10L, (long) passed.get(0).getLong(3));
			assertArrayEquals(SIG, passed.get(0).getRaw(4));
		}
		assertEquals("the owner is not sent its own setting", 0,
				recordsTo(node, fromCreator, 46L).size());
		later(node, DAY);
		assertEquals("the pass runs once", 2, settingsSent(node));
	}

	@Test
	public void theWaitBeforeHandingOnIsHalfAMinuteToTwoMinutes()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId a = node.addContact(aKey);
		node.addContact(bKey);

		settings(node, a, DAY, 10L, SIG);

		List<Long> waits = node.waitingTimerDelays();
		assertEquals(1, waits.size());
		assertTrue(waits.get(0)
				>= GroupTrManagerImpl.SETTINGS_PASS_MIN_DELAY_MS);
		assertTrue(waits.get(0)
				<= GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
	}

	@Test
	public void twoSettingsBeforeThePassAreHandedOnOnceAsTheNewer()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		settings(node, a, DAY, 10L, SIG);
		later(node, 1000L);
		settings(node, a, HOUR, 20L, SIG);

		assertEquals("one pass waits for the group", 1,
				node.waitingTimerDelays().size());
		assertEquals(0, settingsSent(node));
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		List<BdfList> toB = recordsTo(node, b, 46L);
		assertEquals("only the newer setting is handed on", 1, toB.size());
		assertEquals(HOUR, (long) toB.get(0).getLong(2));
		assertEquals(20L, (long) toB.get(0).getLong(3));
		assertEquals("the member it came from is sent nothing", 0,
				recordsTo(node, a, 46L).size());
		assertEquals(0, node.waitingTimerDelays().size());
	}

	@Test
	public void aSettingInsideTheQuietTimeIsHandedOnWhenThatTimeEnds()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		settings(node, a, DAY, 10L, SIG);
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		assertEquals(1, recordsTo(node, b, 46L).size());

		later(node, MINUTE);
		settings(node, a, HOUR, 20L, SIG);
		settings(node, a, DAY, 30L, SIG);

		long quiet = GroupTrManagerImpl.SETTINGS_PASS_QUIET_MS;
		assertEquals("the next pass waits for the end of the quiet time",
				Collections.singletonList(quiet - MINUTE),
				node.waitingTimerDelays());
		later(node, quiet - MINUTE - 1);
		assertEquals("nothing more is handed on inside the quiet time", 1,
				recordsTo(node, b, 46L).size());
		later(node, 1L);
		List<BdfList> toB = recordsTo(node, b, 46L);
		assertEquals(2, toB.size());
		assertEquals(DAY, (long) toB.get(1).getLong(2));
		assertEquals(30L, (long) toB.get(1).getLong(3));

		later(node, quiet);
		settings(node, a, HOUR, 40L, SIG);
		List<Long> waits = node.waitingTimerDelays();
		assertEquals(1, waits.size());
		assertTrue("after the quiet time the wait is short again",
				waits.get(0) <= GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		assertEquals(3, recordsTo(node, b, 46L).size());
	}

	@Test
	public void aPostHandsNothingOnAndASettingIsSentOncePerContact()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		settings(node, fromCreator, DAY, 10L, SIG);

		node.manager.sendGroupPost(groupId, "one".getBytes("UTF-8"), 0L);

		assertEquals("a post hands nothing on", 0, settingsSent(node));
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		assertEquals(1, recordsTo(node, a, 46L).size());
		assertEquals(1, recordsTo(node, b, 46L).size());

		node.manager.sendGroupPost(groupId, "two".getBytes("UTF-8"), 0L);
		node.manager.sendGroupPost(groupId, "three".getBytes("UTF-8"), 0L);
		connected(node, a);
		connected(node, b);
		later(node, DAY);
		assertEquals("a post or a connection sends the same setting again",
				1, recordsTo(node, a, 46L).size());
		assertEquals(1, recordsTo(node, b, 46L).size());

		settings(node, fromCreator, HOUR, 20L, SIG);
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		List<BdfList> passed = recordsTo(node, a, 46L);
		assertEquals("a newer setting is handed on once more", 2,
				passed.size());
		assertEquals(HOUR, (long) passed.get(1).getLong(2));
		assertEquals(20L, (long) passed.get(1).getLong(3));
		node.manager.sendGroupPost(groupId, "four".getBytes("UTF-8"), 0L);
		connected(node, a);
		assertEquals(2, recordsTo(node, a, 46L).size());
		assertEquals(2, recordsTo(node, b, 46L).size());
	}

	@Test
	public void aMemberThatConnectsBeforeThePassIsSentTheSettingOnce()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		settings(node, fromCreator, DAY, 10L, SIG);

		connected(node, a);

		assertEquals(1, recordsTo(node, a, 46L).size());
		assertEquals(0, recordsTo(node, b, 46L).size());
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		assertEquals("the pass leaves out the member already sent it", 1,
				recordsTo(node, a, 46L).size());
		assertEquals(1, recordsTo(node, b, 46L).size());
	}

	@Test
	public void aMemberReachedLaterIsHandedTheSettingWhenItConnects()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		ContactId a = node.addContact(aKey);
		assertEquals(0, recordsTo(node, a, 46L).size());

		connected(node, a);

		List<BdfList> handed = recordsTo(node, a, 46L);
		assertEquals("the setting is handed over at the connection", 1,
				handed.size());
		assertArrayEquals(groupId, handed.get(0).getRaw(1));
		assertEquals(DAY, (long) handed.get(0).getLong(2));
		assertEquals(10L, (long) handed.get(0).getLong(3));
		assertArrayEquals(SIG, handed.get(0).getRaw(4));

		int writes = node.writeTxns();
		int reads = node.readTxns();
		connected(node, a);
		connected(node, a);
		assertEquals("a later connection sends the same setting again", 1,
				recordsTo(node, a, 46L).size());
		assertEquals("a connection of a member already sent it writes"
				+ " nothing", writes, node.writeTxns());
		assertEquals(reads, node.readTxns());

		settings(node, fromCreator, HOUR, 20L, SIG);
		ContactId b = node.addContact(bKey);
		connected(node, b);
		List<BdfList> toB = recordsTo(node, b, 46L);
		assertEquals(1, toB.size());
		assertEquals(HOUR, (long) toB.get(0).getLong(2));
		assertEquals(20L, (long) toB.get(0).getLong(3));
		connected(node, a);
		List<BdfList> toA = recordsTo(node, a, 46L);
		assertEquals("the newer setting is handed over once more", 2,
				toA.size());
		assertEquals(HOUR, (long) toA.get(1).getLong(2));
	}

	@Test
	public void aContactOnAnEarlierVersionIsHandedNothingWhenItConnects()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		ContactId a = node.addContact(aKey);
		node.announceMinorVersion(a, 15);
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		int writes = node.writeTxns();

		connected(node, a);
		assertEquals(0, recordsTo(node, a, 46L).size());
		assertEquals("a connection that cannot be sent the setting writes"
				+ " nothing", writes, node.writeTxns());

		node.versionUpdated(a, 16);
		assertEquals(1, recordsTo(node, a, 46L).size());
		connected(node, a);
		assertEquals("the copy sent at the update is not sent again", 1,
				recordsTo(node, a, 46L).size());
	}

	@Test
	public void aContactOutsideTheGroupIsHandedNothingWhenItConnects()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		ContactId outsider = node.addContact(outsiderKey);

		connected(node, outsider);
		connected(node, fromCreator);

		assertEquals(0, recordsTo(node, outsider, 46L).size());
		assertEquals("the owner is handed nothing", 0,
				recordsTo(node, fromCreator, 46L).size());
	}

	@Test
	public void theMemberTheSettingCameFromIsNotHandedItBack()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		settings(node, a, DAY, 10L, SIG);

		assertEquals("the member it came from is noted at receipt",
				"10:" + a.getInt(), sentToNote(node));
		connected(node, a);
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		assertEquals(0, recordsTo(node, a, 46L).size());
		assertEquals(1, recordsTo(node, b, 46L).size());
	}

	@Test
	public void theOwnersDeviceHandsNothingOnWhenAMemberConnects()
			throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		assertEquals(1, recordsTo(node, a, 46L).size());

		connected(node, a);

		assertEquals(1, recordsTo(node, a, 46L).size());
		assertNull(node.namespace(GroupTrConstants.SETTINGS_NS_PREFIX
				+ org.zerionproject.core.util.StringUtils.toHexString(groupId))
				.get(GroupTrConstants.S_SETTINGS_SENT_TO));
	}

	@Test
	public void theNoteOfWhoWasSentTheSettingHoldsMembersOnly()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		assertEquals("the owner is not noted", "10", sentToNote(node));
		later(node, GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS);
		assertEquals("10:" + a.getInt() + "," + b.getInt(),
				sentToNote(node));

		node.removeContact(b);
		node.manager.sendGroupPost(groupId, "one".getBytes("UTF-8"), 0L);
		assertEquals(1, recordsTo(node, a, 46L).size());

		settings(node, fromCreator, HOUR, 20L, SIG);
		assertEquals("a newer setting starts the note afresh", "20",
				sentToNote(node));
		later(node, GroupTrManagerImpl.SETTINGS_PASS_QUIET_MS);

		assertEquals("a contact no longer reached is not in the note",
				"20:" + a.getInt(), sentToNote(node));
		assertEquals(2, recordsTo(node, a, 46L).size());
		assertEquals(1, recordsTo(node, b, 46L).size());
	}

	@Test
	public void aMemberThatHoldsNoSettingPassesNothingOn() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId a = node.addContact(aKey);

		node.manager.sendGroupPost(groupId, "one".getBytes("UTF-8"), 0L);

		assertEquals(0, recordsTo(node, a, 46L).size());
	}

	@Test
	public void aLateSettingReachesThePostsReceivedSinceItWasChosen()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long before = node.now;
		MessageId early = node.receive(a, groupId, aKey, EPOCH,
				"early".getBytes("UTF-8"), before, 0L, SIG);
		node.now += HOUR;
		long chosen = node.now;
		node.now += MINUTE;
		long arrived = node.now;
		MessageId late = node.receive(a, groupId, aKey, EPOCH,
				"late".getBytes("UTF-8"), arrived, 0L, SIG);
		assertNull(node.cleanupDeadline(late));
		node.now += MINUTE;

		settings(node, fromCreator, HOUR, chosen, SIG);

		assertEquals("a post that arrived after the owner chose the timer"
						+ " takes it, counted from its arrival",
				Long.valueOf(arrived + HOUR), node.cleanupDeadline(late));
		assertNull("a post from before the setting keeps none",
				node.cleanupDeadline(early));
		node.now = arrived + HOUR;
		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertEquals(1, countFrom(shown, aKey));
		assertEquals("early", shown.get(0).getText());
		node.dropFromMemory(groupId);
		node.now = arrived + HOUR - 1;
		int timed = 0;
		for (GroupTrPost p : node.manager.getRecentPosts(groupId)) {
			if (p.getAutoDeleteTimerMs() == HOUR) timed++;
		}
		assertEquals("the stored copy carries the timer too", 1, timed);
	}

	@Test
	public void aLateSettingShortensALongerTimerAlreadyRunning()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		node.manager.getRecentPosts(groupId);
		long chosen = node.now;
		node.now += MINUTE;
		long arrived = node.now;
		MessageId held = node.receive(a, groupId, aKey, EPOCH,
				"x".getBytes("UTF-8"), arrived, DAY, SIG);
		assertEquals(Long.valueOf(arrived + DAY), node.cleanupDeadline(held));

		settings(node, fromCreator, HOUR, chosen, SIG);

		assertEquals(Long.valueOf(arrived + HOUR), node.cleanupDeadline(held));
	}

	@Test
	public void aLateSettingThatSwitchesTheTimerOffChangesNoHeldPost()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		settings(node, fromCreator, HOUR, 10L, SIG);
		node.manager.getRecentPosts(groupId);
		long arrived = node.now;
		MessageId held = node.receive(a, groupId, aKey, EPOCH,
				"x".getBytes("UTF-8"), arrived, HOUR, SIG);

		settings(node, fromCreator, 0L, 20L, SIG);

		assertEquals(Long.valueOf(arrived + HOUR), node.cleanupDeadline(held));
	}

	@Test
	public void aLateSettingReachesTheOwnPostsSentWithoutIt()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		long chosen = node.now;
		node.now += MINUTE;
		long posted = node.now;
		node.manager.sendGroupPost(groupId, "mine".getBytes("UTF-8"), 0L);
		MessageId acknowledged = node.copiesTo(a).get(0);
		MessageId waiting = node.copiesTo(b).get(0);
		node.markAcknowledged(acknowledged);
		assertNull(node.cleanupTimerDuration(acknowledged));
		node.now += MINUTE;

		settings(node, fromCreator, HOUR, chosen, SIG);

		assertEquals("a copy the member already has is due an hour after the"
						+ " post", Long.valueOf(posted + HOUR),
				node.cleanupDeadline(acknowledged));
		assertEquals("a copy still waiting for its member starts its timer"
						+ " when that member has it", Long.valueOf(HOUR),
				node.cleanupTimerDuration(waiting));
		assertNull(node.cleanupDeadline(waiting));
		node.now = posted + HOUR;
		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId),
				localKey));
	}

	@Test
	public void aMemberAddedWhileTheTimerIsOffReceivesTheSettingToo()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L));
		node.addContact(aKey);
		ContactId added = node.addContact(bKey);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		node.manager.setGroupAutoDeleteTimer(groupId, 0L);
		node.sent.clear();

		node.manager.addMember(groupId, bKey, "New");

		List<BdfList> records = recordsTo(node, added, 46L);
		assertEquals(1, records.size());
		assertEquals(0L, (long) records.get(0).getLong(2));
	}

	@Test
	public void aPassedOnSettingAppliesFromThenOnAndTheOwnersCopyReachesBack()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long chosen = node.now;
		node.now += MINUTE;
		long arrived = node.now;
		MessageId held = node.receive(a, groupId, aKey, EPOCH,
				"held".getBytes("UTF-8"), arrived, 0L, SIG);
		node.now += MINUTE;

		settings(node, a, HOUR, chosen, SIG);

		assertEquals(HOUR, node.group(groupId).getDefaultAutoDeleteTimerMs());
		assertNull("a setting a member passed on must not reach back over"
				+ " posts already held", node.cleanupDeadline(held));
		long next = node.now;
		MessageId later = node.receive(a, groupId, aKey, EPOCH,
				"later".getBytes("UTF-8"), next, 0L, SIG);
		assertEquals(Long.valueOf(next + HOUR), node.cleanupDeadline(later));

		settings(node, fromCreator, HOUR, chosen, SIG);

		assertEquals("the owner's own copy of the same setting reaches the"
						+ " posts held since it was chosen",
				Long.valueOf(arrived + HOUR), node.cleanupDeadline(held));
	}

	@Test
	public void anOlderSettingPassedOnToADeviceWithNoneDeletesNoHistory()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long arrived = node.now;
		MessageId held = node.receive(a, groupId, aKey, EPOCH,
				"history".getBytes("UTF-8"), arrived, 0L, SIG);
		node.now += DAY;

		settings(node, a, HOUR, 50L, SIG);

		assertNull(node.cleanupDeadline(held));
		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId), aKey));

		settings(node, fromCreator, 0L, 200L, SIG);

		assertEquals(0L, node.group(groupId).getDefaultAutoDeleteTimerMs());
		assertNull(node.cleanupDeadline(held));
		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId), aKey));
	}

	@Test
	public void aWaitingOwnCopyWithALongerTimerTakesTheShorterOne()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		long chosen = node.now;
		node.now += MINUTE;
		int before = node.copiesTo(a).size();
		node.manager.sendGroupPost(groupId, "mine".getBytes("UTF-8"), 0L);
		MessageId waiting = node.copiesTo(a).get(before);
		assertEquals(Long.valueOf(DAY), node.cleanupTimerDuration(waiting));

		settings(node, fromCreator, HOUR, chosen, SIG);

		assertEquals(Long.valueOf(HOUR), node.cleanupTimerDuration(waiting));
		assertNull(node.cleanupDeadline(waiting));
	}

	@Test
	public void aSettingConfirmedButNotYetAppliedIsAppliedAtTheNextStart()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long chosen = node.now;
		node.now += MINUTE;
		long arrived = node.now;
		MessageId held = node.receive(a, groupId, aKey, EPOCH,
				"held".getBytes("UTF-8"), arrived, 0L, SIG);
		String ns = GroupTrConstants.SETTINGS_NS_PREFIX
				+ org.zerionproject.core.util.StringUtils.toHexString(groupId);
		node.setGroupTimer(groupId, HOUR);
		node.putSetting(ns, GroupTrConstants.S_SETTINGS_TIMESTAMP,
				String.valueOf(chosen));
		node.putSetting(ns, GroupTrConstants.S_SETTINGS_CONFIRMED,
				String.valueOf(chosen));
		node.putSetting(ns, GroupTrConstants.S_SETTINGS_PENDING,
				chosen + ":" + HOUR);

		node.restart();

		assertEquals(Long.valueOf(arrived + HOUR), node.cleanupDeadline(held));
		assertEquals("", node.namespace(ns)
				.get(GroupTrConstants.S_SETTINGS_PENDING));
	}

	@Test
	public void twoOwnerSettingsArrivingTogetherEachCoverTheirOwnPeriod()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long first = node.now;
		node.now += MINUTE;
		long duringFirst = node.now;
		MessageId early = node.receive(a, groupId, aKey, EPOCH,
				"while the timer was on".getBytes("UTF-8"), duringFirst, 0L,
				SIG);
		node.now += DAY;
		long second = node.now;
		node.now += MINUTE;
		MessageId late = node.receive(a, groupId, aKey, EPOCH,
				"after it was switched off".getBytes("UTF-8"), node.now, 0L,
				SIG);
		node.now += MINUTE;

		settings(node, fromCreator, HOUR, first, SIG);
		settings(node, fromCreator, 0L, second, SIG);
		assertNull("nothing is touched before both settings are in",
				node.cleanupDeadline(early));
		node.runTimers();

		assertEquals(0L, node.group(groupId).getDefaultAutoDeleteTimerMs());
		assertEquals("a post from the period of the earlier setting takes"
						+ " its timer", Long.valueOf(duringFirst + HOUR),
				node.cleanupDeadline(early));
		assertNull("a post from after the timer was switched off must keep"
				+ " none", node.cleanupDeadline(late));
	}

	@Test
	public void theOwnersEarlierSettingAfterANewerOneCoversOnlyItsPeriod()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long first = node.now;
		node.now += MINUTE;
		long duringFirst = node.now;
		MessageId early = node.receive(a, groupId, aKey, EPOCH,
				"early".getBytes("UTF-8"), duringFirst, 0L, SIG);
		node.now += DAY;
		long second = node.now;
		node.now += MINUTE;
		MessageId late = node.receive(a, groupId, aKey, EPOCH,
				"late".getBytes("UTF-8"), node.now, 0L, SIG);
		settings(node, a, 0L, second, SIG);

		settings(node, fromCreator, HOUR, first, SIG);

		assertEquals("the newer setting stays in force", 0L,
				node.group(groupId).getDefaultAutoDeleteTimerMs());
		assertEquals(Long.valueOf(duringFirst + HOUR),
				node.cleanupDeadline(early));
		assertNull(node.cleanupDeadline(late));
	}

	@Test
	public void aNewerPassedOnSettingIsNotAppliedBackwardsAtTheNextStart()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		node.manager.getRecentPosts(groupId);
		long chosen = node.now;
		node.now += MINUTE;
		long arrived = node.now;
		MessageId held = node.receive(a, groupId, aKey, EPOCH,
				"held".getBytes("UTF-8"), arrived, DAY, SIG);
		node.now += MINUTE;
		settings(node, a, HOUR, chosen, SIG);

		node.restart();

		assertEquals(Long.valueOf(arrived + DAY), node.cleanupDeadline(held));
	}

	@Test
	public void aCopyOfTheOwnersEarlierSettingFromAMemberIsNotApplied()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long first = node.now;
		node.now += MINUTE;
		long arrived = node.now;
		MessageId held = node.receive(a, groupId, aKey, EPOCH,
				"held".getBytes("UTF-8"), arrived, 0L, SIG);
		node.now += MINUTE;
		settings(node, fromCreator, 0L, node.now, SIG);

		settings(node, a, HOUR, first, SIG);

		assertNull(node.cleanupDeadline(held));
	}

	@Test
	public void aMemberOnAnEarlierVersionIsNotSentAPassedOnSettingUntilItUpdates()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		node.announceMinorVersion(a, 15);
		settings(node, fromCreator, DAY, 10L, SIG);
		node.manager.getRecentPosts(groupId);
		node.sent.clear();

		node.manager.sendGroupPost(groupId, "one".getBytes("UTF-8"), 0L);
		node.receive(a, groupId, aKey, EPOCH, "theirs".getBytes("UTF-8"),
				node.now, 0L, SIG);

		assertEquals("an app that ignores a passed-on setting is sent none",
				0, recordsTo(node, a, 46L).size());

		node.versionUpdated(a, 16);

		assertEquals(1, recordsTo(node, a, 46L).size());
	}

	@Test
	public void theOwnersCopyOfASettingAlreadyHeldIsCheckedOnlyOnce()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		settings(node, fromCreator, DAY, 10L, SIG);
		int before = node.signatureChecks();

		settings(node, fromCreator, DAY, 10L, SIG);
		settings(node, a, DAY, 10L, SIG);
		settings(node, a, HOUR, 10L, SIG);

		assertEquals("a copy that teaches nothing costs no signature check",
				before, node.signatureChecks());
		assertEquals(DAY, node.group(groupId).getDefaultAutoDeleteTimerMs());
	}

	private static void later(GroupTrTestNode node, long ms) {
		node.now += ms;
		node.runDueTimers();
	}

	private static int settingsSent(GroupTrTestNode node) throws Exception {
		int count = 0;
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.body.getLong(0) == 46L) count++;
		}
		return count;
	}

	private String sentToNote(GroupTrTestNode node) {
		return node.namespace(GroupTrConstants.SETTINGS_NS_PREFIX
				+ org.zerionproject.core.util.StringUtils.toHexString(groupId))
				.get(GroupTrConstants.S_SETTINGS_SENT_TO);
	}

	private static void connected(GroupTrTestNode node, ContactId c) {
		node.manager.eventOccurred(new ContactConnectedEvent(c));
	}

	private void settings(GroupTrTestNode node, ContactId from, long timer,
			long timestamp, byte[] sig) {
		node.manager.eventOccurred(new GroupSettingsChangedEvent(from,
				groupId, timer, timestamp, sig, GroupTrTestNode.SIGNED));
	}

	private GroupTrTestNode memberDevice() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		return node;
	}

	private GroupTrTestNode ownerDevice() {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L), member(bKey, 1L));
		return node;
	}

	private static List<BdfList> recordsTo(GroupTrTestNode node, ContactId to,
			long type) throws Exception {
		List<BdfList> out = new ArrayList<>();
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(to) && s.body.getLong(0) == type) out.add(s.body);
		}
		return out;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
