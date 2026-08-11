package org.zerionproject.core.api.plugin;

public interface Backoff {

	int getPollingInterval();

	void increment();

	void reset();
}
