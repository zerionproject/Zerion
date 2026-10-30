package org.zerionproject.core.api.contact.request.event;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactRequestReceivedEvent extends ContactRequestEvent {

	public ContactRequestReceivedEvent(byte[] requestId, boolean outgoing) {
		super(requestId, outgoing);
	}
}
