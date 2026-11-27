package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.IncomingRequestInfo;
import org.zerionproject.core.api.contact.request.event.ContactRequestAddedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestExpiredEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DELIVERED_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.UNDECIDED_WINDOW_MS;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ContactRequestReceiverTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;
	private static final long DAY = 24 * 3_600_000L;

	private final File testDir = getTestDirectory();
	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final SettableClock aliceClock = new SettableClock(NOW);
	private final SettableClock bobClock = new SettableClock(NOW);
	private final FakeTorNetwork network = new FakeTorNetwork();
	private Party alice, bob;

	@Before
	public void setUp() throws Exception {
		testDir.mkdirs();
		alice = new Party(testDir, "alice", crypto, aliceClock, network);
		bob = new Party(testDir, "bob", crypto, bobClock, network);
	}

	@After
	public void tearDown() throws Exception {
		alice.close();
		bob.close();
		deleteTestDirectory(testDir);
	}

	private byte[] deliver() throws Exception {
		return deliver(false);
	}

	private byte[] deliver(boolean reconnect) throws Exception {
		ContactLink z = bob.zeroIdShared();
		bob.publish(network);
		String link = bob.links.linkString(z);
		byte[] id = alice.sender.submit(alice.codec.parse(link), link, "Bob",
				"Alice", "hi", reconnect);
		alice.sender.attempt(id);
		return id;
	}

	private boolean reAddMark(PendingContact p) throws Exception {
		org.zerionproject.core.contact.PendingContactFlags flags =
				new org.zerionproject.core.contact.PendingContactFlags(bob.db);
		return bob.db.transactionWithResult(true, txn -> flags.peek(txn,
				p, org.zerionproject.core.contact.PendingContactFlags.RE_ADD));
	}

	@Test
	public void acceptingAReconnectRequestMarksThePendingContactAsAReAdd()
			throws Exception {
		byte[] plain = deliver(false);
		assertFalse(bob.manager.getIncomingRequests().get(0).isReconnect());
		assertFalse(reAddMark(bob.manager.accept(plain, "Alice")));

		byte[] reconnect = deliver(true);
		IncomingRequestInfo shown = bob.manager.getIncomingRequests().stream()
				.filter(r -> Arrays.equals(r.getRequestId(), reconnect))
				.findFirst().get();
		assertTrue(shown.isReconnect());
		PendingContact p = bob.manager.accept(reconnect, "Alice");
		assertTrue(reAddMark(p));
		bobClock.now = NOW + 1;
		bob.manager.retryAccepted(reconnect);
		PendingContact again = pending().stream()
				.filter(pc -> pc.getId().equals(p.getId())).findFirst().get();
		assertTrue(reAddMark(again));
	}

	@Test
	public void aRefusedAddShowsTheRequestAsAlreadyAContact()
			throws Exception {
		byte[] id = deliver();
		PendingContact p = bob.manager.accept(id, "Alice");
		bob.manager.alreadyContact(p.getId(), "Alice");
		IncomingRequest r = bob.incoming().get(0);
		assertEquals(IncomingRequest.STATE_ALREADY_CONTACT, r.getState());
		assertEquals("Alice", r.getName());
		assertNull(r.getNote());
		assertNull(r.getPendingContactId());
		assertTrue(bob.bus.of(ContactRequestAddedEvent.class).isEmpty());
		bob.manager.dismissIncoming(id);
		assertTrue(bob.incoming().isEmpty());
	}

	private List<PendingContact> pending() throws Exception {
		return new ArrayList<>(bob.db.transactionWithResult(true,
				bob.db::getPendingContacts));
	}

	@Test
	public void anUndecidedRequestIsDeletedAfterThirtyDaysOnTheReceiversClock()
			throws Exception {
		deliver();
		bobClock.now = NOW + UNDECIDED_WINDOW_MS - 1;
		bob.receiver.maintain();
		assertEquals(1, bob.incoming().size());
		bobClock.now = NOW + UNDECIDED_WINDOW_MS;
		bob.receiver.maintain();
		assertTrue(bob.incoming().isEmpty());
		assertEquals(1, bob.bus.of(ContactRequestExpiredEvent.class).size());
	}

	@Test
	public void tryAgainExtendsByTwoDaysButNeverPastThirtyTwo()
			throws Exception {
		byte[] id = deliver();
		bobClock.now = NOW + 29 * DAY;
		bob.manager.accept(id, "Alice");
		assertEquals(NOW + 29 * DAY + 2 * DAY, pending().get(0).getExpiry());
		bobClock.now = NOW + 31 * DAY;
		bob.manager.retryAccepted(id);
		assertEquals(1, pending().size());
		assertEquals(NOW + DELIVERED_WINDOW_MS, pending().get(0).getExpiry());
		assertEquals("Alice", pending().get(0).getAlias());
		org.zerionproject.core.api.contact.PendingContactId pid =
				pending().get(0).getId();
		byte[][] keys = bob.db.transactionWithResult(true, txn ->
				bob.db.getPendingContactOurKeys(txn, pid));
		assertEquals(1216, keys[0].length);
		bobClock.now = NOW + DELIVERED_WINDOW_MS;
		try {
			bob.manager.retryAccepted(id);
			fail();
		} catch (IllegalStateException expected) {
		}
		bob.receiver.maintain();
		assertTrue(bob.incoming().isEmpty());
		assertTrue(pending().isEmpty());
	}

	@Test
	public void aDistantSenderClockIsKeptAndFlagged() throws Exception {
		aliceClock.now = NOW + 3 * DAY;
		deliver();
		List<IncomingRequestInfo> in = bob.manager.getIncomingRequests();
		assertEquals(1, in.size());
		assertTrue(in.get(0).isClockDifferent());
		assertEquals(NOW, in.get(0).getReceivedAt());
		assertEquals(1, bob.manager.getUnopenedCount());
		bob.manager.markOpened(in.get(0).getRequestId());
		assertEquals(0, bob.manager.getUnopenedCount());
		assertFalse(bob.manager.getIncomingRequests().isEmpty());
	}

	@Test
	public void acceptingADeliveredRequestThroughTheZeroIdRotatesTheZeroId()
			throws Exception {
		byte[] id = deliver();
		ContactLink before = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		String oldLink = bob.manager.getZeroIdLink();
		bob.manager.accept(id, "Alice");
		ContactLink after = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		assertFalse(Arrays.equals(before.getLinkId(), after.getLinkId()));
		assertFalse(oldLink.equals(bob.manager.getZeroIdLink()));
		assertEquals("the new ZeroID is not published until it is shared",
				0, after.getLastSharedAt());
		ContactLink kept = bob.db.transactionWithResult(true,
				txn -> bob.links.getLink(txn, before.getLinkId()));
		assertEquals(ContactLink.STATE_RETIRED, kept.getState());
		bobClock.now = NOW + DAY;
		bob.manager.retryAccepted(id);
		assertEquals(1, pending().size());
	}

	@Test
	public void acceptingADeliveredRequestThroughAPermanentLinkRotatesNothing()
			throws Exception {
		bob.zeroIdShared();
		ContactLink zeroId = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		ContactLinkInfo support = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		String link = support.getLink();
		byte[] id = alice.sender.submit(alice.codec.parse(link), link, "Bob",
				"Alice", null);
		alice.sender.attempt(id);
		assertEquals(1, bob.incoming().size());
		bob.manager.accept(id, "Alice");
		ContactLink after = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		assertTrue(Arrays.equals(zeroId.getLinkId(), after.getLinkId()));
		ContactLink kept = bob.db.transactionWithResult(true,
				txn -> bob.links.getLink(txn, support.getLinkId()));
		assertEquals(ContactLink.STATE_ACTIVE, kept.getState());
		assertEquals(1, pending().size());
	}

	@Test
	public void addingTheContactRemovesTheRequestAndNamesTheNewContact()
			throws Exception {
		byte[] id = deliver();
		PendingContact p = bob.manager.accept(id, "Alice");
		bob.db.transaction(false, txn -> bob.manager
				.convertingPendingContact(txn, p.getId(), new ContactId(5)));
		assertTrue(bob.manager.getIncomingRequests().isEmpty());
		List<ContactRequestAddedEvent> added =
				bob.bus.of(ContactRequestAddedEvent.class);
		assertEquals(1, added.size());
		assertFalse(added.get(0).isOutgoing());
		assertArrayEquals(id, added.get(0).getRequestId());
		assertEquals(new ContactId(5), added.get(0).getContactId());
	}
}
