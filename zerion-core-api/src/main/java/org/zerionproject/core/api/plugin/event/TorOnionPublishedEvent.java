package org.zerionproject.core.api.plugin.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * Broadcast when Tor has uploaded the descriptor of one of this device's
 * onion services, which is when contacts can reach that address.
 */
@Immutable
@NotNullByDefault
public class TorOnionPublishedEvent extends Event {

	private final String onion;

	public TorOnionPublishedEvent(String onion) {
		this.onion = onion;
	}

	public String getOnion() {
		return onion;
	}
}
