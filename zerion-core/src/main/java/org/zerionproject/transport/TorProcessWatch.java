package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Carries the one signal the Tor wrapper library does not act on: the loss
 * of its control connection while Tor is supposed to be running, which is
 * how the death of the tor child process shows up. The platform wrapper
 * reports it here and the transport reacts by restarting Tor.
 */
@ThreadSafe
@NotNullByDefault
public final class TorProcessWatch {

	@Nullable
	private volatile Runnable listener;

	public void setListener(@Nullable Runnable listener) {
		this.listener = listener;
	}

	public void controlConnectionLost() {
		Runnable l = listener;
		if (l != null) l.run();
	}
}
