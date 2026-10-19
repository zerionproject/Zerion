package com.professor.zerion.android.update;

import android.app.Application;

import com.professor.zerion.BuildConfig;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.transport.BridgeListStore;
import org.zerionproject.transport.BridgeListStore.Verdict;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.net.SocketFactory;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

@Singleton
@NotNullByDefault
public class BridgeListUpdater implements EventListener {

	static final long AFTER_SUCCESS_MS = DAYS.toMillis(1);
	static final long AFTER_SUCCESS_JITTER_MS = DAYS.toMillis(2);
	static final long AFTER_FAILURE_MS = HOURS.toMillis(3);
	static final long AFTER_FAILURE_JITTER_MS = HOURS.toMillis(3);
	static final long START_DELAY_MS = MINUTES.toMillis(1);
	static final long START_DELAY_JITTER_MS = MINUTES.toMillis(9);
	static final long TICK_MS = HOURS.toMillis(6);
	static final long MAX_AHEAD_MS =
			AFTER_SUCCESS_MS + AFTER_SUCCESS_JITTER_MS;

	interface Clock {
		long now();
	}

	private final PluginManager pluginManager;
	private final Executor ioExecutor;
	private final TaskScheduler scheduler;
	private final BridgeListStore store;
	private final BridgeListSource source;
	private final Clock clock;
	private final LongUnaryOperator jitter;
	private final long installedVersion;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean ticking = new AtomicBoolean(false);
	private final AtomicBoolean startPending = new AtomicBoolean(false);

	@Inject
	public BridgeListUpdater(Application app, EventBus eventBus,
			PluginManager pluginManager, SocketFactory torSockets,
			@IoExecutor Executor ioExecutor, TaskScheduler scheduler,
			BridgeListStore store) {
		this(eventBus, pluginManager, ioExecutor, scheduler, store,
				BridgeListSource.overTor(app, torSockets,
						BridgeListSource.URLS),
				System::currentTimeMillis, randomJitter(),
				BuildConfig.VERSION_CODE);
	}

	BridgeListUpdater(EventBus eventBus, PluginManager pluginManager,
			Executor ioExecutor, TaskScheduler scheduler,
			BridgeListStore store, BridgeListSource source, Clock clock,
			LongUnaryOperator jitter, long installedVersion) {
		this.pluginManager = pluginManager;
		this.ioExecutor = ioExecutor;
		this.scheduler = scheduler;
		this.store = store;
		this.source = source;
		this.clock = clock;
		this.jitter = jitter;
		this.installedVersion = installedVersion;
		eventBus.addListener(this);
	}

	private static LongUnaryOperator randomJitter() {
		SecureRandom random = new SecureRandom();
		return bound -> bound <= 0 ? 0
				: (long) (random.nextDouble() * bound);
	}

	@Override
	public void eventOccurred(Event e) {
		if (!(e instanceof TransportStateEvent)) return;
		TransportStateEvent t = (TransportStateEvent) e;
		if (!t.getTransportId().equals(TorConstants.ID)
				|| t.getState() != Plugin.State.ACTIVE) {
			return;
		}
		if (ticking.compareAndSet(false, true)) {
			scheduler.scheduleWithFixedDelay(this::maybeUpdate, ioExecutor,
					TICK_MS, TICK_MS, MILLISECONDS);
		}
		if (startPending.compareAndSet(false, true)) {
			scheduler.schedule(this::delayedCheck, ioExecutor,
					START_DELAY_MS + jitter.applyAsLong(START_DELAY_JITTER_MS),
					MILLISECONDS);
		}
	}

	private void delayedCheck() {
		startPending.set(false);
		maybeUpdate();
	}

	void maybeUpdate() {
		if (!torActive()) return;
		if (!running.compareAndSet(false, true)) return;
		try {
			update();
		} finally {
			running.set(false);
		}
	}

	private void update() {
		BridgeListSource.Fetched local;
		try {
			local = source.local();
		} catch (IOException | RuntimeException e) {
			local = null;
		}
		if (local != null) {
			store.offer(local.list, local.signature, true);
			return;
		}
		if (!store.builtInBridgesRecent()) return;
		BridgeListStore.FetchState state = store.fetchState();
		if (state == null) return;
		long now = clock.now();
		boolean updated = state.fetchedVersion != installedVersion;
		boolean waiting = now < state.nextFetchMs
				&& state.nextFetchMs - now <= MAX_AHEAD_MS;
		if (!updated && waiting) return;
		boolean answered;
		try {
			BridgeListSource.Fetched f = source.remote();
			answered = f != null && settled(store.offer(f.list, f.signature,
					false));
		} catch (IOException | RuntimeException e) {
			answered = false;
		}
		long next = answered
				? now + AFTER_SUCCESS_MS
				+ jitter.applyAsLong(AFTER_SUCCESS_JITTER_MS)
				: now + AFTER_FAILURE_MS
				+ jitter.applyAsLong(AFTER_FAILURE_JITTER_MS);
		store.saveFetchState(next, installedVersion);
	}

	private static boolean settled(Verdict v) {
		return v == Verdict.ACCEPTED || v == Verdict.NOT_NEWER
				|| v == Verdict.EXPIRED;
	}

	private boolean torActive() {
		Plugin p = pluginManager.getPlugin(TorConstants.ID);
		return p != null && p.getState() == Plugin.State.ACTIVE;
	}
}
