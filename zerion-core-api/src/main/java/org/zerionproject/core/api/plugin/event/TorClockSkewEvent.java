package org.zerionproject.core.api.plugin.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class TorClockSkewEvent extends Event {

	private final long skewSeconds;

	public TorClockSkewEvent(long skewSeconds) {
		this.skewSeconds = skewSeconds;
	}

	public long getSkewSeconds() {
		return skewSeconds;
	}
}
