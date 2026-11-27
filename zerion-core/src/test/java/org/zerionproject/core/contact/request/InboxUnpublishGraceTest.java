package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.EPOCH_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.HELLO_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KEY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RECORD_HEADER_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_KEY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SOLUTION;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.UNPUBLISH_GRACE_MS;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class InboxUnpublishGraceTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;
	private static final int UNTIL_KEY =
			2 * RECORD_HEADER_BYTES + HELLO_BYTES + KEY_BYTES;

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

	private interface Step {
		void run() throws Exception;
	}

	private void afterKey(Step step, AtomicReference<Throwable> failure) {
		network.afterReadingBytes = UNTIL_KEY;
		network.afterReading = () -> {
			try {
				step.run();
			} catch (Throwable t) {
				failure.set(t);
			}
		};
	}

	private byte[] submit(String link) throws Exception {
		return alice.sender.submit(alice.codec.parse(link), link, "Bob",
				"Alice", "hi");
	}

	private static boolean cleared(InboxSession.Inbox inbox) {
		byte[] k = ((HybridAgreementPrivateKey) inbox.linkKeys.getPrivate())
				.getEncoded();
		for (byte b : k) if (b != 0) return false;
		return true;
	}

	private ContactLink find(byte[] linkId) throws Exception {
		return bob.db.transactionWithResult(true,
				txn -> bob.links.getLink(txn, linkId));
	}

	private boolean reachable(ContactLink l) {
		return network.isReachable(bob.links.inboxOnion(l));
	}

	@Test
	public void aRequestAdmittedBeforeItsLinkIsRemovedIsStoredAndAcked()
			throws Exception {
		ContactLinkInfo support = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		ContactLink link = find(support.getLinkId());
		InboxSession.Inbox inbox = bob.inbox.getInbox(link.getLinkId());
		assertNotNull(inbox);
		byte[] id = submit(support.getLink());
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicReference<Boolean> keptDuringRemoval = new AtomicReference<>();
		afterKey(() -> {
			bob.manager.withdrawPermanentLink(support.getLinkId());
			bob.publish(network);
			assertFalse(reachable(link));
			keptDuringRemoval.set(!cleared(inbox));
		}, failure);
		assertEquals(ContactRequestSender.AttemptResult.DELIVERED,
				alice.sender.attempt(id));
		assertNull(failure.get());
		assertTrue("the key outlives the unpublish while the sender is busy",
				keptDuringRemoval.get());
		List<IncomingRequest> incoming = bob.incoming();
		assertEquals(1, incoming.size());
		assertArrayEquals(link.getLinkId(), incoming.get(0).getLinkId());
		assertEquals(OutgoingRequest.STATE_DELIVERED,
				alice.outgoing(id).getState());
		ContactLink kept = find(link.getLinkId());
		assertNotNull("the removed link stays for its request", kept);
		assertEquals(ContactLink.STATE_WITHDRAWN, kept.getState());
		await("the key is cleared once the session ends",
				() -> cleared(inbox));
		await("the hold ends with the session",
				() -> !bob.links.isHeld(link.getLinkId()));
		bob.manager.accept(id, "Alice");
		assertEquals(1, bob.db.transactionWithResult(true,
				bob.db::getPendingContacts).size());
	}

	@Test
	public void aRequestAdmittedBeforeAZeroIdResetIsStoredAndAcked()
			throws Exception {
		ContactLink z = bob.zeroIdShared();
		bob.publish(network);
		InboxSession.Inbox inbox = bob.inbox.getInbox(z.getLinkId());
		assertNotNull(inbox);
		byte[] id = submit(bob.links.linkString(z));
		AtomicReference<Throwable> failure = new AtomicReference<>();
		afterKey(() -> {
			bob.manager.resetZeroId();
			bob.publish(network);
			assertFalse(reachable(z));
		}, failure);
		assertEquals(ContactRequestSender.AttemptResult.DELIVERED,
				alice.sender.attempt(id));
		assertNull(failure.get());
		assertEquals(1, bob.incoming().size());
		assertEquals(ContactLink.STATE_RETIRED, find(z.getLinkId()).getState());
		await("the key is cleared once the session ends",
				() -> cleared(inbox));
	}

	@Test
	public void aNewConnectionAfterRemovalIsRefused() throws Exception {
		ContactLinkInfo support = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		ContactLink link = find(support.getLinkId());
		InboxSession.Inbox inbox = bob.inbox.getInbox(link.getLinkId());
		assertNotNull(inbox);
		int port = network.listenersByOnion.get(bob.links.inboxOnion(link))
				.server.getLocalPort();
		bob.manager.withdrawPermanentLink(support.getLinkId());
		bob.publish(network);
		assertTrue("nobody was admitted, so the key goes at once",
				cleared(inbox));
		assertNull("the link row is gone", find(link.getLinkId()));
		byte[] id = submit(support.getLink());
		assertNotEquals(ContactRequestSender.AttemptResult.DELIVERED,
				alice.sender.attempt(id));
		assertTrue(bob.incoming().isEmpty());
		try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
			fail("the listener still accepts");
		} catch (IOException expected) {
		}
	}

	@Test
	public void anAdmittedSessionThatStallsLosesTheKeyAfterTheGrace()
			throws Exception {
		ContactLinkInfo support = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		ContactLink link = find(support.getLinkId());
		InboxSession.Inbox inbox = bob.inbox.getInbox(link.getLinkId());
		assertNotNull(inbox);
		int port = network.listenersByOnion.get(bob.links.inboxOnion(link))
				.server.getLocalPort();
		try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
			s.setSoTimeout(10_000);
			InputStream in = s.getInputStream();
			OutputStream out = s.getOutputStream();
			readFully(in, new byte[RECORD_HEADER_BYTES + HELLO_BYTES]);
			ContactRequestRecords.write(out, TYPE_SOLUTION,
					new ContactRequestRecords.Solution(NOW / EPOCH_MS, 0,
							getRandomBytes(8)).encode());
			byte[] keyHeader = new byte[RECORD_HEADER_BYTES];
			readFully(in, keyHeader);
			assertEquals(TYPE_KEY, keyHeader[1]);
			readFully(in, new byte[KEY_BYTES]);
			await("the session is admitted",
					() -> bob.links.isHeld(link.getLinkId()));
			bob.scheduler.runOneShots = false;
			bob.scheduler.heldOneShots.clear();
			bob.manager.withdrawPermanentLink(support.getLinkId());
			bob.publish(network);
			assertFalse(reachable(link));
			assertFalse("kept while the admitted sender may still send",
					cleared(inbox));
			assertNotNull("the row is held for the admitted sender",
					find(link.getLinkId()));
			assertTrue(bob.scheduler.delaysMs.contains(UNPUBLISH_GRACE_MS));
			s.setSoTimeout(500);
			try {
				int read = in.read();
				fail("closed before the grace ended: " + read);
			} catch (SocketTimeoutException stillOpen) {
			}
			bob.scheduler.runHeld(UNPUBLISH_GRACE_MS);
			await("the key is cleared when the grace ends",
					() -> cleared(inbox));
			s.setSoTimeout(10_000);
			assertEquals("the stalled session is closed", -1, in.read());
			await("the hold is released",
					() -> !bob.links.isHeld(link.getLinkId()));
			await("the unneeded removed link is deleted",
					() -> find(link.getLinkId()) == null);
		}
	}

	private Socket admit(ContactLink link) throws Exception {
		int port = network.listenersByOnion.get(bob.links.inboxOnion(link))
				.server.getLocalPort();
		Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port);
		s.setSoTimeout(10_000);
		InputStream in = s.getInputStream();
		OutputStream out = s.getOutputStream();
		readFully(in, new byte[RECORD_HEADER_BYTES + HELLO_BYTES]);
		ContactRequestRecords.write(out, TYPE_SOLUTION,
				new ContactRequestRecords.Solution(NOW / EPOCH_MS, 0,
						getRandomBytes(8)).encode());
		byte[] keyHeader = new byte[RECORD_HEADER_BYTES];
		readFully(in, keyHeader);
		assertEquals(TYPE_KEY, keyHeader[1]);
		readFully(in, new byte[KEY_BYTES]);
		await("the session is admitted",
				() -> bob.links.isHeld(link.getLinkId()));
		return s;
	}

	@Test
	public void aRemovedLinkWhoseSessionEndsBeforeTheUnpublishIsDeleted()
			throws Exception {
		ContactLinkInfo support = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		ContactLink link = find(support.getLinkId());
		InboxSession.Inbox inbox = bob.inbox.getInbox(link.getLinkId());
		assertNotNull(inbox);
		Socket s = admit(link);
		bob.manager.withdrawPermanentLink(support.getLinkId());
		assertNotNull("held while the session runs", find(link.getLinkId()));
		s.close();
		await("the hold ends with the session",
				() -> !bob.links.isHeld(link.getLinkId()));
		await("the removed link is deleted although its inbox was still up",
				() -> find(link.getLinkId()) == null);
		bob.publish(network);
		assertFalse(reachable(link));
		assertTrue(cleared(inbox));
	}

	@Test
	public void aRemovedLinkLeftByAnEarlierRunIsDeletedAtStart()
			throws Exception {
		ContactLinkInfo support = bob.manager.createPermanentLink("Support");
		bob.links.hold(support.getLinkId());
		bob.manager.withdrawPermanentLink(support.getLinkId());
		assertTrue(bob.links.release(support.getLinkId()));
		assertNotNull("left behind as after a kill",
				find(support.getLinkId()));
		bob.inbox.startService();
		await("the start sweeps it", () -> find(support.getLinkId()) == null);
	}

	@Test
	public void signingOutClearsAnInboxStillInItsGrace() throws Exception {
		ContactLinkInfo support = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		ContactLink link = find(support.getLinkId());
		InboxSession.Inbox inbox = bob.inbox.getInbox(link.getLinkId());
		assertNotNull(inbox);
		try (Socket s = admit(link)) {
			bob.scheduler.runOneShots = false;
			bob.scheduler.heldOneShots.clear();
			bob.manager.withdrawPermanentLink(support.getLinkId());
			bob.publish(network);
			assertFalse("still in its grace", cleared(inbox));
			bob.inbox.stopService();
			await("sign-out clears the key at once", () -> cleared(inbox));
			assertEquals("and closes the session", -1,
					s.getInputStream().read());
		}
	}

	@Test
	public void onlyALinkThatEndedAfterAdmissionAndWithinTheGraceCounts() {
		ContactLink active = link(ContactLink.STATE_ACTIVE, 0, 0);
		ContactLink retired = link(ContactLink.STATE_RETIRED, NOW, 0);
		ContactLink withdrawn = link(ContactLink.STATE_WITHDRAWN, 0, NOW);
		assertFalse(ContactRequestReceiver.admittedWhileActive(active, NOW,
				NOW));
		assertFalse("no admission known",
				ContactRequestReceiver.admittedWhileActive(retired, 0, NOW));
		assertTrue(ContactRequestReceiver.admittedWhileActive(retired,
				NOW - 1, NOW + 1));
		assertTrue(ContactRequestReceiver.admittedWhileActive(withdrawn, NOW,
				NOW + UNPUBLISH_GRACE_MS));
		assertFalse("admitted after the link ended",
				ContactRequestReceiver.admittedWhileActive(withdrawn, NOW + 1,
						NOW + 2));
		assertFalse("too late",
				ContactRequestReceiver.admittedWhileActive(withdrawn, NOW,
						NOW + UNPUBLISH_GRACE_MS + 1));
	}

	private static ContactLink link(int state, long retiredAt,
			long withdrawnAt) {
		return new ContactLink(getRandomBytes(32),
				new AuthorId(getRandomBytes(32)), ContactLink.KIND_PERMANENT,
				state, null, null, null, null, NOW, 0, retiredAt,
				withdrawnAt);
	}

	private static void readFully(InputStream in, byte[] b) throws Exception {
		int got = 0;
		while (got < b.length) {
			int n = in.read(b, got, b.length - got);
			assertTrue(n > 0);
			got += n;
		}
	}

	private interface Condition {
		boolean holds() throws Exception;
	}

	private static void await(String what, Condition c) throws Exception {
		long end = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < end) {
			if (c.holds()) return;
			Thread.sleep(20);
		}
		assertTrue(what, c.holds());
	}
}
