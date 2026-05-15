package org.briarproject.bramble.api.plugin;

public interface Backoff {

	int getPollingInterval();

	void increment();

	void reset();
}
