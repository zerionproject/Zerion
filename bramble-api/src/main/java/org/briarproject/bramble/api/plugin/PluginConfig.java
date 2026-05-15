package org.briarproject.bramble.api.plugin;

import org.briarproject.bramble.api.plugin.duplex.DuplexPluginFactory;
import org.briarproject.bramble.api.plugin.simplex.SimplexPluginFactory;
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
