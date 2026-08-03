package org.zerionproject.core.api.plugin;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Wakeful;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

@NotNullByDefault
public interface Plugin {

	enum State {

		STARTING_STOPPING,

		DISABLED,

		ENABLING,

		ACTIVE,

		INACTIVE
	}

	String PREF_PLUGIN_ENABLE = "enable";

	int REASON_USER = 1;

	TransportId getId();

	long getMaxLatency();

	int getMaxIdleTime();

	@Wakeful
	void start() throws PluginException;

	@Wakeful
	void stop() throws PluginException;

	State getState();

	int getReasonsDisabled();

	boolean shouldPoll();

	int getPollingInterval();

	@Wakeful
	void poll(Collection<Pair<TransportProperties, ConnectionHandler>>
			properties);
}
