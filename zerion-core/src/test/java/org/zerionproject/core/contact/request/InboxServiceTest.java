package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.contact.request.event.ContactRequestReceivedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestWithdrawnEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.contact.request.ContactRequestSender.AttemptResult;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DELIVERED_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_UNDECIDED_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RENDEZVOUS_MODE_DIAL_ONLY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RENDEZVOUS_MODE_PUBLISH_ONLY;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_CANCELLED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_DELIVERED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_QUEUED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class InboxServiceTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;

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

	private String bobsZeroId() throws Exception {
		ContactLink z = bob.zeroIdShared();
		bob.publish(network);
		return bob.links.linkString(z);
	}

	private byte[] send(String link) throws Exception {
		InviteLink parsed = alice.codec.parse(link);
		return alice.sender.submit(parsed, link, "Bob", "Alice", "hello");
	}

	private PendingContact pending(Party p) throws Exception {
		List<PendingContact> all = new java.util.ArrayList<>(
				p.db.transactionWithResult(true, p.db::getPendingContacts));
		return all.isEmpty() ? null : all.get(0);
	}

	@Test
	public void aRequestIsDeliveredSealedAndStoredOnce() throws Exception {
		String link = bobsZeroId();
		byte[] id = send(link);
		assertNull("no pending row while queued", pending(alice));
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		OutgoingRequest out = alice.outgoing(id);
		assertEquals(STATE_DELIVERED, out.getState());
		assertEquals(NOW, out.getDeliveredAt());
		PendingContact p = pending(alice);
		assertNotNull(p);
		assertEquals(RENDEZVOUS_MODE_DIAL_ONLY, p.getRendezvousMode());
		assertEquals(NOW + DELIVERED_WINDOW_MS, p.getExpiry());
		assertEquals(p.getId(), out.getPendingContactId());
		byte[][] snapshot = alice.db.transactionWithResult(true,
				txn -> alice.db.getPendingContactOurKeys(txn, p.getId()));
		assertArrayEquals(out.getOneTimePublicKey(), snapshot[0]);
		List<IncomingRequest> in = bob.incoming();
		assertEquals(1, in.size());
		IncomingRequest r = in.get(0);
		assertArrayEquals(id, r.getRequestId());
		assertEquals("Alice", r.getName());
		assertEquals("hello", r.getNote());
		assertEquals(NOW, r.getReceivedAt());
		assertEquals(NOW / 3_600_000 * 3_600_000, r.getSenderTimestamp());
		assertEquals(1, bob.bus.of(ContactRequestReceivedEvent.class).size());
		assertTrue(network.clientKeysForgotten.get() >= 1);

		resend(id);
		assertEquals(1, bob.incoming().size());
		assertEquals(1, bob.bus.of(ContactRequestReceivedEvent.class).size());
	}

	private void resend(byte[] id) throws Exception {
		OutgoingRequest r = alice.outgoing(id);
		alice.db.transaction(false, txn -> {
			alice.db.updateOutgoingRequest(txn, new OutgoingRequest(
					r.getRequestId(), r.getLocalAuthorId(), r.getTargetLink(),
					r.getAlias(), r.getShownName(), r.getNote(),
					r.getOneTimePublicKey(), r.getOneTimePrivateKey(),
					r.getCreatedAt(), STATE_QUEUED, 0, NOW, 0, null, 0));
			if (r.getPendingContactId() != null) {
				alice.db.removePendingContact(txn, r.getPendingContactId());
			}
		});
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
	}

	@Test
	public void declineAndBlockAreSilentAndBlocksStayWithTheLink()
			throws Exception {
		String link = bobsZeroId();
		byte[] first = send(link);
		alice.sender.attempt(first);
		bob.manager.decline(first);
		assertTrue(bob.incoming().isEmpty());
		resend(first);
		assertTrue("a declined request is not shown again",
				bob.incoming().isEmpty());

		alice.sender.cancel(first);
		alice.sender.attempt(first);
		alice.sender.dismiss(first);
		byte[] second = send(link);
		alice.sender.attempt(second);
		assertEquals(1, bob.incoming().size());
		bob.manager.block(second);
		alice.sender.cancel(second);
		alice.sender.attempt(second);
		alice.sender.dismiss(second);
		byte[] third = send(link);
		assertEquals("a blocked sender is acknowledged",
				AttemptResult.DELIVERED, alice.sender.attempt(third));
		assertTrue(bob.incoming().isEmpty());

		alice.sender.cancel(third);
		alice.sender.attempt(third);
		alice.sender.dismiss(third);
		String newLink = bob.resetAndShare(network);
		byte[] fourth = send(newLink);
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(fourth));
		assertEquals("blocks do not carry across a reset", 1,
				bob.incoming().size());
	}

	@Test
	public void aNewerRequestFromTheSameAccountReplacesTheUndecidedOne()
			throws Exception {
		String link = bobsZeroId();
		byte[] first = send(link);
		alice.sender.attempt(first);
		alice.db.transaction(false, txn ->
				alice.db.removeOutgoingRequest(txn, first));
		PendingContact p = pending(alice);
		alice.db.transaction(false, txn ->
				alice.db.removePendingContact(txn, p.getId()));
		byte[] second = send(link);
		alice.sender.attempt(second);
		List<IncomingRequest> in = bob.incoming();
		assertEquals(1, in.size());
		assertArrayEquals(second, in.get(0).getRequestId());
	}

	@Test
	public void aWithdrawRemovesAnUndecidedRequest() throws Exception {
		String link = bobsZeroId();
		byte[] id = send(link);
		alice.sender.attempt(id);
		alice.sender.cancel(id);
		assertNull("the pending row goes at cancel", pending(alice));
		assertNull(alice.outgoing(id).getOneTimePrivateKey());
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		assertEquals(STATE_CANCELLED, alice.outgoing(id).getState());
		assertTrue(bob.incoming().isEmpty());
		assertEquals(1, bob.bus.of(ContactRequestWithdrawnEvent.class).size());
	}

	@Test
	public void aWithdrawAfterAcceptRemovesBobsPendingRow() throws Exception {
		String link = bobsZeroId();
		byte[] id = send(link);
		alice.sender.attempt(id);
		PendingContact accepted = bob.manager.accept(id, "Alice");
		assertEquals(RENDEZVOUS_MODE_PUBLISH_ONLY,
				accepted.getRendezvousMode());
		assertEquals(NOW + 2L * 24 * 3_600_000, accepted.getExpiry());
		assertNotNull(pending(bob));
		IncomingRequest r = bob.incoming().get(0);
		assertNull("name and note go at accept", r.getName());
		assertNull(r.getNote());
		alice.sender.cancel(id);
		alice.sender.attempt(id);
		assertNull(pending(bob));
		assertEquals(IncomingRequest.STATE_WITHDRAWN,
				bob.incoming().get(0).getState());
	}

	@Test
	public void aWithdrawForAnUnknownRequestIsAcknowledged()
			throws Exception {
		String link = bobsZeroId();
		byte[] id = send(link);
		OutgoingRequest queued = alice.outgoing(id);
		alice.db.transaction(false, txn ->
				alice.db.updateOutgoingRequest(txn, queued.withdrawing(NOW)));
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		assertTrue(bob.incoming().isEmpty());
	}

	@Test
	public void aFullInboxCountsAsAFailedAttempt() throws Exception {
		String link = bobsZeroId();
		ContactLink z = bob.links().get(0);
		bob.db.transaction(false, txn -> {
			for (int i = 0; i < MAX_UNDECIDED_ZERO_ID; i++) {
				byte[] otl = getRandomBytes(65);
				otl[0] = 1;
				bob.db.addIncomingRequest(txn, new IncomingRequest(
						getRandomBytes(16), z.getLinkId(), otl, "x", null,
						getRandomBytes(32), NOW, NOW,
						IncomingRequest.STATE_RECEIVED, 0, null));
			}
		});
		byte[] id = send(link);
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(STATE_QUEUED, r.getState());
		assertEquals(1, r.getAttempts());
		assertTrue(r.getNextAttemptAt() >= NOW + 48_000);
		assertTrue(r.getNextAttemptAt() <= NOW + 72_000);
	}

	@Test
	public void aRaisedLevelIsPaidForOnceAndThenDelivered() throws Exception {
		String link = bobsZeroId();
		ContactLink z = bob.links().get(0);
		InboxPuzzle puzzle = bob.inbox.getPuzzle(z.getLinkId());
		for (int i = 0; i < 10; i++) {
			puzzle.admit(new ContactRequestRecords.Solution(NOW / 600_000, 3,
					getRandomBytes(8)), NOW);
		}
		assertEquals(1, puzzle.getLevel(NOW));
		byte[] id = send(link);
		int before = network.connections.get();
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		assertEquals("HELLO, then a solved reconnect", 2,
				network.connections.get() - before);
		assertEquals(1, bob.incoming().size());
	}

	private void raise(InboxPuzzle puzzle, long at) {
		for (int i = 0; i < 10; i++) {
			puzzle.admit(new ContactRequestRecords.Solution(at / 600_000, 3,
					getRandomBytes(8)), at);
		}
		assertEquals(1, puzzle.getLevel(at));
	}

	@Test
	public void theSenderPaysForAtMostTwoSolutionsAnHour() throws Exception {
		String link = bobsZeroId();
		ContactLink z = bob.links().get(0);
		InboxPuzzle puzzle = bob.inbox.getPuzzle(z.getLinkId());
		bob.db.transaction(false, txn -> {
			for (int i = 0; i < MAX_UNDECIDED_ZERO_ID; i++) {
				byte[] otl = getRandomBytes(65);
				otl[0] = 1;
				bob.db.addIncomingRequest(txn, new IncomingRequest(
						getRandomBytes(16), z.getLinkId(), otl, "x", null,
						getRandomBytes(32), NOW, NOW,
						IncomingRequest.STATE_RECEIVED, 0, null));
			}
		});
		byte[] id = send(link);
		raise(puzzle, NOW);
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		clock.now = NOW + 21 * 60_000;
		raise(puzzle, clock.now);
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		clock.now = NOW + 42 * 60_000;
		raise(puzzle, clock.now);
		assertEquals(AttemptResult.WAITING, alice.sender.attempt(id));
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(2, r.getAttempts());
		assertEquals(NOW + 3_600_000, r.getNextAttemptAt());
	}

	@Test
	public void busySlotsAreRetriedThreeTimesThenCountAsOneFailure()
			throws Exception {
		String link = bobsZeroId();
		ContactLink z = bob.links().get(0);
		InboxSlots slots = bob.inbox.getSlots(z.getLinkId());
		for (int i = 0; i < 3; i++) slots.acquireSolved(new Object());
		byte[] id = send(link);
		int before = network.connections.get();
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		assertEquals(4, network.connections.get() - before);
		assertTrue(clock.now >= NOW + 15_000);
		assertTrue(clock.now <= NOW + 90_000);
		assertEquals(1, alice.outgoing(id).getAttempts());
	}

	@Test
	public void aSubstitutedInboxIsNotDelivered() throws Exception {
		String bobs = bobsZeroId();
		ContactLink carol = alice.zeroIdShared();
		alice.publish(network);
		String substituted = alice.codec.format(
				alice.codec.parse(bobs).getBody(),
				alice.links.inboxOnion(carol));
		byte[] id = alice.sender.submit(alice.codec.parse(substituted),
				substituted, "Bob", "Alice", null);
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		assertTrue(bob.incoming().isEmpty());
	}

	@Test
	public void aSenderKeepsOneRequestPerLink() throws Exception {
		String link = bobsZeroId();
		send(link);
		try {
			send(link);
			throw new AssertionError();
		} catch (org.zerionproject.core.api.contact.request
				.OutgoingRequestLimitException expected) {
		}
	}

	@Test
	public void ownLinksAndOwnInboxesAreRecognised() throws Exception {
		String link = bobsZeroId();
		try {
			bob.manager.parseLink(link);
			throw new AssertionError();
		} catch (org.zerionproject.core.api.contact.request.OwnLinkException
				expected) {
		}
		String foreignBodyOwnInbox = bob.codec.format(
				alice.codec.parse(alice.manager.getZeroIdLink()).getBody(),
				bob.codec.parse(link).getInbox());
		try {
			bob.manager.parseLink(foreignBodyOwnInbox);
			throw new AssertionError();
		} catch (org.zerionproject.core.api.contact.request.OwnLinkException
				expected) {
		}
		assertTrue(Arrays.equals(alice.manager.parseLink(link).getBody(),
				bob.codec.parse(link).getBody()));
	}
}
