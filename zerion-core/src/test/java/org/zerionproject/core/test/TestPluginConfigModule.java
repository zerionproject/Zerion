package org.zerionproject.core.test;

import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.core.api.plugin.simplex.SimplexPlugin;
import org.zerionproject.core.api.plugin.simplex.SimplexPluginFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import dagger.Module;
import dagger.Provides;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.zerionproject.core.test.TestUtils.getTransportId;

@Module
public class TestPluginConfigModule {

	public static final TransportId SIMPLEX_TRANSPORT_ID = getTransportId();
	public static final TransportId DUPLEX_TRANSPORT_ID = getTransportId();
	private static final int MAX_LATENCY = 30_000;

	private final TransportId simplexTransportId, duplexTransportId;

	public TestPluginConfigModule() {
		this(SIMPLEX_TRANSPORT_ID, DUPLEX_TRANSPORT_ID);
	}

	public TestPluginConfigModule(TransportId simplexTransportId,
			TransportId duplexTransportId) {
		this.simplexTransportId = simplexTransportId;
		this.duplexTransportId = duplexTransportId;
	}

	@NotNullByDefault
	private final SimplexPluginFactory simplex = new SimplexPluginFactory() {

		@Override
		public TransportId getId() {
			return simplexTransportId;
		}

		@Override
		public long getMaxLatency() {
			return MAX_LATENCY;
		}

		@Override
		@Nullable
		public SimplexPlugin createPlugin(PluginCallback callback) {
			return null;
		}
	};

	@NotNullByDefault
	private final DuplexPluginFactory duplex = new DuplexPluginFactory() {

		@Override
		public TransportId getId() {
			return duplexTransportId;
		}

		@Override
		public long getMaxLatency() {
			return MAX_LATENCY;
		}

		@Nullable
		@Override
		public DuplexPlugin createPlugin(PluginCallback callback) {
			return null;
		}
	};

	@Provides
	public PluginConfig providePluginConfig() {
		@NotNullByDefault
		PluginConfig pluginConfig = new PluginConfig() {

			@Override
			public Collection<DuplexPluginFactory> getDuplexFactories() {
				return singletonList(duplex);
			}

			@Override
			public Collection<SimplexPluginFactory> getSimplexFactories() {
				return singletonList(simplex);
			}

			@Override
			public boolean shouldPoll() {
				return false;
			}

			@Override
			public Map<TransportId, List<TransportId>> getTransportPreferences() {
				return emptyMap();
			}

		};
		return pluginConfig;
	}
}
