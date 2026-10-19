package org.zerionproject.transport;

import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.system.TaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

class NoScheduler implements TaskScheduler {

	@Override
	public Cancellable schedule(Runnable task, Executor executor, long delay,
			TimeUnit unit) {
		return () -> {
		};
	}

	@Override
	public Cancellable scheduleWithFixedDelay(Runnable task, Executor executor,
			long delay, long interval, TimeUnit unit) {
		return () -> {
		};
	}
}
