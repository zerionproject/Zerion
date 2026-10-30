package org.zerionproject.core.contact.request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkLimitException;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.FloodNotice;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.event.ContactLinkFloodedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.data.BdfForTests;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.EPOCH_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_NOTICE_SLOTS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_PAUSE_MAX_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_PAUSE_MIN_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_PAUSE_RESET_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_RETRY_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_ZERO_WORK_PER_10_MIN;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.INBOX_KEY_SEED_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KEY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_PERMANENT_LINKS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_KEY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.TYPE_SOLUTION;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ZERO_ID_OPEN_MS;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class InboxLifecycleTest extends BrambleTestCase {

	private static final long NOW = 1_800_000_000_000L;

	private final File testDir = getTestDirectory();
	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final SettableClock clock = new SettableClock(NOW);
	private final FakeTorNetwork network = new FakeTorNetwork();
	private Party bob;

	@Before
	public void setUp() throws Exception {
		testDir.mkdirs();
		bob = new Party(testDir, "bob", crypto, clock, network);
	}

	@After
	public void tearDown() throws Exception {
		bob.close();
		deleteTestDirectory(testDir);
	}

	private String onionOf(ContactLink l) {
		return bob.links.inboxOnion(l);
	}

	private void refresh() throws Exception {
		bob.publish(network);
	}

	private boolean published(ContactLink l) {
		return bob.inbox.getPublishedOnions().contains(onionOf(l))
				&& network.isReachable(onionOf(l));
	}

	@Test
	public void aZeroIdOpensOnlyForThirtyDaysAfterAnExplicitShare()
			throws Exception {
		bob.manager.getZeroIdLink();
		ContactLink z = bob.links().get(0);
		refresh();
		assertFalse("viewing the ZeroID opens nothing", published(z));
		bob.manager.zeroIdShared();
		refresh();
		assertTrue(published(z));
		clock.now = NOW + ZERO_ID_OPEN_MS - 1;
		refresh();
		assertTrue(published(z));
		clock.now = NOW + ZERO_ID_OPEN_MS;
		refresh();
		assertFalse(published(z));
		assertTrue(network.removedOnions().contains(onionOf(z)));
		bob.manager.zeroIdShared();
		refresh();
		assertTrue(published(z));
	}

	@Test
	public void aResetClosesTheOldInboxAtOnceAndKeepsItsUndecidedRequests()
			throws Exception {
		bob.manager.zeroIdShared();
		ContactLink old = bob.links().get(0);
		refresh();
		bob.db.transaction(false, txn -> bob.db.addIncomingRequest(txn,
				incoming(old.getLinkId())));
		String fresh = bob.manager.resetZeroId();
		refresh();
		assertFalse(published(old));
		assertTrue(network.removedOnions().contains(onionOf(old)));
		ContactLink kept = find(old.getLinkId());
		assertNotNull("the old key stays while a request is undecided", kept);
		assertEquals(ContactLink.STATE_RETIRED, kept.getState());
		ContactLink newZeroId = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		assertFalse(Arrays.equals(old.getLinkId(), newZeroId.getLinkId()));
		assertEquals(fresh, bob.links.linkString(newZeroId));
		assertFalse("a new ZeroID waits for its first share",
				published(newZeroId));
		bob.manager.decline(bob.incoming().get(0).getRequestId());
		assertNull("the old key goes with its last request",
				find(old.getLinkId()));
	}

	@Test
	public void acceptingThroughTheZeroIdTakesItsInboxDownAndTheNewOneWaits()
			throws Exception {
		bob.manager.zeroIdShared();
		ContactLink old = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		refresh();
		assertTrue(published(old));
		IncomingRequest r = incoming(old.getLinkId());
		bob.db.transaction(false, txn -> bob.db.addIncomingRequest(txn, r));
		bob.manager.accept(r.getRequestId(), "Alice");
		refresh();
		assertFalse("the old ZerionID inbox is taken down", published(old));
		assertTrue("DEL_ONION for the old ZerionID inbox",
				network.removedOnions().contains(onionOf(old)));
		assertEquals(ContactLink.STATE_RETIRED,
				find(old.getLinkId()).getState());
		ContactLink fresh = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		assertFalse(Arrays.equals(old.getLinkId(), fresh.getLinkId()));
		assertEquals(0, fresh.getLastSharedAt());
		assertFalse("the new ZerionID is unpublished until shared",
				published(fresh));
		assertFalse(network.isReachable(onionOf(fresh)));
		bob.manager.zeroIdShared();
		refresh();
		assertTrue("published after the first share", published(fresh));
	}

	@Test
	public void permanentLinksArePublishedUntilWithdrawn() throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink(" Support ");
		assertEquals("Support", a.getLabel());
		ContactLinkInfo b = bob.manager.createPermanentLink("Press");
		refresh();
		ContactLink la = find(a.getLinkId());
		ContactLink lb = find(b.getLinkId());
		assertTrue(published(la));
		assertTrue(published(lb));
		assertTrue(a.getLink().contains("?i=" + onionOf(la)));
		assertEquals(2, bob.codec.parse(a.getLink()).getType());
		bob.manager.withdrawPermanentLink(a.getLinkId());
		refresh();
		assertFalse(published(la));
		assertTrue(published(lb));
		assertTrue(network.removedOnions().contains(onionOf(la)));
		assertNull(find(a.getLinkId()));
	}

	@Test
	public void aLinkSaysWhetherItsInboxRunsTorProofOfWork()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		assertFalse("not published yet", a.isPowProtected());
		bob.adapter.powProtected = true;
		refresh();
		assertTrue(bob.manager.getLinks().stream().filter(i ->
				Arrays.equals(i.getLinkId(), a.getLinkId())).findFirst()
				.get().isPowProtected());
		ContactLink la = find(a.getLinkId());
		assertTrue(bob.links.isInboxPowProtected(la));
		bob.manager.withdrawPermanentLink(a.getLinkId());
		refresh();
		assertFalse("a removed inbox no longer counts",
				bob.links.isInboxPowProtected(la));
	}

	@Test
	public void anInboxIsRemovedWhenAStepAfterItsPublicationFails()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		ContactLink la = find(a.getLinkId());
		bob.adapter.powProtected = true;
		bob.adapter.failAfterPublish = true;
		refresh();
		assertTrue("the onion is removed again",
				network.removedOnions().contains(onionOf(la)));
		assertFalse(published(la));
		assertFalse(bob.links.isInboxPowProtected(la));
		bob.adapter.failAfterPublish = false;
		refresh();
		assertTrue("the next refresh publishes it", published(la));
	}

	@Test
	public void atMostFivePermanentLinksAndLabelsUpToThirtyTwo()
			throws Exception {
		for (int i = 0; i < MAX_PERMANENT_LINKS; i++) {
			bob.manager.createPermanentLink("L" + i);
		}
		try {
			bob.manager.createPermanentLink("too many");
			fail();
		} catch (ContactLinkLimitException expected) {
		}
		bob.manager.withdrawPermanentLink(
				bob.manager.getLinks().get(0).getLinkId());
		StringBuilder max = new StringBuilder();
		for (int i = 0; i < 32; i++) max.append('x');
		bob.manager.createPermanentLink(max.toString());
		for (String bad : new String[] {"", "   ", max + "x"}) {
			try {
				bob.manager.createPermanentLink(bad);
				fail();
			} catch (IllegalArgumentException expected) {
			}
		}
	}

	@Test
	public void inboxesFollowTorAndKeepTheirSecretWithinTheProcess()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		refresh();
		ContactLink la = find(a.getLinkId());
		InboxPuzzle puzzle = bob.inbox.getPuzzle(la.getLinkId());
		bob.adapter.connected = false;
		refresh();
		assertFalse(published(la));
		bob.adapter.connected = true;
		refresh();
		assertTrue(published(la));
		assertSame(puzzle, bob.inbox.getPuzzle(la.getLinkId()));
	}

	@Test
	public void aRestoredCopyPublishesNothingUntilConfirmed()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		bob.db.transaction(false, txn -> bob.links.setPaused(txn, true));
		refresh();
		ContactLink la = find(a.getLinkId());
		assertFalse(published(la));
		assertTrue(bob.manager.isPausedAfterRestore());
		bob.manager.confirmOnlyActiveDevice();
		refresh();
		assertTrue(published(la));
	}

	@Test
	public void aBackupSnapshotCarriesThePauseButTheLiveDeviceKeepsGoing()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		refresh();
		ContactLink la = find(a.getLinkId());
		bob.manager.beginBackupSnapshot();
		assertTrue("the copy carries the flag", bob.db.transactionWithResult(
				true, bob.links::isPauseStored));
		assertFalse(bob.manager.isPausedAfterRestore());
		refresh();
		assertTrue(published(la));
		bob.manager.endBackupSnapshot();
		assertFalse(bob.db.transactionWithResult(true,
				bob.links::isPauseStored));
	}

	private IncomingRequest incoming(byte[] linkId) {
		byte[] otl = getRandomBytes(65);
		otl[0] = 1;
		return new IncomingRequest(getRandomBytes(16), linkId, otl, "x", null,
				getRandomBytes(32), NOW, NOW, IncomingRequest.STATE_RECEIVED,
				0, null);
	}

	private ContactLink find(byte[] linkId) throws Exception {
		return bob.db.transactionWithResult(true,
				txn -> bob.links.getLink(txn, linkId));
	}

	@Test
	public void withdrawingALinkClosesItsLiveConnections() throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		refresh();
		ContactLink la = find(a.getLinkId());
		int port = network.listenersByOnion.get(onionOf(la)).server
				.getLocalPort();
		try (java.net.Socket s = new java.net.Socket(
				java.net.InetAddress.getByName("127.0.0.1"), port)) {
			s.setSoTimeout(10_000);
			java.io.InputStream in = s.getInputStream();
			byte[] hello = new byte[49];
			int got = 0;
			while (got < hello.length) {
				int n = in.read(hello, got, hello.length - got);
				assertTrue(n > 0);
				got += n;
			}
			bob.manager.withdrawPermanentLink(a.getLinkId());
			refresh();
			assertEquals("the session past HELLO is closed", -1, in.read());
		}
	}

	private interface Condition {
		boolean holds() throws Exception;
	}

	private void await(String what, Condition c) throws Exception {
		long end = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < end) {
			if (c.holds()) return;
			Thread.sleep(20);
		}
		assertTrue(what, c.holds());
	}

	private void flood(ContactLink l) {
		InboxPuzzle puzzle = bob.inbox.getPuzzle(l.getLinkId());
		assertNotNull(puzzle);
		flood(puzzle);
	}

	private void flood(InboxPuzzle puzzle) {
		long at = clock.now;
		for (int i = 0; i <= FLOOD_ZERO_WORK_PER_10_MIN; i++) {
			puzzle.zeroWork(at + i);
		}
		assertTrue(puzzle.isFlooded());
	}

	private ContactLinkInfo info(byte[] linkId) throws Exception {
		for (ContactLinkInfo i : bob.manager.getLinks()) {
			if (Arrays.equals(i.getLinkId(), linkId)) return i;
		}
		return null;
	}

	private long pausedUntil(byte[] linkId) throws Exception {
		return bob.db.transactionWithResult(true,
				txn -> bob.links.getPausedUntil(txn, linkId));
	}

	private void awaitPaused(ContactLink l, long until) throws Exception {
		await("the link is paused until " + until + ", not "
				+ pausedUntil(l.getLinkId()) + " at " + clock.now,
				() -> pausedUntil(l.getLinkId()) == until);
		await("the inbox is removed", () -> !published(l)
				&& network.removedOnions().contains(onionOf(l)));
	}

	@Test
	public void aFloodedPermanentLinkIsPausedAndComesBackByItself()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		ContactLinkInfo b = bob.manager.createPermanentLink("Press");
		refresh();
		ContactLink la = find(a.getLinkId());
		ContactLink lb = find(b.getLinkId());
		bob.db.transaction(false, txn -> bob.db.addIncomingRequest(txn,
				incoming(la.getLinkId())));
		bob.scheduler.delaysMs.clear();
		flood(la);
		awaitPaused(la, NOW + FLOOD_PAUSE_MIN_MS);
		assertEquals("the link stays valid", ContactLink.STATE_ACTIVE,
				find(a.getLinkId()).getState());
		ContactLinkInfo paused = info(a.getLinkId());
		assertEquals(NOW + FLOOD_PAUSE_MIN_MS, paused.getPausedUntil());
		assertTrue(paused.isPaused(NOW));
		assertFalse(paused.isInboxOpen());
		assertNotNull("the link string is still there", paused.getLink());
		assertTrue("other links are untouched", published(lb));
		assertEquals(0, info(b.getLinkId()).getPausedUntil());
		assertEquals("requests already received stay", 1,
				bob.incoming().size());
		assertEquals(1, bob.bus.of(ContactLinkFloodedEvent.class).size());
		assertEquals(ContactLink.KIND_PERMANENT,
				bob.bus.of(ContactLinkFloodedEvent.class).get(0).getKind());
		List<FloodNotice> notices = bob.manager.getFloodNotices();
		assertEquals(1, notices.size());
		assertEquals("Support", notices.get(0).getLabel());
		assertEquals(NOW + FLOOD_PAUSE_MIN_MS, notices.get(0).getPausedUntil());
		assertTrue("a refresh is scheduled for the end of the pause",
				bob.scheduler.delaysMs.contains(FLOOD_PAUSE_MIN_MS));
		assertTrue("no Tor setting changed", network.isReachable(onionOf(lb)));

		clock.now = NOW + FLOOD_PAUSE_MIN_MS - 1;
		refresh();
		assertFalse("still paused one millisecond before", published(la));
		clock.now = NOW + FLOOD_PAUSE_MIN_MS;
		refresh();
		assertTrue("republished by the normal refresh", published(la));
		assertTrue(info(a.getLinkId()).isInboxOpen());
		assertEquals(0, info(a.getLinkId()).getPausedUntil());
		assertNotNull("a fresh detector", bob.inbox.getPuzzle(la.getLinkId()));
		assertFalse(bob.inbox.getPuzzle(la.getLinkId()).isFlooded());
	}

	@Test
	public void thePauseDoublesWithinADayIsCappedAndResetsAfterAQuietDay()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		refresh();
		ContactLink la = find(a.getLinkId());
		long[] expected = {1, 2, 4, 8, 16, 24, 24};
		for (long hours : expected) {
			flood(la);
			long until = clock.now + Math.min(hours * FLOOD_PAUSE_MIN_MS,
					FLOOD_PAUSE_MAX_MS);
			awaitPaused(la, until);
			clock.now = until;
			refresh();
			assertTrue(published(la));
		}
		assertEquals(1, bob.manager.getFloodNotices().size());
		clock.now += FLOOD_PAUSE_RESET_MS + 1;
		refresh();
		flood(la);
		awaitPaused(la, clock.now + FLOOD_PAUSE_MIN_MS);
		assertEquals(1, bob.manager.getFloodNotices().size());
	}

	@Test
	public void resumeNowEndsThePauseAndRepublishes() throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		refresh();
		ContactLink la = find(a.getLinkId());
		flood(la);
		awaitPaused(la, NOW + FLOOD_PAUSE_MIN_MS);
		bob.manager.resumePermanentLink(a.getLinkId());
		assertEquals(0, pausedUntil(a.getLinkId()));
		refresh();
		assertTrue(published(la));
		assertTrue(info(a.getLinkId()).isInboxOpen());
		flood(la);
		awaitPaused(la, NOW + 2 * FLOOD_PAUSE_MIN_MS);
	}

	@Test
	public void removingAPausedLinkRemovesItAndItsFloodSlot()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		refresh();
		ContactLink la = find(a.getLinkId());
		flood(la);
		awaitPaused(la, NOW + FLOOD_PAUSE_MIN_MS);
		bob.manager.withdrawPermanentLink(a.getLinkId());
		assertNull(find(a.getLinkId()));
		assertTrue(bob.manager.getFloodNotices().isEmpty());
		refresh();
		assertFalse(published(la));
	}

	@Test
	public void floodSlotsAreBoundedAndTheOldestIsOverwritten()
			throws Exception {
		for (int i = 0; i < FLOOD_NOTICE_SLOTS + 2; i++) {
			ContactLinkInfo a = bob.manager.createPermanentLink("L" + i);
			refresh();
			ContactLink la = find(a.getLinkId());
			clock.now = NOW + i;
			flood(la);
			awaitPaused(la, clock.now + FLOOD_PAUSE_MIN_MS);
			bob.db.transaction(false, txn -> bob.db.updateContactLink(txn,
					la.withdrawn(clock.now)));
		}
		List<FloodNotice> notices = bob.manager.getFloodNotices();
		assertEquals(FLOOD_NOTICE_SLOTS, notices.size());
		for (FloodNotice n : notices) {
			assertTrue("the oldest two were overwritten",
					n.getAt() >= NOW + 2);
		}
		int keys = bob.db.transactionWithResult(true, txn -> bob.db
				.getSettings(txn, ContactLinkStore.SETTINGS_NAMESPACE).size());
		assertTrue("never unbounded keys", keys <= 6 * FLOOD_NOTICE_SLOTS + 3);
		bob.manager.dismissFloodNotice(notices.get(0).getLinkId());
		assertEquals(FLOOD_NOTICE_SLOTS - 1,
				bob.manager.getFloodNotices().size());
	}

	@Test
	public void aFloodedZeroIdIsStillResetByItself() throws Exception {
		bob.manager.zeroIdShared();
		ContactLink old = bob.links().get(0);
		refresh();
		assertTrue(published(old));
		flood(old);
		await("the old ZeroID is retired", () -> {
			ContactLink l = find(old.getLinkId());
			return l == null || l.getState() == ContactLink.STATE_RETIRED;
		});
		await("the old inbox is removed", () -> !published(old)
				&& network.removedOnions().contains(onionOf(old)));
		assertNull("no requests, so the old key is gone", find(old.getLinkId()));
		ContactLink fresh = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		assertNotNull(fresh);
		assertFalse(Arrays.equals(old.getLinkId(), fresh.getLinkId()));
		assertEquals("a reset ZeroID waits for its first share", 0,
				fresh.getLastSharedAt());
		refresh();
		assertFalse(published(fresh));
		assertEquals(1, bob.bus.of(ContactLinkFloodedEvent.class).size());
		assertEquals(ContactLink.KIND_ZERO_ID,
				bob.bus.of(ContactLinkFloodedEvent.class).get(0).getKind());
		List<FloodNotice> notices = bob.manager.getFloodNotices();
		assertEquals(1, notices.size());
		assertEquals(ContactLink.KIND_ZERO_ID, notices.get(0).getKind());
		assertNull(notices.get(0).getLabel());
		assertEquals(0, notices.get(0).getPausedUntil());
		bob.manager.zeroIdShared();
		refresh();
		flood(fresh);
		await("a second reset reuses the one ZeroID slot", () ->
				bob.manager.getFloodNotices().size() == 1
						&& Arrays.equals(fresh.getLinkId(),
						bob.manager.getFloodNotices().get(0).getLinkId()));
		bob.manager.dismissFloodNotice(fresh.getLinkId());
		assertTrue(bob.manager.getFloodNotices().isEmpty());
	}

	@Test
	public void aFloodReportThatHitsABusyDatabaseIsRetried()
			throws Exception {
		AtomicBoolean busy = new AtomicBoolean(false);
		DatabaseComponent flaky = (DatabaseComponent) Proxy.newProxyInstance(
				DatabaseComponent.class.getClassLoader(),
				new Class<?>[] {DatabaseComponent.class},
				(proxy, method, args) -> {
					if (busy.get() && method.getName()
							.equals("transactionWithNullableResult")) {
						throw new DbException();
					}
					try {
						return method.invoke(bob.db, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		InboxService service = new InboxService(flaky, bob.links,
				bob.receiver, bob.requestCrypto, BdfForTests.readers(),
				() -> bob.adapter, clock, bob.scheduler, bob.io);
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		network.learn(bob.links, bob.links());
		service.refresh();
		ContactLink la = find(a.getLinkId());
		assertTrue(network.isReachable(onionOf(la)));
		bob.scheduler.runOneShots = false;
		bob.scheduler.delaysMs.clear();
		busy.set(true);
		flood(service.getPuzzle(la.getLinkId()));
		await("the retry is scheduled", () ->
				bob.scheduler.delaysMs.contains(FLOOD_RETRY_MS));
		assertEquals("nothing was paused", 0, pausedUntil(a.getLinkId()));
		assertNotNull("the detector is kept for the retry",
				service.getPuzzle(la.getLinkId()));
		busy.set(false);
		assertNotNull(service.flood(la.getLinkId()));
		assertEquals(NOW + FLOOD_PAUSE_MIN_MS, pausedUntil(a.getLinkId()));
		assertFalse(network.isReachable(onionOf(la)));
		service.stopService();
	}

	@Test
	public void aFloodOnAnInactiveLinkStillDropsItsDetector()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		refresh();
		ContactLink la = find(a.getLinkId());
		assertNotNull(bob.inbox.getPuzzle(la.getLinkId()));
		assertNotNull(bob.inbox.getSlots(la.getLinkId()));
		bob.manager.withdrawPermanentLink(a.getLinkId());
		assertNull(bob.inbox.flood(la.getLinkId()));
		assertNull(bob.inbox.getPuzzle(la.getLinkId()));
		assertNull(bob.inbox.getSlots(la.getLinkId()));
	}

	@Test
	public void acceptingThroughASecondActiveZeroIdNeverFailsTheAccept()
			throws Exception {
		bob.manager.zeroIdShared();
		KeyPair keys = crypto.generateHybridAgreementKeyPair();
		byte[] pub = keys.getPublic().getEncoded();
		byte[] linkId = crypto.hash(HYBRID_COMMITMENT_LABEL, pub);
		byte[] seed = getRandomBytes(INBOX_KEY_SEED_BYTES);
		bob.db.transaction(false, txn -> {
			AuthorId local = bob.identity.manager.getLocalAuthor(txn).getId();
			bob.db.addContactLink(txn, new ContactLink(linkId, local,
					ContactLink.KIND_ZERO_ID, ContactLink.STATE_ACTIVE, null,
					pub, keys.getPrivate().getEncoded(), seed, NOW, NOW, 0, 0));
		});
		ContactLink first = bob.db.transactionWithResult(true,
				bob.links::getZeroId);
		ContactLink other = null;
		for (ContactLink l : bob.links()) {
			if (l.getKind() == ContactLink.KIND_ZERO_ID
					&& !Arrays.equals(l.getLinkId(), first.getLinkId())) {
				other = l;
			}
		}
		assertNotNull(other);
		IncomingRequest r = incoming(other.getLinkId());
		bob.db.transaction(false, txn -> bob.db.addIncomingRequest(txn, r));
		bob.manager.accept(r.getRequestId(), "Alice");
		assertEquals(1, bob.db.transactionWithResult(true,
				bob.db::getPendingContacts).size());
		assertEquals(ContactLink.STATE_RETIRED,
				find(other.getLinkId()).getState());
		assertEquals(ContactLink.STATE_ACTIVE,
				find(first.getLinkId()).getState());
		int active = 0;
		for (ContactLink l : bob.links()) {
			if (l.getKind() == ContactLink.KIND_ZERO_ID
					&& l.getState() == ContactLink.STATE_ACTIVE) {
				active++;
			}
		}
		assertEquals("no extra ZeroID is created", 1, active);
	}

	private static void readFully(InputStream in, byte[] b) throws Exception {
		int got = 0;
		while (got < b.length) {
			int n = in.read(b, got, b.length - got);
			assertTrue(n > 0);
			got += n;
		}
	}

	@Test
	public void withdrawingALinkKeepsAConnectionThatPassedAdmit()
			throws Exception {
		ContactLinkInfo a = bob.manager.createPermanentLink("Support");
		refresh();
		ContactLink la = find(a.getLinkId());
		int port = network.listenersByOnion.get(onionOf(la)).server
				.getLocalPort();
		try (Socket s = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
			s.setSoTimeout(10_000);
			InputStream in = s.getInputStream();
			OutputStream out = s.getOutputStream();
			byte[] hello = new byte[49];
			readFully(in, hello);
			assertEquals("level 0", 0, hello[7]);
			ContactRequestRecords.write(out, TYPE_SOLUTION,
					new ContactRequestRecords.Solution(NOW / EPOCH_MS, 0,
							getRandomBytes(8)).encode());
			byte[] keyHeader = new byte[6];
			readFully(in, keyHeader);
			assertEquals("KEY follows admit", TYPE_KEY, keyHeader[1]);
			readFully(in, new byte[KEY_BYTES]);
			await("the solved slot is taken", () ->
					bob.inbox.getSlots(la.getLinkId()).solvedCount() == 1);
			bob.manager.withdrawPermanentLink(a.getLinkId());
			refresh();
			assertFalse(published(la));
			s.setSoTimeout(1_500);
			try {
				int read = in.read();
				fail("the admitted session was closed: " + read);
			} catch (SocketTimeoutException stillOpen) {
			}
		}
	}
}
