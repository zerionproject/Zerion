package org.briarproject.bramble.api.plugin;

import org.briarproject.bramble.api.plugin.Plugin.State;
import org.briarproject.bramble.api.plugin.event.TransportActiveEvent;
import org.briarproject.bramble.api.plugin.event.TransportInactiveEvent;
import org.briarproject.bramble.api.plugin.event.TransportStateEvent;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.settings.Settings;
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
