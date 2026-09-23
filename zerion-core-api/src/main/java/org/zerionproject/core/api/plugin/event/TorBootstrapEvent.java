package org.zerionproject.core.api.plugin.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/**
 * Broadcast while Tor bootstraps, with the percentage it reported.
 */
@Immutable
@NotNullByDefault
public class TorBootstrapEvent extends Event {

	private final int percentage;

	public TorBootstrapEvent(int percentage) {
		this.percentage = percentage;
	}

	public int getPercentage() {
		return percentage;
	}
}
