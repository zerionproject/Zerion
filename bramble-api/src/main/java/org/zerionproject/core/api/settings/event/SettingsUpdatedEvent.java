package org.zerionproject.core.api.settings.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.settings.Settings;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class SettingsUpdatedEvent extends Event {

	private final String namespace;
	private final Settings settings;

	public SettingsUpdatedEvent(String namespace, Settings settings) {
		this.namespace = namespace;
		this.settings = settings;
	}

	public String getNamespace() {
		return namespace;
	}

	public Settings getSettings() {
		return settings;
	}
}
