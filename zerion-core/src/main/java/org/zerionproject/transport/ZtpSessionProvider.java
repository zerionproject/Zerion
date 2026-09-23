package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Resolves a transport connection to a contact and supplies the stored inputs
 * that every connection to an established contact starts from. This is the
 * seam between the transport and the contact/identity database: the transport
 * knows about sockets and tags, and this provider knows about contacts, their
 * stored root keys and roles.
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
	 * Called after a connection to {@code contactId} has ended, so the
	 * contact's tag window can advance past the streams the connection
	 * accepted. No ratchet state is persisted: the next connection starts
	 * a fresh Mode 3-Full ratchet.
	 */
	void sessionClosed(int contactId);

	/** A resumed session with the contact has been registered. */
	default void sessionEstablished(int contactId) {
	}
}
