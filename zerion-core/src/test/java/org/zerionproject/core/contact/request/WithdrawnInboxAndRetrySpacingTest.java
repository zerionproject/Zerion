package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.contact.request.ContactRequestSender.AttemptResult;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.data.BdfForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SEND_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_NOT_DELIVERED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_QUEUED;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class WithdrawnInboxAndRetrySpacingTest extends BrambleTestCase {

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

	private ContactLink find(byte[] linkId) throws Exception {
		for (ContactLink l : bob.links()) {
			if (java.util.Arrays.equals(l.getLinkId(), linkId)) return l;
		}
		return null;
	}

	private byte[] send(String link) throws Exception {
		return alice.sender.submit(alice.codec.parse(link), link, "Bob",
				"Alice", null);
	}

	@Test
	public void aWithdrawnInboxRefusesConnectionsAndStoresNothing()
			throws Exception {
		ContactLinkInfo info = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		ContactLink link = find(info.getLinkId());
		String onion = bob.links.inboxOnion(link);
		String linkString = info.getLink();
		int oldPort = network.listenersByOnion.get(onion).server
				.getLocalPort();
		byte[] id = send(linkString);

		bob.manager.withdrawPermanentLink(info.getLinkId());
		bob.publish(network);
		assertTrue(network.removedOnions().contains(onion));
		assertFalse(network.isReachable(onion));
		assertNull(find(info.getLinkId()));

		try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"),
				oldPort)) {
			fail("the old inbox listener still accepts connections");
		} catch (IOException expected) {
		}

		int before = network.connections.get();
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		assertEquals(0, network.connections.get() - before);
		assertTrue(bob.incoming().isEmpty());
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(STATE_QUEUED, r.getState());
		assertEquals(1, r.getAttempts());
		assertTrue(r.getNextAttemptAt() >= NOW + 48_000);
		assertTrue(r.getNextAttemptAt() <= NOW + 72_000);

		clock.now = NOW + SEND_WINDOW_MS;
		alice.sender.tick();
		r = alice.outgoing(id);
		assertEquals(STATE_NOT_DELIVERED, r.getState());
		assertNull(r.getOneTimePrivateKey());
		assertTrue(bob.incoming().isEmpty());
	}

	@Test
	public void aFailedAttemptIsNotRetriedBeforeItsDelay() throws Exception {
		ContactLinkInfo info = bob.manager.createPermanentLink("Support");
		bob.publish(network);
		String linkString = info.getLink();
		bob.manager.withdrawPermanentLink(info.getLinkId());
		bob.publish(network);

		List<Runnable> submitted = new ArrayList<>();
		ContactRequestSender sender = new ContactRequestSender(alice.db,
				alice.links, alice.requestCrypto, BdfForTests.writers(),
				() -> alice.adapter, alice.identity.manager, clock,
				alice.scheduler, submitted::add);
		byte[] id = sender.submit(alice.codec.parse(linkString), linkString,
				"Bob", "Alice", null);
		assertEquals("submit schedules one tick", 1, submitted.size());
		submitted.get(0).run();
		assertEquals("the tick schedules the first attempt at once", 2,
				submitted.size());
		submitted.get(1).run();
		assertEquals(1, alice.outgoing(id).getAttempts());

		sender.tick();
		assertEquals("nothing is due right after a failure", 2,
				submitted.size());
		clock.now = NOW + 47_000;
		sender.tick();
		assertEquals(2, submitted.size());
		clock.now = NOW + 72_000;
		sender.tick();
		assertEquals(3, submitted.size());
		submitted.get(2).run();
		assertEquals(2, alice.outgoing(id).getAttempts());
		assertTrue(alice.outgoing(id).getNextAttemptAt()
				>= clock.now + 96_000);
		assertTrue(alice.outgoing(id).getNextAttemptAt()
				<= clock.now + 144_000);
	}

	@Test
	public void aConnectionFailureAfterASolveKeepsTheSolution()
			throws Exception {
		ContactLink z = bob.zeroIdShared();
		bob.publish(network);
		String linkString = bob.links.linkString(z);
		String onion = bob.links.inboxOnion(z);
		InboxPuzzle puzzle = bob.inbox.getPuzzle(z.getLinkId());
		for (int i = 0; i < 10; i++) {
			puzzle.admit(new ContactRequestRecords.Solution(NOW / 600_000, 3,
					getRandomBytes(8)), NOW);
		}
		assertEquals(1, puzzle.getLevel(NOW));
		FakeTorNetwork.Listener real = network.listenersByOnion.get(onion);
		ServerSocket closed = new ServerSocket(0, 5,
				InetAddress.getByName("127.0.0.1"));
		closed.close();
		FakeTorNetwork.Listener gone = new FakeTorNetwork.Listener(closed);

		byte[] id = send(linkString);
		network.beforeConnect = () -> network.beforeConnect =
				() -> network.listenersByOnion.put(onion, gone);
		int before = network.connections.get();
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		assertEquals("HELLO was read, the solved reconnect failed", 1,
				network.connections.get() - before);
		assertEquals(1, alice.outgoing(id).getAttempts());
		assertTrue(bob.incoming().isEmpty());

		network.listenersByOnion.put(onion, real);
		clock.now = NOW + 60_000;
		before = network.connections.get();
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		assertEquals("one pipelined connection, no new HELLO round", 1,
				network.connections.get() - before);
		assertEquals(1, bob.incoming().size());
	}
}
