package org.zerionproject.core;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Executor;
import javax.annotation.concurrent.GuardedBy;

@NotNullByDefault
public class PoliteExecutor implements Executor {

	private final Object lock = new Object();
	@GuardedBy("lock")
	private final Queue<Runnable> queue = new LinkedList<>();
	private final Executor delegate;
	private final int maxConcurrentTasks;
	@GuardedBy("lock")
	private int concurrentTasks = 0;

	public PoliteExecutor(String tag, Executor delegate,
			int maxConcurrentTasks) {
		this.delegate = delegate;
		this.maxConcurrentTasks = maxConcurrentTasks;
	}

	@Override
	public void execute(Runnable r) {
		Runnable wrapped = () -> {
			try {
				r.run();
			} finally {
				scheduleNext();
			}
		};
		synchronized (lock) {
			if (concurrentTasks < maxConcurrentTasks) {
				concurrentTasks++;
				delegate.execute(wrapped);
			} else {
				queue.add(wrapped);
			}
		}
	}

	private void scheduleNext() {
		synchronized (lock) {
			Runnable next = queue.poll();
			if (next == null) concurrentTasks--;
			else delegate.execute(next);
		}
	}
}
