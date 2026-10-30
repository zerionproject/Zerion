package org.zerionproject.core.contact.request;

import org.zerionproject.core.api.system.Clock;

public final class SettableClock implements Clock {

	public volatile long now;

	public SettableClock(long now) {
		this.now = now;
	}

	@Override
	public long currentTimeMillis() {
		return now;
	}

	@Override
	public void sleep(long milliseconds) {
		now += milliseconds;
	}
}
