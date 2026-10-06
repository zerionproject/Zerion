package org.zerionproject.core.api.plugin;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

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

	@Nullable
	String getDialOnion(ContactId c);

	boolean acceptsInbound(ContactId c, boolean viaAuthorizedService);

	void inboundViaAuthorizedService(ContactId c);

	void rotateAuthorizedService() throws DbException;

	void refeedCredentials();

	void resetNegotiation(ContactId c) throws DbException;
}
