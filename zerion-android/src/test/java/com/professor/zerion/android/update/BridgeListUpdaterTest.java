package com.professor.zerion.android.update;

import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.tor.BridgeList;
import org.zerionproject.tor.BridgeListFixtures;
import org.zerionproject.tor.BridgeListFixtures.Signer;
import org.zerionproject.tor.BridgeListKeys;
import org.zerionproject.transport.BridgeListStore;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.zerionproject.tor.BridgeListFixtures.DAY_MS;
import static org.zerionproject.tor.BridgeListFixtures.HOUR_MS;

public class BridgeListUpdaterTest {

	private static final long TODAY = LocalDate.of(2026, 10, 18).toEpochDay();
	private static final long INSTALLED = 30018;

	private final Signer pinned = new Signer();
	private final Signer local = new Signer();
	private final MemorySettings settings = new MemorySettings();
	private long now = TODAY * DAY_MS + 8 * HOUR_MS;
	private final Clock storeClock = new Clock() {
		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	};
	private final BridgeListStore store = new BridgeListStore(settings,
			storeClock, new BridgeListKeys(
			Collections.singletonList(pinned.publicKey()),
			Collections.singletonList(local.publicKey())), () -> "account");
	private final PluginManager pluginManager = mock(PluginManager.class);
	private final Plugin tor = mock(Plugin.class);
	private final RecordingScheduler scheduler = new RecordingScheduler();
	private final List<EventListener> listeners = new ArrayList<>();
	private final FakeSource source = new FakeSource();
	private long jitterFraction = 0;
	private boolean bridgesUsed = true;

	private BridgeListUpdater updater() {
		when(pluginManager.getPlugin(TorConstants.ID)).thenReturn(tor);
		if (bridgesUsed) store.noteBuiltInBridges(true);
		EventBus bus = new EventBus() {
			@Override
			public void addListener(EventListener l) {
				listeners.add(l);
			}

			@Override
			public void removeListener(EventListener l) {
				listeners.remove(l);
			}

			@Override
			public void broadcast(Event e) {
				for (EventListener l : listeners) l.eventOccurred(e);
			}
		};
		return new BridgeListUpdater(bus, pluginManager, Runnable::run,
				scheduler, store, source, () -> now,
				bound -> bound * jitterFraction / 100, INSTALLED);
	}

	private void torIs(Plugin.State state) {
		when(tor.getState()).thenReturn(state);
	}

	private static byte[] list(long seq) {
		return BridgeListFixtures.bytes(BridgeListFixtures.json(seq,
				BridgeListFixtures.date(TODAY), BridgeListFixtures.date(
						TODAY + 90), BridgeListFixtures.groups(
						BridgeList.OBFS4_DEFAULT, Arrays.asList(
								BridgeListFixtures.obfs4(seq == 2 ? 1 : 9),
								BridgeListFixtures.obfs4(2),
								BridgeListFixtures.obfs4(3)))));
	}

	private BridgeListSource.Fetched signed(Signer s, long seq) {
		byte[] file = list(seq);
		return new BridgeListSource.Fetched(file, s.sign(file));
	}

	@Test
	public void nothingIsFetchedOrReadWhileTorIsNotConnected() {
		BridgeListUpdater u = updater();
		source.remote = signed(pinned, 2);
		source.local = signed(local, 3);
		for (Plugin.State s : new Plugin.State[] {Plugin.State.ENABLING,
				Plugin.State.INACTIVE, Plugin.State.DISABLED,
				Plugin.State.STARTING_STOPPING}) {
			torIs(s);
			u.maybeUpdate();
		}
		when(pluginManager.getPlugin(TorConstants.ID)).thenReturn(null);
		u.maybeUpdate();
		assertEquals(0, source.remoteCalls.get());
		assertEquals(0, source.localCalls.get());
		assertNull(store.current());
		assertEquals(0, store.fetchState().nextFetchMs);
		assertEquals(0, store.fetchState().fetchedVersion);
	}

	@Test
	public void nothingIsFetchedWhereBuiltInBridgesWereNeverUsed() {
		bridgesUsed = false;
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.remote = signed(pinned, 2);
		u.maybeUpdate();
		assertEquals("a direct connection never fetches the list", 0,
				source.remoteCalls.get());
		BridgeListStore.FetchState state = store.fetchState();
		assertEquals(0, state.nextFetchMs);
		assertEquals("the first use of bridges still fetches at once", 0,
				state.fetchedVersion);

		store.noteBuiltInBridges(true);
		u.maybeUpdate();
		assertEquals(1, source.remoteCalls.get());
	}

	@Test
	public void aFetchFollowsBridgeUseForThirtyDays() {
		bridgesUsed = false;
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.remote = signed(pinned, 2);
		store.noteBuiltInBridges(true);
		store.noteBuiltInBridges(false);
		now += 29 * DAY_MS;
		u.maybeUpdate();
		assertEquals("used 29 days ago", 1, source.remoteCalls.get());
		now += 2 * DAY_MS + BridgeListUpdater.MAX_AHEAD_MS;
		store.saveFetchState(0, INSTALLED);
		u.maybeUpdate();
		assertEquals("not after 31 days", 1, source.remoteCalls.get());
	}

	@Test
	public void aFirstRunFetchesOnceAndThenWaitsOneToThreeDays() {
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.remote = signed(pinned, 2);
		jitterFraction = 50;
		u.maybeUpdate();
		assertEquals(1, source.remoteCalls.get());
		assertNotNull(store.current());
		assertEquals(2, store.current().getSeq());
		BridgeListStore.FetchState state = store.fetchState();
		assertNotNull(state);
		assertEquals(INSTALLED, state.fetchedVersion);
		assertEquals(now + BridgeListUpdater.AFTER_SUCCESS_MS
				+ BridgeListUpdater.AFTER_SUCCESS_JITTER_MS / 2,
				state.nextFetchMs);

		now += DAY_MS;
		u.maybeUpdate();
		assertEquals("not due yet", 1, source.remoteCalls.get());
		now += DAY_MS;
		u.maybeUpdate();
		assertEquals(2, source.remoteCalls.get());
	}

	@Test
	public void anAppUpdateFetchesAtOnce() {
		store.saveFetchState(now + 10 * DAY_MS, INSTALLED - 1);
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.remote = signed(pinned, 2);
		u.maybeUpdate();
		assertEquals(1, source.remoteCalls.get());
		u.maybeUpdate();
		assertEquals("only once", 1, source.remoteCalls.get());
	}

	@Test
	public void aNextCheckFurtherAheadThanThreeDaysIsDueNow() {
		store.saveFetchState(now + 10 * DAY_MS, INSTALLED);
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.remote = signed(pinned, 2);
		u.maybeUpdate();
		assertEquals("a clock that ran ahead does not stop the checks", 1,
				source.remoteCalls.get());
		store.saveFetchState(now + 3 * DAY_MS, INSTALLED);
		u.maybeUpdate();
		assertEquals(1, source.remoteCalls.get());
	}

	@Test
	public void aFailedOrRejectedFetchIsRetriedWithinHours() {
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.fail = true;
		u.maybeUpdate();
		assertEquals(now + BridgeListUpdater.AFTER_FAILURE_MS,
				store.fetchState().nextFetchMs);

		source.fail = false;
		source.remote = signed(new Signer(), 2);
		now += BridgeListUpdater.AFTER_FAILURE_MS;
		u.maybeUpdate();
		assertNull("a list signed with another key is not taken",
				store.current());
		assertEquals(now + BridgeListUpdater.AFTER_FAILURE_MS,
				store.fetchState().nextFetchMs);

		source.remote = null;
		now += BridgeListUpdater.AFTER_FAILURE_MS;
		u.maybeUpdate();
		assertEquals("a missing file is retried soon too",
				now + BridgeListUpdater.AFTER_FAILURE_MS,
				store.fetchState().nextFetchMs);

		source.remote = signed(pinned, 1);
		now += BridgeListUpdater.AFTER_FAILURE_MS;
		u.maybeUpdate();
		assertEquals("the bundled list again is an answer",
				now + BridgeListUpdater.AFTER_SUCCESS_MS,
				store.fetchState().nextFetchMs);
		assertNull(store.current());
	}

	@Test
	public void anExpiredButGenuineListIsAnAnswerAndWaitsDays() {
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		byte[] file = BridgeListFixtures.bytes(BridgeListFixtures.json(5,
				BridgeListFixtures.date(TODAY - 100),
				BridgeListFixtures.date(TODAY - 10),
				BridgeListFixtures.groups(BridgeList.OBFS4_DEFAULT,
						Arrays.asList(BridgeListFixtures.obfs4(7)))));
		source.remote = new BridgeListSource.Fetched(file, pinned.sign(file));
		u.maybeUpdate();
		assertNull(store.current());
		assertEquals("a published list that expired is not retried every"
				+ " few hours", now + BridgeListUpdater.AFTER_SUCCESS_MS,
				store.fetchState().nextFetchMs);

		byte[] forged = file.clone();
		source.remote = new BridgeListSource.Fetched(file,
				new Signer().sign(forged));
		now += BridgeListUpdater.AFTER_SUCCESS_MS;
		u.maybeUpdate();
		assertEquals("a bad signature still counts as a failure",
				now + BridgeListUpdater.AFTER_FAILURE_MS,
				store.fetchState().nextFetchMs);
	}

	@Test
	public void aDownloadedListSignedWithTheLocalKeyIsRefused() {
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.remote = signed(local, 2);
		u.maybeUpdate();
		assertNull(store.current());
	}

	@Test
	public void aLocalTestFileIsTakenWithTheLocalKeyAndNothingIsDownloaded() {
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.local = signed(local, 3);
		source.remote = signed(pinned, 2);
		u.maybeUpdate();
		assertEquals(0, source.remoteCalls.get());
		assertEquals(3, store.current().getSeq());
		u.maybeUpdate();
		assertEquals(0, source.remoteCalls.get());
	}

	@Test
	public void neverTwoFetchesAtOnce() throws Exception {
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		source.remote = signed(pinned, 2);
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		source.inside = inside;
		source.release = release;
		Thread first = new Thread(u::maybeUpdate);
		first.start();
		assertTrue(inside.await(10, TimeUnit.SECONDS));
		source.inside = null;
		source.release = null;
		store.saveFetchState(0, 0);
		u.maybeUpdate();
		assertEquals(1, source.remoteCalls.get());
		release.countDown();
		first.join(10_000);
		assertEquals(1, source.remoteCalls.get());
	}

	@Test
	public void aConnectedTorStartsOneTickAndADelayedCheck() {
		BridgeListUpdater u = updater();
		torIs(Plugin.State.ACTIVE);
		jitterFraction = 100;
		TransportStateEvent active =
				new TransportStateEvent(TorConstants.ID, Plugin.State.ACTIVE);
		u.eventOccurred(active);
		u.eventOccurred(active);
		u.eventOccurred(new TransportStateEvent(TorConstants.ID,
				Plugin.State.ENABLING));
		assertEquals(1, scheduler.periodic.size());
		assertEquals(BridgeListUpdater.TICK_MS,
				(long) scheduler.periodic.get(0));
		assertEquals("flaps share one delayed check", 1,
				scheduler.once.size());
		assertEquals(BridgeListUpdater.START_DELAY_MS
				+ BridgeListUpdater.START_DELAY_JITTER_MS,
				(long) scheduler.once.get(0));
		assertEquals(0, source.remoteCalls.get());
		scheduler.runAll();
		assertEquals(1, source.remoteCalls.get());
		u.eventOccurred(active);
		assertEquals("once it ran, the next connection gets its own", 2,
				scheduler.once.size());
	}

	private static final class FakeSource implements BridgeListSource {
		@Nullable
		volatile Fetched local;
		@Nullable
		volatile Fetched remote;
		volatile boolean fail = false;
		@Nullable
		volatile CountDownLatch inside;
		@Nullable
		volatile CountDownLatch release;
		final AtomicInteger localCalls = new AtomicInteger();
		final AtomicInteger remoteCalls = new AtomicInteger();

		@Nullable
		@Override
		public Fetched local() {
			localCalls.incrementAndGet();
			return local;
		}

		@Nullable
		@Override
		public Fetched remote() throws IOException {
			remoteCalls.incrementAndGet();
			CountDownLatch in = inside;
			CountDownLatch out = release;
			if (in != null && out != null) {
				in.countDown();
				try {
					out.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
			}
			if (fail) throw new IOException("unreachable");
			return remote;
		}
	}

	private static final class RecordingScheduler implements TaskScheduler {
		final List<Long> once = new ArrayList<>();
		final List<Long> periodic = new ArrayList<>();
		final List<Runnable> tasks = new ArrayList<>();

		@Override
		public Cancellable schedule(Runnable task, Executor executor,
				long delay, TimeUnit unit) {
			once.add(unit.toMillis(delay));
			tasks.add(task);
			return () -> {
			};
		}

		@Override
		public Cancellable scheduleWithFixedDelay(Runnable task,
				Executor executor, long delay, long interval, TimeUnit unit) {
			periodic.add(unit.toMillis(interval));
			return () -> {
			};
		}

		void runAll() {
			for (Runnable r : new ArrayList<>(tasks)) r.run();
		}
	}

	static final class MemorySettings implements SettingsManager {
		final Map<String, Settings> byNamespace = new HashMap<>();

		@Override
		public Settings getSettings(String namespace) {
			Settings copy = new Settings();
			Settings s = byNamespace.get(namespace);
			if (s != null) copy.putAll(s);
			return copy;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public void mergeSettings(Settings s, String namespace) {
			Settings stored = byNamespace.get(namespace);
			if (stored == null) {
				stored = new Settings();
				byNamespace.put(namespace, stored);
			}
			stored.putAll(s);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) throws DbException {
			mergeSettings(s, namespace);
		}
	}
}
