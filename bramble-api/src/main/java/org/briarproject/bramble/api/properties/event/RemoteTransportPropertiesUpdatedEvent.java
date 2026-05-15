package org.briarproject.bramble.api.properties.event;

import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.properties.TransportProperties;
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
