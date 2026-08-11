package org.zerionproject.core.api.properties.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class RemoteTransportPropertiesUpdatedEvent extends Event {

	private final TransportId transportId;

	public RemoteTransportPropertiesUpdatedEvent(TransportId transportId) {
		this.transportId = transportId;
	}

	public TransportId getTransportId() {
		return transportId;
	}
}
