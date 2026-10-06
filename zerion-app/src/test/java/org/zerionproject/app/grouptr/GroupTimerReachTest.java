package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.Transaction;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupTimerReachTest {

	private static final long EPOCH = 5L;
	private static final long DAY = 24L * 60L * 60L * 1000L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] aKey = key((byte) 2);
	private final byte[] bKey = key((byte) 3);

	@Test
	public void aTimerSetUnderAnEarlierVersionIsDatedAndSentAtTheFirstStart()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.announceMinorVersion(b, 7);
		node.setGroupTimer(groupId, DAY);
		assertNull(node.settingsTimestamp(groupId));

		node.manager.onDatabaseOpened(new Transaction(null, false));

		BdfList record = recordTo(node, a, 46L);
		assertNotNull("the timer set under the earlier version was not sent"
				+ " to a member that takes it", record);
		assertEquals(DAY, (long) record.getLong(2));
		assertNull("a member on the earlier version was sent the record",
				recordTo(node, b, 46L));
		Long dated = node.settingsTimestamp(groupId);
		assertNotNull(dated);
		assertEquals(dated, record.getLong(3));
		node.sent.clear();
		node.manager.onDatabaseOpened(new Transaction(null, false));
		assertTrue("the timer was sent again at the next start",
				node.sent.isEmpty());
	}

	@Test
	public void aMemberThatUpgradesIsSentTheTimerInForce() throws Exception {
		GroupTrTestNode node = creatorDevice();
		node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.announceMinorVersion(b, 7);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		assertNull(recordTo(node, b, 46L));
		assertEquals(1, node.manager.countMembersOnOlderVersion(groupId));
		long chosen = node.settingsTimestamp(groupId);
		node.sent.clear();
		node.now += DAY;

		node.versionUpdated(b, 8);

		BdfList record = recordTo(node, b, 46L);
		assertNotNull("the member that upgraded was not sent the timer",
				record);
		assertEquals(DAY, (long) record.getLong(2));
		assertEquals("the record is signed over the time the timer was"
				+ " chosen, so every copy orders the same", chosen,
				(long) record.getLong(3));
		assertEquals(0, node.manager.countMembersOnOlderVersion(groupId));
	}

	@Test
	public void aMemberWhoseVersionBecomesKnownIsSentTheTimerInForce()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.announceMinorVersion(b, -1);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		assertNull(recordTo(node, b, 46L));
		node.sent.clear();

		node.versionUpdated(b, 8);

		assertNotNull("the member whose version became known was not sent"
				+ " the timer", recordTo(node, b, 46L));
	}

	@Test
	public void anEarlierVersionAnnouncedLaterSendsNothing()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.announceMinorVersion(b, 7);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		node.sent.clear();

		node.versionUpdated(b, 7);

		assertTrue(node.sent.isEmpty());
	}

	@Test
	public void aGroupWithoutATimerSendsNothingAtTheFirstStart()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		node.addContact(aKey);

		node.manager.onDatabaseOpened(new Transaction(null, false));

		assertTrue(node.sent.isEmpty());
		assertNull(node.settingsTimestamp(groupId));
	}

	private GroupTrTestNode creatorDevice() {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L), member(bKey, 1L));
		return node;
	}

	private static BdfList recordTo(GroupTrTestNode node, ContactId to,
			long type) throws Exception {
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(to) && s.body.getLong(0) == type) return s.body;
		}
		return null;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
