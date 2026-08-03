package org.zerionproject.core.api.system;

public interface Clock {

	long MIN_REASONABLE_TIME_MS = 1_609_459_200_000L;

	long MAX_REASONABLE_TIME_MS = 4_765_132_800_000L;

	long currentTimeMillis();

	void sleep(long milliseconds) throws InterruptedException;
}
