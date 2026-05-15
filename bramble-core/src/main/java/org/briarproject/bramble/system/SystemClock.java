package org.briarproject.bramble.system;

import org.briarproject.bramble.api.system.Clock;

public class SystemClock implements Clock {

	@Override
	public long currentTimeMillis() {
		return System.currentTimeMillis();
	}

	@Override
	public void sleep(long milliseconds) throws InterruptedException {
		Thread.sleep(milliseconds);
	}
}
