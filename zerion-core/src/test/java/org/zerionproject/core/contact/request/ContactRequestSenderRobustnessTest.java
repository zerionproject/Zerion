package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.contact.request.ContactRequestRecords.Hello;
import org.zerionproject.core.contact.request.ContactRequestRecords.Solution;
import org.zerionproject.core.contact.request.ContactRequestSender.AttemptResult;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_HELLO;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_CANCELLED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_DELIVERED;
import static org.zerionproject.core.api.contact.request.OutgoingRequest.STATE_WITHDRAWING;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ContactRequestSenderRobustnessTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;

	private final File testDir = getTestDirectory();
	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final SettableClock aliceClock = new SettableClock(NOW);
	private final SettableClock bobClock = new SettableClock(NOW);
	private final FakeTorNetwork network = new FakeTorNetwork();
	private final ExecutorService exec = Executors.newCachedThreadPool();
	private Party alice, bob;

	@Before
	public void setUp() throws Exception {
		testDir.mkdirs();
		alice = new Party(testDir, "alice", crypto, aliceClock, network);
		bob = new Party(testDir, "bob", crypto, bobClock, network);
	}

	@After
	public void tearDown() throws Exception {
		exec.shutdownNow();
		alice.close();
		bob.close();
		deleteTestDirectory(testDir);
	}

	private String bobsLink() throws Exception {
		ContactLink z = bob.zeroIdShared();
		bob.publish(network);
		return bob.links.linkString(z);
	}

	private byte[] send(String link) throws Exception {
		return alice.sender.submit(alice.codec.parse(link), link, "Bob",
				"Alice", null);
	}

	private void raise(long at) {
		ContactLink z;
		try {
			z = bob.links().get(0);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
		InboxPuzzle puzzle = bob.inbox.getPuzzle(z.getLinkId());
		for (int i = 0; i < 10; i++) {
			puzzle.admit(new Solution(at / 600_000, 3, getRandomBytes(8)), at);
		}
		assertEquals(1, puzzle.getLevel(at));
	}

	@Test
	public void aSenderClockAheadOfTheInboxStillDeliversAtLevelOne()
			throws Exception {
		String link = bobsLink();
		raise(NOW);
		aliceClock.now = NOW + 30 * 60_000;
		byte[] id = send(link);
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		assertEquals(1, bob.incoming().size());
	}

	@Test
	public void anInboxAskingForUnboundedWorkIsOneFailedAttempt()
			throws Exception {
		String link = bobsLink();
		ContactLink z = bob.links().get(0);
		String onion = bob.links.inboxOnion(z);
		ServerSocket greedy = new ServerSocket(0, 5,
				InetAddress.getByName("127.0.0.1"));
		exec.execute(() -> {
			while (true) {
				try (Socket s = greedy.accept()) {
					ContactRequestRecords.write(s.getOutputStream(),
							TYPE_HELLO, new Hello(1, 3, 255, NOW / 600_000,
									getRandomBytes(32)).encode());
				} catch (IOException e) {
					return;
				}
			}
		});
		network.listenersByOnion.put(onion,
				new FakeTorNetwork.Listener(greedy));
		byte[] id = send(link);
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		assertEquals(1, alice.outgoing(id).getAttempts());
		greedy.close();
	}

	@Test
	public void anInboxAskingTooMuchWorkMarksTheRequestBusyUntilDelivered()
			throws Exception {
		String link = bobsLink();
		ContactLink z = bob.links().get(0);
		String onion = bob.links.inboxOnion(z);
		FakeTorNetwork.Listener real = network.listenersByOnion.get(onion);
		ServerSocket greedy = new ServerSocket(0, 5,
				InetAddress.getByName("127.0.0.1"));
		exec.execute(() -> {
			while (true) {
				try (Socket s = greedy.accept()) {
					ContactRequestRecords.write(s.getOutputStream(),
							TYPE_HELLO, new Hello(1, 3, 255, NOW / 600_000,
									getRandomBytes(32)).encode());
				} catch (IOException e) {
					return;
				}
			}
		});
		network.listenersByOnion.put(onion,
				new FakeTorNetwork.Listener(greedy));
		byte[] id = send(link);
		assertFalse(alice.sender.isBusy(id));
		assertEquals(AttemptResult.FAILED, alice.sender.attempt(id));
		assertTrue(alice.sender.isBusy(id));
		greedy.close();
		network.listenersByOnion.put(onion, real);
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		assertFalse(alice.sender.isBusy(id));
	}

	@Test
	public void aCancelDuringDeliveryBecomesAWithdraw() throws Exception {
		String link = bobsLink();
		byte[] id = send(link);
		network.beforeConnect = () -> {
			try {
				alice.sender.cancel(id);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		};
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(STATE_WITHDRAWING, r.getState());
		assertNull(r.getOneTimePrivateKey());
		assertEquals(1, bob.incoming().size());
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(id));
		assertEquals(STATE_CANCELLED, alice.outgoing(id).getState());
		assertTrue(bob.incoming().isEmpty());
	}

	@Test
	public void aCancelledQueuedRequestIsKeptWithoutKeys() throws Exception {
		String link = bobsLink();
		byte[] id = send(link);
		alice.sender.cancel(id);
		OutgoingRequest r = alice.outgoing(id);
		assertEquals(STATE_CANCELLED, r.getState());
		assertNull(r.getOneTimePrivateKey());
		assertEquals(AttemptResult.SKIPPED, alice.sender.attempt(id));
		alice.sender.dismiss(id);
		assertNull(alice.outgoing(id));
	}

	@Test
	public void aWithdrawDoesNotHoldBackANewRequestToTheSameLink()
			throws Exception {
		String link = bobsLink();
		byte[] first = send(link);
		alice.sender.attempt(first);
		bob.adapter.connected = false;
		bob.publish(network);
		alice.sender.cancel(first);
		assertEquals(STATE_WITHDRAWING, alice.outgoing(first).getState());
		byte[] second = send(link);
		bob.adapter.connected = true;
		bob.publish(network);
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(first));
		assertEquals(AttemptResult.DELIVERED, alice.sender.attempt(second));
		assertEquals(STATE_DELIVERED, alice.outgoing(second).getState());
		assertEquals(1, bob.incoming().size());
	}
}
