package org.zerionproject.core.api.plugin;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Tor v3 client authorization for contact addresses: per-contact state,
 * the authorized address to dial, and the inbound policy. See
 * docs/protocol/ONION_CLIENT_AUTH.md.
 */
@NotNullByDefault
public interface OnionClientAuthManager {

	enum State {
		LEGACY,
		AUTH_NEGOTIATING,
		AUTH_CONFIRMED,
		AUTH_REQUIRED,
		REVOKED
	}

	State getState(ContactId c) throws DbException;

	/**
	 * The onion this contact must be dialled at, or null to dial the open
	 * address. Once a pair is AUTH_REQUIRED this is always the authorized
	 * address; it is never null again.
	 */
	@Nullable
	String getDialOnion(ContactId c);

	/**
	 * Whether a recognised inbound connection from the contact may proceed.
	 * A contact at AUTH_REQUIRED is refused over the open service.
	 */
	boolean acceptsInbound(ContactId c, boolean viaAuthorizedService);

	/**
	 * A connection from a recognised contact arrived through the
	 * authorized service: the contact's credential and this device's
	 * service both work, which is this side's probe of the pair.
	 */
	void inboundViaAuthorizedService(ContactId c);

	/** Rotates the authorized address with overlap (normal rotation). */
	void rotateAuthorizedService() throws DbException;

	/**
	 * Aborts a negotiation that has not committed and returns the pair to
	 * LEGACY, cleaning up keys and authorized entries. A pair at
	 * AUTH_REQUIRED is not affected.
	 */
	void resetNegotiation(ContactId c) throws DbException;
}
