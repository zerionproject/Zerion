package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Resolves a transport connection to a contact and persists the post-quantum
 * ratchet state across connections. This is the seam between the transport and
 * the contact/identity database: the transport knows about sockets and tags, and
 * this provider knows about contacts, their stored root keys, roles, and Mode
 * 3-Full state.
 */
@NotNullByDefault
public interface ZtpSessionProvider {

	/**
	 * Recognises a peeked stream tag to an established contact, or returns a
	 * value {@code < 0} if the tag matches no known contact. This is a read-only
	 * lookup; it must not advance any replay/reorder counter (the connection
	 * commits the stream id once the first frame authenticates).
	 */
	int recogniseIncoming(byte[] tag);

	/**
	 * The stored inputs to resume {@code contactId}, or {@code null} if there is
	 * no established session for it (the contact must be paired first).
	 */
	@Nullable
	StoredContactSession getStoredSession(int contactId);

	/**
	 * Persists the Mode 3-Full ratchet state after a connection to
	 * {@code contactId} has ended, so the next connection resumes from it.
	 */
	void saveMode3FullState(int contactId, Mode3FullState state);
}
