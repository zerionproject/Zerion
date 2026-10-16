package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSyncStatus;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ChannelSyncStatusTest {

	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60L * MINUTE;
	private static final long DAY = 24L * HOUR;
	private static final long BACKOFF_CEILING = 15L * MINUTE;
	private static final String NS_STATUS = "zerion-channels-sync-status";
	private static final String NS_DELETED =
			"zerion-channels-deleted-notices";
	private static final String ACCENTED = "Caf" + (char) 0xE9 + ", news";

	private final Random random = new Random(207);
	private final AtomicBoolean ownerUp = new AtomicBoolean(true);
	private final AtomicInteger requests = new AtomicInteger();
	private CryptoComponent crypto;
	private ChannelTestNode.MutableClock clock;
	private ChannelTestNode publisher;
	private ChannelTestNode subscriber;
	private ChannelTestNode.TestChannel channel;
	private byte[] id;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		clock = new ChannelTestNode.MutableClock();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		id = channel.channelId;
		publisher = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport());
		publisher.seedPublisher(channel, true, null, null, false,
				publisher.posts(channel, 2));
		subscriber = new ChannelTestNode(crypto, clock, new ChannelTransport() {
			@Override
			public ChannelServer bindServer(byte[] channelId,
					@Nullable String key, ChannelRequestHandler handler)
					throws IOException {
				throw new IOException();
			}

			@Override
			public byte[] requestFromOnion(String onion, byte[] request)
					throws IOException {
				requests.incrementAndGet();
				if (!ownerUp.get()) throw new IOException("unreachable");
				try {
					return publisher.handle(id, request);
				} catch (Exception e) {
					throw new IOException(e);
				}
			}

			@Override
			public boolean isReachable(String onion) {
				return ownerUp.get();
			}
		});
		subscriber.seedSubscriber(channel, true, null, null,
				Collections.emptyList());
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
		subscriber.deleteFiles();
	}

	private List<Long> runFor(long ms) throws Exception {
		List<Long> pulls = new ArrayList<>();
		long end = clock.currentTimeMillis() + ms;
		int seen = requests.get();
		while (clock.currentTimeMillis() < end) {
			subscriber.refreshAndReschedule();
			if (requests.get() > seen) {
				seen = requests.get();
				pulls.add(clock.currentTimeMillis());
			}
			clock.advance(subscriber.scheduledDelays.get(
					subscriber.scheduledDelays.size() - 1));
		}
		return pulls;
	}

	private long interval() {
		return subscriber.manager.pollIntervalMs(id);
	}

	private ChannelSyncStatus status() throws DbException {
		return subscriber.manager.getSyncStatus(id);
	}

	private void refreshFails() {
		try {
			subscriber.manager.refreshChannel(id);
			fail("the owner is not reachable");
		} catch (DbException expected) {
		}
	}

	private void openFails() {
		try {
			subscriber.manager.refreshChannelOnOpen(id);
			fail("the owner is not reachable");
		} catch (DbException expected) {
		}
	}

	private long nextDue() {
		return subscriber.manager.nextPullDueMs(id);
	}

	@Test
	public void anOwnerThatNeverAnswersIsPulledLessAndLessOften()
			throws Exception {
		ownerUp.set(false);
		List<Long> pulls = runFor(3L * HOUR);
		assertEquals("each failed pull doubles the interval up to 15 min",
				BACKOFF_CEILING, interval());
		assertTrue(pulls.size() + " pulls in three hours",
				pulls.size() > 8 && pulls.size() < 30);
		long last = pulls.get(pulls.size() - 1)
				- pulls.get(pulls.size() - 2);
		assertTrue("the jitter is kept: " + last + " ms",
				last >= BACKOFF_CEILING / 2
						&& last <= BACKOFF_CEILING * 3 / 2 + MINUTE);
		for (int i = 1; i < pulls.size(); i++) {
			assertTrue(pulls.get(i) - pulls.get(i - 1)
					<= BACKOFF_CEILING * 3 / 2 + MINUTE);
		}
	}

	@Test
	public void aSuccessfulPullEndsTheBackoff() throws Exception {
		ownerUp.set(false);
		runFor(HOUR);
		assertTrue("backed off to " + interval() + " ms",
				interval() > 60_000L);
		ownerUp.set(true);
		List<Long> pulls = runFor(BACKOFF_CEILING * 3 / 2 + MINUTE);
		assertFalse(pulls.isEmpty());
		assertTrue("the usual schedule is back: " + interval() + " ms",
				interval() <= 60_000L);
		assertEquals(2, subscriber.store.getPosts(id).size());
		assertFalse(status().isLastAttemptFailed());
		assertTrue(runFor(10L * MINUTE).size() >= 8);
	}

	@Test
	public void openingTheFeedPullsAtOnceAndResetsTheBackoff()
			throws Exception {
		ownerUp.set(false);
		runFor(HOUR);
		assertTrue("backed off to " + interval() + " ms",
				interval() > 60_000L);
		int before = requests.get();
		long due = nextDue();
		openFails();
		assertEquals("the feed pulls at once", before + 1, requests.get());
		assertEquals("the backoff starts again from the first step",
				10_000L, interval());
		assertTrue("a failed pull never brings the next one forward",
				nextDue() >= due);
		ownerUp.set(true);
		subscriber.manager.refreshChannelOnOpen(id);
		assertEquals(5_000L, interval());
		assertFalse(status().isLastAttemptFailed());
	}

	@Test
	public void refreshesOfAnOpenFeedDoNotHurryTheBackgroundPulls()
			throws Exception {
		ownerUp.set(false);
		runFor(HOUR);
		int before = requests.get();
		int manual = 1;
		openFails();
		long end = clock.currentTimeMillis() + 10L * MINUTE;
		while (clock.currentTimeMillis() < end) {
			clock.advance(12_000L);
			subscriber.refreshAndReschedule();
			long due = nextDue();
			refreshFails();
			manual++;
			assertTrue("a failed refresh moved the next pull forward",
					nextDue() >= due);
		}
		int background = requests.get() - before - manual;
		assertTrue(background + " background pulls while the feed was open",
				background <= 2);
	}

	@Test
	public void torComingBackStartsTheScheduleAgain() throws Exception {
		ownerUp.set(false);
		runFor(HOUR);
		assertTrue(interval() > 60_000L);
		subscriber.manager.eventOccurred(
				new TransportActiveEvent(TorConstants.ID));
		assertEquals(5_000L, interval());
		assertFalse("failures while Tor was away are not held against "
				+ "the channel", runFor(16_000L).isEmpty());
	}

	@Test
	public void theLastSuccessfulPullAndAFailedAttemptAreKeptLocally()
			throws Exception {
		assertEquals(ChannelSyncStatus.NEVER, status().getLastUpdatedMs());
		assertFalse(status().isLastAttemptFailed());
		long t0 = clock.currentTimeMillis();
		subscriber.manager.refreshChannel(id);
		clock.advance(10_000L);
		subscriber.manager.refreshChannel(id);
		clock.advance(10_000L);
		subscriber.manager.refreshChannel(id);
		assertEquals("one write per minute at most", 1,
				subscriber.settings.writes(NS_STATUS));
		ChannelSyncStatus s = status();
		assertEquals(t0, s.getLastUpdatedMs());
		assertFalse(s.isLastAttemptFailed());
		assertFalse(s.isDormant());

		clock.advance(10L * MINUTE);
		ownerUp.set(false);
		refreshFails();
		s = status();
		assertEquals(t0, s.getLastUpdatedMs());
		assertTrue(s.isLastAttemptFailed());

		ChannelTestNode restarted = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport(), subscriber);
		s = restarted.manager.getSyncStatus(id);
		assertEquals("kept across a restart", t0, s.getLastUpdatedMs());
		assertTrue(s.isLastAttemptFailed());

		ChannelSyncStatus owner = publisher.manager.getSyncStatus(id);
		assertEquals(ChannelSyncStatus.NEVER, owner.getLastUpdatedMs());
		assertFalse(owner.isLastAttemptFailed());
		assertEquals("nothing is kept for the owner's own channel", 0,
				publisher.settings.writes(NS_STATUS));
	}

	@Test
	public void aChannelSilentForNinetyDaysRestsUntilItsFeedIsOpened()
			throws Exception {
		long t0 = clock.currentTimeMillis();
		subscriber.manager.refreshChannel(id);
		ownerUp.set(false);
		runFor(HOUR);
		clock.advance(90L * DAY);
		int before = requests.get();
		assertTrue(runFor(6L * HOUR).isEmpty());
		assertEquals("no automatic pull", before, requests.get());
		ChannelSyncStatus s = status();
		assertTrue(s.isDormant());
		assertEquals(t0, s.getLastUpdatedMs());

		openFails();
		assertEquals("opening the feed tries once", before + 1,
				requests.get());
		assertTrue(runFor(6L * HOUR).isEmpty());
		assertTrue(status().isDormant());

		subscriber.manager.leaveChannel(id);
		assertNull(subscriber.store.getChannel(id));
		for (String[] row : subscriber.settings.rows()) {
			if (row[0].equals(NS_STATUS) && !row[2].isEmpty()) {
				fail("a status row was left: " + row[1]);
			}
		}
		assertTrue("leaving is not an owner deletion", subscriber.manager
				.getOwnerDeletedChannelNames().isEmpty());
	}

	@Test
	public void aRestingChannelIsNotLookedUpOnEveryTick() throws Exception {
		subscriber.manager.refreshChannel(id);
		ownerUp.set(false);
		runFor(HOUR);
		clock.advance(91L * DAY);
		assertTrue(runFor(HOUR).isEmpty());
		assertTrue(status().isDormant());
		AtomicInteger reads = new AtomicInteger();
		subscriber.settings.onRead(ns -> {
			if (ns.equals(NS_STATUS)) reads.incrementAndGet();
		});
		try {
			assertTrue(runFor(12L * HOUR).isEmpty());
		} finally {
			subscriber.settings.onRead(null);
		}
		assertTrue(reads.get() + " reads of the status while resting",
				reads.get() <= 1);
		ownerUp.set(true);
		subscriber.manager.refreshChannelOnOpen(id);
		assertFalse(status().isDormant());
		assertTrue("the cached state follows a successful pull",
				runFor(10L * MINUTE).size() >= 8);
	}

	@Test
	public void aSuccessfulPullWakesADormantChannel() throws Exception {
		subscriber.manager.refreshChannel(id);
		ownerUp.set(false);
		runFor(HOUR);
		clock.advance(91L * DAY);
		assertTrue(runFor(HOUR).isEmpty());
		assertTrue(status().isDormant());
		ownerUp.set(true);
		long t1 = clock.currentTimeMillis();
		subscriber.manager.refreshChannel(id);
		ChannelSyncStatus s = status();
		assertFalse(s.isDormant());
		assertEquals(t1, s.getLastUpdatedMs());
		assertTrue("automatic pulls resume",
				runFor(10L * MINUTE).size() >= 8);
	}

	@Test
	public void aChannelNeverReachedRestsNinetyDaysAfterItsFirstAttempt()
			throws Exception {
		ownerUp.set(false);
		assertFalse(runFor(HOUR).isEmpty());
		ChannelSyncStatus s = status();
		assertEquals(ChannelSyncStatus.NEVER, s.getLastUpdatedMs());
		assertTrue(s.isLastAttemptFailed());
		assertFalse(s.isDormant());
		clock.advance(89L * DAY);
		assertFalse(runFor(HOUR).isEmpty());
		clock.advance(DAY);
		assertTrue(runFor(6L * HOUR).isEmpty());
		assertTrue(status().isDormant());
	}

	private void deletedByTheOwner() throws Exception {
		subscriber.manager.refreshChannel(id);
		assertEquals("name", subscriber.store.getChannel(id).getName());
		byte[] tombstone = (byte[]) publisher.call("signTombstone",
				new Class<?>[] {ChannelState.class},
				publisher.store.getChannel(id));
		assertNotNull(tombstone);
		new ChannelTombstoneStore(publisher.settings).put(id, tombstone);
		subscriber.manager.refreshChannel(id);
		assertNull(subscriber.store.getChannel(id));
	}

	@Test
	public void anOwnerDeletionNoticeLapsesAfterAboutThirtyDays()
			throws Exception {
		deletedByTheOwner();
		boolean stored = false;
		for (String[] row : subscriber.settings.rows()) {
			if (row[0].equals(NS_DELETED)) {
				stored = true;
				assertTrue("only the name and the day are kept: " + row[2],
						row[2].matches("n[A-Za-z0-9_-]*\\.[0-9]+"));
			}
		}
		assertTrue(stored);
		List<String> name = Collections.singletonList("name");
		clock.advance(29L * DAY);
		assertEquals(name, subscriber.manager.getOwnerDeletedChannelNames());
		clock.advance(2L * DAY);
		subscriber.call("runDailyPurgeSafely", new Class<?>[0]);
		for (String[] row : subscriber.settings.rows()) {
			if (row[0].equals(NS_DELETED) && !row[2].isEmpty()) {
				fail("the name outlived its notice: " + row[2]);
			}
		}
		assertTrue(subscriber.manager.getOwnerDeletedChannelNames()
				.isEmpty());
	}

	@Test
	public void anOwnerDeletionNoticeStaysUntilDismissed()
			throws Exception {
		deletedByTheOwner();

		String hex = ChannelStore.hex(id);
		boolean stored = false;
		for (String[] row : subscriber.settings.rows()) {
			if (row[0].equals(NS_DELETED)) {
				stored = true;
				assertFalse(row[1].contains(hex) || row[2].contains(hex));
			}
			if (row[0].equals(NS_STATUS) && !row[2].isEmpty()) {
				fail("a status row was left: " + row[1]);
			}
		}
		assertTrue(stored);
		List<String> name = Collections.singletonList("name");
		assertEquals(name, subscriber.manager.getOwnerDeletedChannelNames());
		assertEquals("reading the notice does not forget it", name,
				subscriber.manager.getOwnerDeletedChannelNames());
		ChannelTestNode restarted = new ChannelTestNode(crypto, clock,
				ChannelTestNode.noTransport(), subscriber);
		assertEquals("a screen lost before the notice was dismissed "
				+ "shows it again", name,
				restarted.manager.getOwnerDeletedChannelNames());
		restarted.manager.dismissOwnerDeletedChannelName("other");
		assertEquals(name, restarted.manager.getOwnerDeletedChannelNames());
		restarted.manager.dismissOwnerDeletedChannelName("name");
		assertTrue("dismissed means forgotten", subscriber.manager
				.getOwnerDeletedChannelNames().isEmpty());
		for (String[] row : subscriber.settings.rows()) {
			if (row[0].equals(NS_DELETED) && !row[2].isEmpty()) {
				fail("the name was kept after the notice was dismissed");
			}
		}
	}

	@Test
	public void noticesKeepNamesOnly() throws Exception {
		ChannelSyncStatusStore store =
				new ChannelSyncStatusStore(subscriber.settings);
		long now = clock.currentTimeMillis();
		store.addDeletedNotice(ACCENTED, now);
		store.addDeletedNotice("", now);
		store.addDeletedNotice(ACCENTED, now + DAY);
		List<String> names = store.deletedNotices(now + DAY);
		assertNotNull(names);
		assertEquals(2, names.size());
		assertEquals("", names.get(0));
		assertEquals(ACCENTED, names.get(1));
		assertEquals("a name deleted again counts from the later day",
				Collections.singletonList(ACCENTED),
				store.deletedNotices(now + 30L * DAY));
		store.dismissDeletedNotice(ACCENTED, now + 30L * DAY);
		assertTrue(store.deletedNotices(now + 30L * DAY).isEmpty());
	}

	@Test
	public void aNoticeStoredWithoutADayLapsesThirtyDaysAfterItIsRead()
			throws Exception {
		Settings legacy = new Settings();
		legacy.put("names", "n" + java.util.Base64.getUrlEncoder()
				.withoutPadding().encodeToString("old".getBytes("UTF-8")));
		subscriber.settings.mergeSettings(legacy, NS_DELETED);
		ChannelSyncStatusStore store =
				new ChannelSyncStatusStore(subscriber.settings);
		long now = clock.currentTimeMillis();
		assertEquals(Collections.singletonList("old"),
				store.deletedNotices(now));
		assertEquals(Collections.singletonList("old"),
				store.deletedNotices(now + 29L * DAY));
		store.purgeDeletedNotices(now + 31L * DAY);
		assertTrue(store.deletedNotices(now + 31L * DAY).isEmpty());
	}
}
