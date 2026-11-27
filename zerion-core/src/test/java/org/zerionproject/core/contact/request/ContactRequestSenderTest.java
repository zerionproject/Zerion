package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.contact.request.OutgoingRequestLimitException;
import org.zerionproject.core.api.contact.request.event.ContactRequestAcceptedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestAddedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestExpiredEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DELIVERED_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_OPEN_OUTGOING;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SEND_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_ADDED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_CANCELLED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_DELIVERED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_NOT_DELIVERED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_NO_ANSWER;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_QUEUED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ContactRequestSenderTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;
	private static final long MINUTE = 60_000, HOUR = 3_600_000;

	private final File testDir = getTestDirectory();
	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final SettableClock clock = new SettableClock(NOW);
	private final FakeTorNetwork network = new FakeTorNetwork();
	private Party alice, bob;

	@Before
	public void setUp() throws Exception {
		testDir.mkdirs();
		alice = new Party(testDir, "alice", crypto, clock, network);
		bob = new Party(testDir, "bob", crypto, clock, network);
	}

	@After
	public void tearDown() throws Exception {
		alice.close();
		bob.close();
		deleteTestDirectory(testDir);
	}

	@Test
	public void theRetryScheduleSlowsFromMinutesToHoursWithJitter() {
		long[] first = {0, MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE,
				20 * MINUTE, 30 * MINUTE};
		for (int i = 0; i < first.length; i++) {
			assertEquals(first[i], RetrySchedule.delay(NOW, i, NOW));
		}
		assertEquals(HOUR, RetrySchedule.delay(NOW, 7, NOW + 71 * HOUR));
		assertEquals(4 * HOUR, RetrySchedule.delay(NOW, 7, NOW + 72 * HOUR));
		Random random = new Random(3);
		for (int i = 0; i < 1000; i++) {
			long next = RetrySchedule.next(NOW, 3, NOW, random);
			assertTrue(next >= NOW + 4 * MINUTE);
			assertTrue(next <= NOW + 6 * MINUTE);
		}
	}

	private String bobsLink() throws Exception {
		ContactLink z = bob.zeroIdShared();
		bob.publish(network);
		return bob.links.linkString(z);
	}

	private byte[] send(String link) throws Exception {
		return send(link, false);
	}

	private byte[] send(String link, boolean reconnect) throws Exception {
		return alice.sender.submit(alice.codec.parse(link), link, "Bob",
				"Alice", null, reconnect);
	}

	private PendingContact pendingOf(byte[] id) throws Exception {
		PendingContactId p = alice.outgoing(id).getPendingContactId();
		for (PendingContact pc : pending()) {
			if (pc.getId().equals(p)) return pc;
		}
		return null;
	}

	private boolean reAddMark(PendingContact p) throws Exception {
		org.zerionproject.core.contact.PendingContactFlags flags =
				new org.zerionproject.core.contact.PendingContactFlags(
						alice.db);
		return alice.db.transactionWithResult(true, txn -> flags.peek(txn,
				p, org.zerionproject.core.contact.PendingContactFlags.RE_ADD));
	}

	@Test
	public void aReconnectRequestMarksItsPendingContactAsAReAdd()
			throws Exception {
		String link = bobsLink();
		byte[] plain = send(link);
		alice.sender.attempt(plain);
		assertFalse(alice.db.transactionWithResult(true,
				txn -> alice.sender.isReconnect(txn, plain)));
		assertFalse(reAddMark(pendingOf(plain)));
		assertFalse(bob.db.transactionWithResult(true,
				txn -> bob.receiver.isReconnect(txn, plain)));

		alice.sender.cancel(plain);
		byte[] reconnect = send(bobsLink(), true);
		alice.sender.attempt(reconnect);
		assertTrue(alice.db.transactionWithResult(true,
				txn -> alice.sender.isReconnect(txn, reconnect)));
		assertTrue(reAddMark(pendingOf(reconnect)));
		assertTrue(bob.db.transactionWithResult(true,
				txn -> bob.receiver.isReconnect(txn, reconnect)));
		assertTrue(alice.manager.getOutgoingRequests().stream().anyMatch(
				r -> java.util.Arrays.equals(r.getRequestId(), reconnect)
						&& r.isReconnect()));
	}

	@Test
	public void aRefusedAddEndsTheRequestAsAlreadyAContact()
			throws Exception {
		byte[] id = send(bobsLink());
		alice.sender.attempt(id);
		PendingContactId p = alice.outgoing(id).getPendingContactId();
		alice.manager.alreadyContact(p, null);
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(OutgoingRequest.STATE_ALREADY_CONTACT, r.getState());
		assertNull(r.getPendingContactId());
		assertNull(r.getOneTimePrivateKey());
		assertTrue(alice.bus.of(ContactRequestAddedEvent.class).isEmpty());
		alice.sender.dismiss(id);
		assertNull(alice.outgoing(id));
	}

	private List<PendingContact> pending() throws Exception {
		return new ArrayList<>(alice.db.transactionWithResult(true,
				alice.db::getPendingContacts));
	}

	@Test
	public void nothingIsTriedWhileTorIsDown() throws Exception {
		String link = bobsLink();
		byte[] id = send(link);
		alice.adapter.connected = false;
		assertEquals(ContactRequestSender.AttemptResult.SKIPPED,
				alice.sender.attempt(id));
		assertEquals(0, alice.outgoing(id).getAttempts());
		assertEquals(0, network.connections.get());
	}

	@Test
	public void torComingUpRunsAnOverdueAttemptFiveToSixtySecondsLater() {
		alice.scheduler.runOneShots = false;
		for (int i = 0; i < 50; i++) {
			alice.sender.eventOccurred(new TransportActiveEvent(TorConstants.ID));
		}
		assertEquals(50, alice.scheduler.delaysMs.size());
		for (long d : alice.scheduler.delaysMs) {
			assertTrue(d >= 5_000);
			assertTrue(d <= 60_000);
		}
	}

	@Test
	public void anUndeliveredRequestEndsAfterThirtyDaysWithoutKeys()
			throws Exception {
		String link = bobsLink();
		byte[] id = send(link);
		bob.adapter.connected = false;
		bob.publish(network);
		assertEquals(ContactRequestSender.AttemptResult.FAILED,
				alice.sender.attempt(id));
		assertTrue(pending().isEmpty());
		clock.now = NOW + SEND_WINDOW_MS - 1;
		alice.sender.tick();
		assertEquals(STATE_QUEUED, alice.outgoing(id).getState());
		clock.now = NOW + SEND_WINDOW_MS;
		alice.sender.tick();
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(STATE_NOT_DELIVERED, r.getState());
		assertNull(r.getOneTimePrivateKey());
		assertEquals(1, alice.bus.of(ContactRequestExpiredEvent.class).size());
	}

	@Test
	public void aDeliveredRequestEndsWithNoAnswerOnItsOwnClock()
			throws Exception {
		String link = bobsLink();
		byte[] id = send(link);
		alice.sender.attempt(id);
		assertEquals(1, pending().size());
		clock.now = NOW + DELIVERED_WINDOW_MS - 1;
		alice.sender.tick();
		assertEquals(STATE_DELIVERED, alice.outgoing(id).getState());
		clock.now = NOW + DELIVERED_WINDOW_MS;
		alice.sender.tick();
		assertEquals(STATE_NO_ANSWER, alice.outgoing(id).getState());
		assertTrue(pending().isEmpty());
		assertNull(alice.outgoing(id).getOneTimePrivateKey());
	}

	@Test
	public void cancellingAQueuedRequestEndsItWithoutKeys() throws Exception {
		String link = bobsLink();
		byte[] id = send(link);
		alice.sender.cancel(id);
		assertEquals(STATE_CANCELLED, alice.outgoing(id).getState());
		assertNull(alice.outgoing(id).getOneTimePrivateKey());
	}

	@Test
	public void aWithdrawIsRetriedUntilThirtyTwoDaysAfterDelivery()
			throws Exception {
		String link = bobsLink();
		byte[] id = send(link);
		alice.sender.attempt(id);
		bob.adapter.connected = false;
		bob.publish(network);
		clock.now = NOW + HOUR;
		alice.sender.cancel(id);
		assertEquals(ContactRequestSender.AttemptResult.FAILED,
				alice.sender.attempt(id));
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(OutgoingRequest.STATE_WITHDRAWING, r.getState());
		assertEquals(1, r.getAttempts());
		clock.now = NOW + DELIVERED_WINDOW_MS;
		alice.sender.tick();
		assertEquals(STATE_CANCELLED, alice.outgoing(id).getState());
	}

	@Test
	public void addingTheContactErasesTheOneTimeKeys() throws Exception {
		String link = bobsLink();
		byte[] id = send(link);
		alice.sender.attempt(id);
		PendingContactId p = alice.outgoing(id).getPendingContactId();
		alice.db.transaction(false, txn -> alice.sender
				.convertingPendingContact(txn, p, new ContactId(7)));
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(STATE_ADDED, r.getState());
		assertNull(r.getOneTimePrivateKey());
		assertNull(r.getOneTimePublicKey());
		assertEquals(1, alice.bus.of(ContactRequestAcceptedEvent.class).size());
		List<ContactRequestAddedEvent> added =
				alice.bus.of(ContactRequestAddedEvent.class);
		assertEquals(1, added.size());
		assertTrue(added.get(0).isOutgoing());
		assertArrayEquals(id, added.get(0).getRequestId());
		assertEquals(new ContactId(7), added.get(0).getContactId());
	}

	@Test
	public void atMostTwentyOpenRequests() throws Exception {
		for (int i = 0; i < MAX_OPEN_OUTGOING; i++) {
			Party other = new Party(testDir, "p" + i, crypto, clock, network);
			try {
				ContactLink z = other.zeroIdShared();
				send(other.links.linkString(z));
			} finally {
				other.close();
			}
		}
		try {
			send(bobsLink());
			fail();
		} catch (OutgoingRequestLimitException expected) {
		}
	}
}
