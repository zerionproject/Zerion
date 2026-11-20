package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.system.TaskScheduler;

import java.util.HashMap;
import java.util.Map;
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
	static final long TOR_EVENT_HOLD_MS = 30_000;
	static final long CONNECTION_HOLD_MS = 60_000;
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
	private long connectionUntil = NEVER;
	@GuardedBy("lock")
	private long publishCapUntil = NEVER;
	@GuardedBy("lock")
	private long heartbeatUntil = NEVER;
	@GuardedBy("lock")
	private final Map<String, Integer> uploadsInFlight = new HashMap<>();
	@GuardedBy("lock")
	@Nullable
	private Cancellable heartbeatTask = null;
	@GuardedBy("lock")
	private boolean checkPending = false;
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
	 * Hands the policy the wake lock it drives. Until a lock is attached the
	 * policy only keeps its bookkeeping.
	 */
	public void attachLock(Lock wakeLock) {
		synchronized (lock) {
			if (this.wakeLock != null) throw new IllegalStateException();
			this.wakeLock = wakeLock;
			if (held) wakeLock.acquire();
		}
	}

	public boolean isHeld() {
		synchronized (lock) {
			return held;
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

	public void onNetworkEnabled(boolean enabled) {
		synchronized (lock) {
			if (enabled) {
				if (!networkEnabled) {
					networkEnabled = true;
					networkEnabledAt = clock.getAsLong();
					bootstrapping = bootstrapPercentage < 100;
				}
				if (heartbeatTask == null) {
					heartbeatTask = scheduler.scheduleWithFixedDelay(
							this::heartbeat, executor, HEARTBEAT_INTERVAL_MS,
							HEARTBEAT_INTERVAL_MS, MILLISECONDS);
				}
			} else {
				networkEnabled = false;
				clearWindows();
				cancelHeartbeat();
			}
			evaluate();
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
	 * A circuit, stream, relay connection or descriptor event arrived over
	 * the control connection.
	 */
	public void onTorEvent() {
		synchronized (lock) {
			torEventUntil = clock.getAsLong() + TOR_EVENT_HOLD_MS;
			evaluate();
		}
	}

	public void onDescriptorUpload(String onion) {
		synchronized (lock) {
			long now = clock.getAsLong();
			Integer count = uploadsInFlight.get(onion);
			uploadsInFlight.put(onion, count == null ? 1 : count + 1);
			if (now >= publishCapUntil) publishCapUntil = now + PUBLISH_CAP_MS;
			evaluate();
		}
	}

	public void onDescriptorUploadFinished(String onion) {
		synchronized (lock) {
			Integer count = uploadsInFlight.get(onion);
			if (count != null) {
				if (count <= 1) uploadsInFlight.remove(onion);
				else uploadsInFlight.put(onion, count - 1);
			}
			if (uploadsInFlight.isEmpty()) publishCapUntil = NEVER;
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
			connectionUntil = clock.getAsLong() + CONNECTION_HOLD_MS;
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
			if (!networkEnabled || held) return;
			heartbeatUntil = clock.getAsLong() + HEARTBEAT_HOLD_MS;
			evaluate();
		}
	}

	private void check() {
		synchronized (lock) {
			checkPending = false;
			evaluate();
		}
	}

	@GuardedBy("lock")
	private void clearWindows() {
		bootstrapping = false;
		bootstrapGraceUntil = NEVER;
		torEventUntil = NEVER;
		connectionUntil = NEVER;
		publishCapUntil = NEVER;
		heartbeatUntil = NEVER;
		uploadsInFlight.clear();
	}

	@GuardedBy("lock")
	private void cancelHeartbeat() {
		Cancellable task = heartbeatTask;
		if (task != null) {
			heartbeatTask = null;
			task.cancel();
		}
	}

	@GuardedBy("lock")
	private void evaluate() {
		long now = clock.getAsLong();
		if (publishCapUntil != NEVER && now >= publishCapUntil) {
			publishCapUntil = NEVER;
			uploadsInFlight.clear();
		}
		boolean want = shouldHold(now);
		if (want != held) {
			held = want;
			Lock l = wakeLock;
			if (l != null) {
				if (want) l.acquire();
				else l.release();
			}
		}
		if (!held) return;
		long deadline = nextDeadline(now);
		if (deadline == NEVER) return;
		if (checkPending && deadline >= checkDueAt) return;
		checkPending = true;
		checkDueAt = deadline;
		scheduler.schedule(this::check, executor,
				Math.max(1, deadline - now), MILLISECONDS);
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
		if (now < connectionUntil) return true;
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
		deadline = earliest(deadline, now, connectionUntil);
		deadline = earliest(deadline, now, heartbeatUntil);
		return deadline;
	}

	private static long earliest(long current, long now, long candidate) {
		if (candidate <= now) return current;
		if (current == NEVER || candidate < current) return candidate;
		return current;
	}
}
