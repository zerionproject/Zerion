package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Request {

	private final Collection<MessageId> requested;

	public Request(Collection<MessageId> requested) {
		this.requested = requested;
	}

	public Collection<MessageId> getMessageIds() {
		return requested;
	}
}
