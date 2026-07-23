package org.zerionproject.core.io;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.io.TimeoutMonitor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.system.Wakeful;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.concurrent.GuardedBy;
import javax.inject.Inject;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
class TimeoutMonitorImpl implements TimeoutMonitor {
	private static final long CHECK_INTERVAL_MS = SECONDS.toMillis(10);

	private final TaskScheduler scheduler;
	private final Executor ioExecutor;
	private final Clock clock;
	private final Object lock = new Object();
	@GuardedBy("lock")
	private final List<TimeoutInputStream> streams = new ArrayList<>();

	@GuardedBy("lock")
	private Cancellable cancellable = null;

	@Inject
	TimeoutMonitorImpl(TaskScheduler scheduler,
			@IoExecutor Executor ioExecutor, Clock clock) {
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		this.clock = clock;
	}

	@Override
	public InputStream createTimeoutInputStream(InputStream in,
			long timeoutMs) {
		TimeoutInputStream stream = new TimeoutInputStream(clock, in,
				timeoutMs, this::removeStream);
		synchronized (lock) {
			if (streams.isEmpty()) {
				cancellable = scheduler.scheduleWithFixedDelay(
						this::checkTimeouts, ioExecutor, CHECK_INTERVAL_MS,
						CHECK_INTERVAL_MS, MILLISECONDS);
			}
			streams.add(stream);
		}
		return stream;
	}

	private void removeStream(TimeoutInputStream stream) {
		Cancellable toCancel = null;
		synchronized (lock) {
			if (streams.remove(stream) && streams.isEmpty()) {
				toCancel = cancellable;
				cancellable = null;
			}
		}
		if (toCancel != null) {
			toCancel.cancel();
		}
	}

	@IoExecutor
	@Wakeful
	private void checkTimeouts() {
		List<TimeoutInputStream> snapshot;
		synchronized (lock) {
			snapshot = new ArrayList<>(streams);
		}
		for (TimeoutInputStream stream : snapshot) {
			if (stream.hasTimedOut()) {
				try {
					stream.close();
				} catch (IOException e) {
				}
			}
		}
	}
}
