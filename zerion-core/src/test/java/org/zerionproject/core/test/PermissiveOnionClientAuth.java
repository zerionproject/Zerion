package org.zerionproject.core.test;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.plugin.OnionClientAuthManager;

import javax.annotation.Nullable;

/** A client authorization manager with every contact at LEGACY. */
public class PermissiveOnionClientAuth implements OnionClientAuthManager {

	@Override
	public State getState(ContactId c) {
		return State.LEGACY;
	}

	@Override
	@Nullable
	public String getDialOnion(ContactId c) {
		return null;
	}

	@Override
	public boolean acceptsInbound(ContactId c, boolean viaAuthorizedService) {
		return true;
	}

	@Override
	public void inboundViaAuthorizedService(ContactId c) {
	}

	@Override
	public void rotateAuthorizedService() {
	}

	@Override
	public void resetNegotiation(ContactId c) {
	}

	@Override
	public void refeedCredentials() {
	}
}
