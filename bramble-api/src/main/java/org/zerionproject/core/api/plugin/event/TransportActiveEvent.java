package org.zerionproject.core.api.plugin.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.plugin.Plugin.State;
import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class TransportActiveEvent extends Event {

	private final TransportId transportId;

	public TransportActiveEvent(TransportId transportId) {
		this.transportId = transportId;
	}

	public TransportId getTransportId() {
		return transportId;
	}
}
