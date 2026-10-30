package org.zerionproject.core.contact.request;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.system.TaskScheduler;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class TestScheduler implements TaskScheduler {

	public final List<Long> delaysMs = new CopyOnWriteArrayList<>();
	public final List<Map.Entry<Long, Runnable>> heldOneShots =
			new CopyOnWriteArrayList<>();
	private final ScheduledExecutorService exec =
			new ScheduledThreadPoolExecutor(2);
	public volatile boolean runOneShots = true;
	public volatile boolean runRepeating = false;

	@Override
	public Cancellable schedule(Runnable task, Executor executor, long delay,
			TimeUnit unit) {
		delaysMs.add(unit.toMillis(delay));
		if (!runOneShots) {
			heldOneShots.add(new AbstractMap.SimpleEntry<>(
					unit.toMillis(delay), () -> executor.execute(task)));
			return () -> {
			};
		}
		ScheduledFuture<?> f = exec.schedule(() -> executor.execute(task),
				delay, unit);
		return () -> f.cancel(false);
	}

	@Override
	public Cancellable scheduleWithFixedDelay(Runnable task,
			Executor executor, long delay, long interval, TimeUnit unit) {
		if (!runRepeating) return () -> {
		};
		ScheduledFuture<?> f = exec.scheduleWithFixedDelay(
				() -> executor.execute(task), delay, interval, unit);
		return () -> f.cancel(false);
	}

	public void runHeld(long delayMs) {
		for (Map.Entry<Long, Runnable> e : heldOneShots) {
			if (e.getKey() == delayMs) {
				heldOneShots.remove(e);
				e.getValue().run();
			}
		}
	}

	public void shutdown() {
		exec.shutdownNow();
	}
}
