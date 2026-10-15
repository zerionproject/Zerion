package org.zerionproject.core.api.plugin.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class TorEntryRelayEvent extends Event {

	private final boolean failing;

	public TorEntryRelayEvent(boolean failing) {
		this.failing = failing;
	}

	public boolean isFailing() {
		return failing;
	}
}
