package org.zerionproject.core.api.network.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.network.NetworkStatus;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class NetworkStatusEvent extends Event {

	private final NetworkStatus status;

	public NetworkStatusEvent(NetworkStatus status) {
		this.status = status;
	}

	public NetworkStatus getStatus() {
		return status;
	}
}