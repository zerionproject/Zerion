package org.zerionproject.core.api.connection;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface InterruptibleConnection {

	void interruptOutgoingSession();

	void forceClose();
}
