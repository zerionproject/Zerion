package org.zerionproject.core.api.plugin;

import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.core.api.plugin.simplex.SimplexPluginFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@NotNullByDefault
public interface PluginConfig {

	Collection<DuplexPluginFactory> getDuplexFactories();

	Collection<SimplexPluginFactory> getSimplexFactories();

	boolean shouldPoll();

	Map<TransportId, List<TransportId>> getTransportPreferences();
}
