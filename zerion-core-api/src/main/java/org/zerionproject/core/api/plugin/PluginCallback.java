package org.zerionproject.core.api.plugin;

import org.zerionproject.core.api.plugin.Plugin.State;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.settings.Settings;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

@NotNullByDefault
public interface PluginCallback extends ConnectionHandler {

	Settings getSettings();

	TransportProperties getLocalProperties();

	Collection<TransportProperties> getRemoteProperties();

	void mergeSettings(Settings s);

	void mergeLocalProperties(TransportProperties p);

	void pluginStateChanged(State state);
}
