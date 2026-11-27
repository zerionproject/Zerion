package org.zerionproject.core.api.contact.request.event;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.event.Event;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public abstract class ContactRequestEvent extends Event {

	private final byte[] requestId;
	private final boolean outgoing;

	protected ContactRequestEvent(byte[] requestId, boolean outgoing) {
		this.requestId = requestId.clone();
		this.outgoing = outgoing;
	}

	public byte[] getRequestId() {
		return requestId.clone();
	}

	public boolean isOutgoing() {
		return outgoing;
	}
}
