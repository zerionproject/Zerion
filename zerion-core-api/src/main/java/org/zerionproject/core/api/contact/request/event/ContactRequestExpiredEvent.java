package org.zerionproject.core.api.contact.request.event;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactRequestExpiredEvent extends ContactRequestEvent {

	public ContactRequestExpiredEvent(byte[] requestId, boolean outgoing) {
		super(requestId, outgoing);
	}
}
