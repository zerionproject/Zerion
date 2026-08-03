package org.zerionproject.core;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@NotNullByDefault
public class TimeLoggingExecutor extends ThreadPoolExecutor {

	public TimeLoggingExecutor(String tag, int corePoolSize, int maxPoolSize,
			long keepAliveTime, TimeUnit unit,
			BlockingQueue<Runnable> workQueue,
			ThreadFactory threadFactory,
			RejectedExecutionHandler handler) {
		super(corePoolSize, maxPoolSize, keepAliveTime, unit, workQueue,
				threadFactory, handler);
	}

	@Override
	public void execute(Runnable r) {
		super.execute(r);
	}
}
