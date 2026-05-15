package org.briarproject.bramble.api.plugin;

import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.simplex.SimplexPlugin;
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

}
