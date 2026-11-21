package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.system.TaskScheduler;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Decides when the Tor wake lock is held. The lock is held while Tor starts,
 * bootstraps or stops, while an onion service descriptor is being published,
 * for a short time after every Tor control event or piece of connection
 * activity, while a call runs, while the app is in the foreground and while
 * the "Stay awake for Tor" setting is on. Otherwise it is released so the
 * device can suspend, and a heartbeat takes it briefly every ten minutes so
 * the timers inside Tor get to run.
 * <p>
 * Clock contract: the injected clock returns milliseconds and must keep
 * advancing while the device is suspended (on Android
 * {@code SystemClock.elapsedRealtime()}), otherwise a window that started
 * before a sleep would outlive it. The default clock, based on
 * {@code System.nanoTime()}, is for tests and for platforms that do not
 * suspend.
 * <p>
 * Every call into the wake lock and the task scheduler is guarded, so an
 * exception there (for example after shutdown) never reaches the caller,
 * which may be the Tor control connection's parse thread.
 */
@ThreadSafe
@NotNullByDefault
public class TorWakePolicy implements TorActivitySink {

	public interface Lock {

		void acquire();

		void release();
	}

	static final long NETWORK_ENABLED_HOLD_MS = 90_000;
	static final long BOOTSTRAP_GRACE_MS = 60_000;
	static final long BOOTSTRAP_HOLD_CAP_MS = 10 * 60_000;
	static final long PUBLISH_CAP_MS = 120_000;
	static final long PUBLISH_BURST_CEILING_MS = 300_000;
	static final long TOR_EVENT_HOLD_MS = 30_000;
	static final long BRIEF_EVENT_HOLD_MS = 5_000;
	static final long KEEPALIVE_HOLD_MS = 2_000;
	static final long CONNECTION_HOLD_MS = 60_000;
	static final long DIAL_HOLD_MS = 15_000;
	static final long HEARTBEAT_INTERVAL_MS = 10 * 60_000;
	static final long HEARTBEAT_HOLD_MS = 5_000;

	private static final long NEVER = Long.MIN_VALUE;

	private final TaskScheduler scheduler;
	private final Executor executor;
	private final LongSupplier clock;
	private final Object lock = new Object();

	@GuardedBy("lock")
	@Nullable
	private Lock wakeLock = null;
	@GuardedBy("lock")
	private boolean held = false;
	@GuardedBy("lock")
	private boolean starting = false;
	@GuardedBy("lock")
	private boolean stopping = false;
	@GuardedBy("lock")
	private boolean networkEnabled = false;
	@GuardedBy("lock")
	private boolean bootstrapping = false;
	@GuardedBy("lock")
	private boolean keepAwake = false;
	@GuardedBy("lock")
	private boolean foreground = false;
	@GuardedBy("lock")
	private boolean callInProgress = false;
	@GuardedBy("lock")
	private int bootstrapPercentage = 0;
	@GuardedBy("lock")
	private long startedUntil = NEVER;
	@GuardedBy("lock")
	private long networkEnabledAt = NEVER;
	@GuardedBy("lock")
	private long bootstrapGraceUntil = NEVER;
	@GuardedBy("lock")
	private long torEventUntil = NEVER;
	@GuardedBy("lock")
	private long briefEventUntil = NEVER;
	@GuardedBy("lock")
	private long keepaliveUntil = NEVER;
	@GuardedBy("lock")
	private long connectionUntil = NEVER;
	@GuardedBy("lock")
	private long dialUntil = NEVER;
	@GuardedBy("lock")
	private long publishBurstStart = NEVER;
	@GuardedBy("lock")
	private long publishCapUntil = NEVER;
	@GuardedBy("lock")
	private long heartbeatUntil = NEVER;
	@GuardedBy("lock")
	private final Map<String, Integer> uploadsInFlight = new HashMap<>();
	@GuardedBy("lock")
	private final Set<String> confirmedOnions = new HashSet<>();
	@GuardedBy("lock")
	@Nullable
	private Cancellable heartbeatTask = null;
	@GuardedBy("lock")
	@Nullable
	private Cancellable checkTask = null;
	@GuardedBy("lock")
	private long checkGeneration = 0;
	@GuardedBy("lock")
	private long checkDueAt = NEVER;

	public TorWakePolicy(TaskScheduler scheduler, Executor executor) {
		this(scheduler, executor, () -> System.nanoTime() / 1_000_000L);
	}

	public TorWakePolicy(TaskScheduler scheduler, Executor executor,
			LongSupplier clock) {
		this.scheduler = scheduler;
		this.executor = executor;
		this.clock = clock;
	}

	/**
	 * Hands the policy the wake lock it drives, replacing any earlier one.
	 * If the policy currently holds the lock, the earlier lock is released
	 * and the new one acquired. Until a lock is attached the policy only
	 * keeps its bookkeeping.
	 */
	public void attachLock(Lock wakeLock) {
		synchronized (lock) {
			Lock old = this.wakeLock;
			this.wakeLock = wakeLock;
			if (!held) return;
			if (old != null) {
				try {
					old.release();
				} catch (RuntimeException ignored) {
				}
			}
			try {
				wakeLock.acquire();
			} catch (RuntimeException e) {
				held = false;
				evaluate();
			}
		}
	}

	public boolean isHeld() {
		synchronized (lock) {
			return held;
		}
	}

	/**
	 * True while nothing a person is doing asks for the device to stay
	 * awake: the network is enabled, Tor is neither starting nor stopping,
	 * the app is not in the foreground, no call runs, the "Stay awake for
	 * Tor" setting is off and no connection has carried or queued a record
	 * in the last {@link #CONNECTION_HOLD_MS}. A dial in progress, Tor's
	 * own control events and a keepalive echo do not count, so a dial that
	 * fails does not make the device look busy to the poller that decides
	 * how soon to retry.
	 */
	public boolean isQuiet() {
		synchronized (lock) {
			return networkEnabled && !starting && !stopping && !foreground
					&& !callInProgress && !keepAwake
					&& clock.getAsLong() >= connectionUntil;
		}
	}

	public void onStarting() {
		synchronized (lock) {
			starting = true;
			evaluate();
		}
	}

	public void onStartFinished() {
		synchronized (lock) {
			starting = false;
			startedUntil = clock.getAsLong() + TOR_EVENT_HOLD_MS;
			evaluate();
		}
	}

	/**
	 * @return true if this call changed the network from disabled to enabled
	 */
	public boolean onNetworkEnabled(boolean enabled) {
		synchronized (lock) {
			boolean changed = false;
			if (enabled) {
				if (!networkEnabled) {
					networkEnabled = true;
					networkEnabledAt = clock.getAsLong();
					bootstrapping = bootstrapPercentage < 100;
					changed = true;
				}
				startHeartbeat();
			} else {
				networkEnabled = false;
				clearWindows();
				cancelHeartbeat();
			}
			evaluate();
			return changed;
		}
	}

	public void onBootstrapProgress(int percentage) {
		synchronized (lock) {
			bootstrapPercentage = percentage;
			if (percentage >= 100) {
				if (bootstrapping) {
					bootstrapping = false;
					bootstrapGraceUntil =
							clock.getAsLong() + BOOTSTRAP_GRACE_MS;
				}
			} else if (networkEnabled) {
				bootstrapping = true;
			}
			evaluate();
		}
	}

	/**
	 * A relay connection event arrived over the control connection: Tor is
	 * opening or has opened a connection to a guard or bridge, which takes
	 * several round trips that Tor drives from its own timers.
	 */
	public void onTorEvent() {
		synchronized (lock) {
			torEventUntil = clock.getAsLong() + TOR_EVENT_HOLD_MS;
			evaluate();
		}
	}

	/**
	 * A circuit, stream or descriptor event arrived over the control
	 * connection. The steps that follow such an event are replies to packets
	 * that have just woken the device, so the hold only has to bridge the
	 * gap to the next step: a few seconds. Tor's own timer-driven retries
	 * wait for the next wakeup or the heartbeat. Tor builds spare circuits
	 * for itself about once a minute while it is in use, and every dial of
	 * the app produces stream and descriptor events of its own, so a longer
	 * hold here kept the processor awake about half the time.
	 */
	public void onBriefTorEvent() {
		synchronized (lock) {
			briefEventUntil = clock.getAsLong() + BRIEF_EVENT_HOLD_MS;
			evaluate();
		}
	}

	/**
	 * Tor has started uploading a descriptor of the given onion service to a
	 * directory. The lock is held for the uploads of an onion until the first
	 * burst of them in which one succeeded has finished since the network was
	 * enabled: until then nobody can reach the service, and the device may
	 * have gone to sleep right after publishing it. Tor refreshes every
	 * published descriptor about once an hour and whenever an introduction
	 * point changes, and each invite-link inbox is an onion of its own, so
	 * later uploads count only as the brief descriptor events they are: the
	 * old descriptor stays valid, and Tor finishes a refresh within seconds
	 * while the device is awake. A directory that never answers would
	 * otherwise hold the lock for the whole publish cap on every refresh.
	 */
	public void onDescriptorUpload(String onion) {
		synchronized (lock) {
			if (confirmedOnions.contains(onion)
					&& !uploadsInFlight.containsKey(onion)) {
				return;
			}
			long now = clock.getAsLong();
			Integer count = uploadsInFlight.get(onion);
			uploadsInFlight.put(onion, count == null ? 1 : count + 1);
			if (publishBurstStart == NEVER) publishBurstStart = now;
			publishCapUntil = Math.min(now + PUBLISH_CAP_MS,
					publishBurstStart + PUBLISH_BURST_CEILING_MS);
			evaluate();
		}
	}

	public void onDescriptorUploadFinished(String onion, boolean uploaded) {
		synchronized (lock) {
			if (uploaded) confirmedOnions.add(onion);
			Integer count = uploadsInFlight.get(onion);
			if (count != null) {
				if (count <= 1) uploadsInFlight.remove(onion);
				else uploadsInFlight.put(onion, count - 1);
			}
			if (uploadsInFlight.isEmpty()) clearPublish();
			evaluate();
		}
	}

	public void onStopping() {
		synchronized (lock) {
			stopping = true;
			evaluate();
		}
	}

	public void onStopped() {
		synchronized (lock) {
			stopping = false;
			starting = false;
			startedUntil = NEVER;
			networkEnabled = false;
			bootstrapPercentage = 0;
			clearWindows();
			cancelHeartbeat();
			evaluate();
		}
	}

	@Override
	public void noteActivity(Activity activity) {
		synchronized (lock) {
			long now = clock.getAsLong();
			if (activity == Activity.CONNECTION_ATTEMPT) {
				dialUntil = now + DIAL_HOLD_MS;
			} else if (activity == Activity.KEEPALIVE) {
				keepaliveUntil = now + KEEPALIVE_HOLD_MS;
			} else {
				connectionUntil = now + CONNECTION_HOLD_MS;
			}
			evaluate();
		}
	}

	public void setCallInProgress(boolean inProgress) {
		synchronized (lock) {
			callInProgress = inProgress;
			evaluate();
		}
	}

	public void setForeground(boolean inForeground) {
		synchronized (lock) {
			foreground = inForeground;
			evaluate();
		}
	}

	public void setKeepAwake(boolean keepAwake) {
		synchronized (lock) {
			this.keepAwake = keepAwake;
			evaluate();
		}
	}

	private void heartbeat() {
		synchronized (lock) {
			try {
				if (!networkEnabled || held) return;
				heartbeatUntil = clock.getAsLong() + HEARTBEAT_HOLD_MS;
				evaluate();
			} catch (RuntimeException ignored) {
			}
		}
	}

	/**
	 * Runs a scheduled check. A check that was cancelled after it had
	 * already been handed to its executor still runs; it must not clear the
	 * reference to a newer check, so the reference is cleared only by the
	 * generation that set it.
	 */
	private void check(long generation) {
		synchronized (lock) {
			if (generation == checkGeneration) checkTask = null;
			evaluate();
		}
	}

	@GuardedBy("lock")
	private void cancelCheck() {
		Cancellable task = checkTask;
		checkTask = null;
		checkGeneration++;
		if (task == null) return;
		try {
			task.cancel();
		} catch (RuntimeException ignored) {
		}
	}

	@GuardedBy("lock")
	private void startHeartbeat() {
		if (heartbeatTask != null) return;
		try {
			heartbeatTask = scheduler.scheduleWithFixedDelay(this::heartbeat,
					executor, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS,
					MILLISECONDS);
		} catch (RuntimeException ignored) {
		}
	}

	@GuardedBy("lock")
	private void clearWindows() {
		bootstrapping = false;
		bootstrapGraceUntil = NEVER;
		torEventUntil = NEVER;
		briefEventUntil = NEVER;
		keepaliveUntil = NEVER;
		connectionUntil = NEVER;
		dialUntil = NEVER;
		heartbeatUntil = NEVER;
		confirmedOnions.clear();
		clearPublish();
	}

	@GuardedBy("lock")
	private void clearPublish() {
		publishBurstStart = NEVER;
		publishCapUntil = NEVER;
		uploadsInFlight.clear();
	}

	@GuardedBy("lock")
	private void cancelHeartbeat() {
		Cancellable task = heartbeatTask;
		if (task != null) {
			heartbeatTask = null;
			try {
				task.cancel();
			} catch (RuntimeException ignored) {
			}
		}
	}

	@GuardedBy("lock")
	private void evaluate() {
		long now = clock.getAsLong();
		if (publishCapUntil != NEVER && now >= publishCapUntil) clearPublish();
		boolean want = shouldHold(now);
		if (want != held) {
			Lock l = wakeLock;
			if (l == null) {
				held = want;
			} else {
				try {
					if (want) l.acquire();
					else l.release();
					held = want;
				} catch (RuntimeException ignored) {
				}
			}
		}
		if (!held) {
			cancelCheck();
			return;
		}
		long deadline = nextDeadline(now);
		if (deadline == NEVER) {
			cancelCheck();
			return;
		}
		if (checkTask != null && deadline >= checkDueAt) return;
		try {
			Cancellable previous = checkTask;
			long generation = checkGeneration + 1;
			checkTask = scheduler.schedule(() -> check(generation), executor,
					Math.max(1, deadline - now), MILLISECONDS);
			checkGeneration = generation;
			checkDueAt = deadline;
			if (previous != null) {
				try {
					previous.cancel();
				} catch (RuntimeException ignored) {
				}
			}
		} catch (RuntimeException ignored) {
		}
	}

	@GuardedBy("lock")
	private boolean shouldHold(long now) {
		if (starting || stopping) return true;
		if (now < startedUntil) return true;
		if (!networkEnabled) return false;
		if (keepAwake || foreground || callInProgress) return true;
		if (bootstrapping && now < networkEnabledAt + BOOTSTRAP_HOLD_CAP_MS) {
			return true;
		}
		if (now < networkEnabledAt + NETWORK_ENABLED_HOLD_MS) return true;
		if (now < bootstrapGraceUntil) return true;
		if (!uploadsInFlight.isEmpty() && now < publishCapUntil) return true;
		if (now < torEventUntil) return true;
		if (now < briefEventUntil) return true;
		if (now < keepaliveUntil) return true;
		if (now < connectionUntil) return true;
		if (now < dialUntil) return true;
		return now < heartbeatUntil;
	}

	@GuardedBy("lock")
	private long nextDeadline(long now) {
		if (starting || stopping) return NEVER;
		if (!networkEnabled) return earliest(NEVER, now, startedUntil);
		if (keepAwake || foreground || callInProgress) return NEVER;
		long deadline = earliest(NEVER, now, startedUntil);
		if (bootstrapping) {
			deadline = earliest(deadline, now,
					networkEnabledAt + BOOTSTRAP_HOLD_CAP_MS);
		}
		deadline = earliest(deadline, now,
				networkEnabledAt + NETWORK_ENABLED_HOLD_MS);
		deadline = earliest(deadline, now, bootstrapGraceUntil);
		if (!uploadsInFlight.isEmpty()) {
			deadline = earliest(deadline, now, publishCapUntil);
		}
		deadline = earliest(deadline, now, torEventUntil);
		deadline = earliest(deadline, now, briefEventUntil);
		deadline = earliest(deadline, now, keepaliveUntil);
		deadline = earliest(deadline, now, connectionUntil);
		deadline = earliest(deadline, now, dialUntil);
		deadline = earliest(deadline, now, heartbeatUntil);
		return deadline;
	}

	private static long earliest(long current, long now, long candidate) {
		if (candidate <= now) return current;
		if (current == NEVER || candidate < current) return candidate;
		return current;
	}
}
