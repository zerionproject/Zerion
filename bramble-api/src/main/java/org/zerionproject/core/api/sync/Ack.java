package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Ack {

	private final Collection<MessageId> acked;

	public Ack(Collection<MessageId> acked) {
		this.acked = acked;
	}

	public Collection<MessageId> getMessageIds() {
		return acked;
	}
}
