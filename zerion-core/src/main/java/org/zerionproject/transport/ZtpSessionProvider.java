package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ZtpSessionProvider {

	int recogniseIncoming(byte[] tag);

	@Nullable
	StoredContactSession getStoredSession(int contactId);

	void sessionClosed(int contactId);

	default void sessionEstablished(int contactId) {
	}
}
