package org.zerionproject.core.api.plugin;

import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.simplex.SimplexPlugin;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.Nullable;

@NotNullByDefault
public interface PluginManager {

	@Nullable
	Plugin getPlugin(TransportId t);

	Collection<SimplexPlugin> getSimplexPlugins();

	Collection<DuplexPlugin> getDuplexPlugins();

	Collection<DuplexPlugin> getKeyAgreementPlugins();

	Collection<DuplexPlugin> getRendezvousPlugins();

	void setPluginEnabled(TransportId t, boolean enabled);

	/**
	 * Offline (paranoia) mode: when on, all internet transports (Tor, I2P) are
	 * stopped and only the offline mesh runs, so the app makes no internet
	 * connections at all. Persisted, so it survives a restart.
	 */
	void setOfflineMode(boolean offline);

	boolean isOfflineMode();

	/**
	 * Connections paused: when on, no transport plugins are started at all,
	 * including the offline mesh, so the app opens no connections of any kind.
	 * Persisted, so a restart that brings the service back up still starts no
	 * transports until this is cleared.
	 */
	void setConnectionsPaused(boolean paused);

	boolean isConnectionsPaused();

}
