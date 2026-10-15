package org.zerionproject.core.db;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.event.PurgeRequestedEvent;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

@ThreadSafe
@NotNullByDefault
class DeletedContentPurger implements EventListener {

	static final long PURGE_DELAY_MS = 1000;
	static final long RETRY_DELAY_MS = SECONDS.toMillis(30);
	static final long MIN_INTERVAL_MS = SECONDS.toMillis(10);

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final TaskScheduler taskScheduler;
	private final Clock clock;
	private final Object lock = new Object();

	@GuardedBy("lock")
	private boolean scheduled = false;
	@GuardedBy("lock")
	private long lastPurgeAt = Long.MIN_VALUE;

	DeletedContentPurger(DatabaseComponent db, Executor dbExecutor,
			TaskScheduler taskScheduler, Clock clock) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.taskScheduler = taskScheduler;
		this.clock = clock;
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof PurgeRequestedEvent) schedule(PURGE_DELAY_MS);
	}

	private void schedule(long minDelay) {
		long delay;
		synchronized (lock) {
			if (scheduled) return;
			scheduled = true;
			delay = minDelay;
			if (lastPurgeAt != Long.MIN_VALUE) {
				long wait = lastPurgeAt + MIN_INTERVAL_MS
						- clock.currentTimeMillis();
				if (wait > MIN_INTERVAL_MS) wait = MIN_INTERVAL_MS;
				if (wait > delay) delay = wait;
			}
		}
		try {
			taskScheduler.schedule(this::purge, dbExecutor, delay,
					MILLISECONDS);
		} catch (RejectedExecutionException e) {
			synchronized (lock) {
				scheduled = false;
			}
		}
	}

	private void purge() {
		synchronized (lock) {
			scheduled = false;
			lastPurgeAt = clock.currentTimeMillis();
		}
		boolean complete;
		try {
			complete = db.purgeDeletedContent();
		} catch (DbException | RuntimeException e) {
			complete = false;
		}
		if (!complete) schedule(RETRY_DELAY_MS);
	}
}
