package org.briarproject.bramble.api.system;

import org.briarproject.bramble.api.Cancellable;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@NotNullByDefault
public interface TaskScheduler {

	Cancellable schedule(Runnable task, Executor executor, long delay,
			TimeUnit unit);

	Cancellable scheduleWithFixedDelay(Runnable task, Executor executor,
			long delay, long interval, TimeUnit unit);

}
