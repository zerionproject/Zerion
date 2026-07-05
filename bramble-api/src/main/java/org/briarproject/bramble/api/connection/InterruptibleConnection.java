package org.briarproject.bramble.api.connection;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface InterruptibleConnection {

	void interruptOutgoingSession();

	void forceClose();
}
