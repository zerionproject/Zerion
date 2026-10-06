package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

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
