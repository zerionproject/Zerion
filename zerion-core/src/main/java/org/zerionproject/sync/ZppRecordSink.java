package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ZppRecordSink {

	void deliver(int contactId, long sessionId, int type, byte[] payload);

	default void onConnected(int contactId, long sessionId) {
	}

	void onDisconnected(int contactId, long sessionId);
}
