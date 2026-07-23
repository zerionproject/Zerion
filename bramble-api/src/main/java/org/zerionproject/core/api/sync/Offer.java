package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Offer {

	private final Collection<MessageId> offered;

	public Offer(Collection<MessageId> offered) {
		this.offered = offered;
	}

	public Collection<MessageId> getMessageIds() {
		return offered;
	}
}
