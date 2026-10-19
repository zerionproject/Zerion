package org.zerionproject.core.api.plugin.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class TorHelperTransportsPromptEvent extends Event {

	private final String country;

	public TorHelperTransportsPromptEvent(String country) {
		this.country = country;
	}

	public String getCountry() {
		return country;
	}
}
