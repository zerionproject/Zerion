package org.briarproject.briar.conversation.voice;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.plugin.ConnectionHandler;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.bramble.api.rendezvous.RendezvousEndpoint;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@ThreadSafe
@NotNullByDefault
class VoiceCallConnectionManagerImpl implements VoiceCallConnectionManager {

	private final PluginManager pluginManager;
	private final VoiceCallCrypto crypto;

	private final ConcurrentMap<String, RendezvousEndpoint> activeEndpoints =
			new ConcurrentHashMap<>();

	private final ConcurrentMap<String, Long> endpointCreationTimes =
			new ConcurrentHashMap<>();
	private static final long ENDPOINT_TIMEOUT_MS = 5 * 60 * 1000;

	private final ConcurrentMap<String, DuplexTransportConnection> activeConnections =
			new ConcurrentHashMap<>();

	private final ScheduledExecutorService cleanupScheduler =
			Executors.newSingleThreadScheduledExecutor();

	@Inject
	VoiceCallConnectionManagerImpl(PluginManager pluginManager,
			VoiceCallCrypto crypto) {
		this.pluginManager = pluginManager;
		this.crypto = crypto;

		cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredEndpoints,
				1, 1, TimeUnit.MINUTES);
	}

	private void cleanupExpiredEndpoints() {
		long now = System.currentTimeMillis();
		List<String> expired = new ArrayList<>();

		for (Map.Entry<String, Long> entry :
				endpointCreationTimes.entrySet()) {
			if (now - entry.getValue() > ENDPOINT_TIMEOUT_MS) {
				expired.add(entry.getKey());
			}
		}

		for (String callId : expired) {
			closeEndpoint(callId);
		}
	}

	@Override
	public EndpointInfo createIncomingEndpoint(String callId,
			SecretKey voiceCallKey, boolean alice,
			VoiceCallConnectionHandler handler) throws IOException {

		Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
		if (plugin == null || !(plugin instanceof DuplexPlugin)) {
			throw new IOException("Tor plugin not available");
		}
		DuplexPlugin torPlugin = (DuplexPlugin) plugin;

		if (!torPlugin.supportsRendezvous()) {
			throw new IOException("Tor plugin does not support rendezvous");
		}

		KeyMaterialSource keyMaterialForOnion = crypto.createKeyMaterialSource(
				voiceCallKey, TorConstants.ID);
		KeyMaterialSource keyMaterialForEndpoint = crypto.createKeyMaterialSource(
				voiceCallKey, TorConstants.ID);

		String localOnion = crypto.getLocalOnion(keyMaterialForOnion, alice);

		if (localOnion == null || localOnion.isEmpty()) {
			throw new IOException("Failed to derive local onion address");
		}

		ConnectionHandler connectionHandler = new ConnectionHandler() {
			@Override
			public void handleConnection(DuplexTransportConnection conn) {
				handler.handleConnection(conn);
			}

			@Override
			public void handleReader(TransportConnectionReader r) {
			}

			@Override
			public void handleWriter(TransportConnectionWriter w) {
			}
		};

		RendezvousEndpoint endpoint = torPlugin.createRendezvousEndpoint(
				keyMaterialForEndpoint,
				alice,
				connectionHandler
		);

		if (endpoint == null) {
			throw new IOException("Failed to create rendezvous endpoint");
		}

		RendezvousEndpoint oldEndpoint = activeEndpoints.put(callId, endpoint);
		if (oldEndpoint != null) {
			tryToClose(oldEndpoint);
		}

		endpointCreationTimes.put(callId, System.currentTimeMillis());

		return new EndpointInfo(localOnion, 80);
	}

	@Override
	@Nullable
	public DuplexTransportConnection connectToRemote(String callId,
			String remoteOnion, SecretKey voiceCallKey, boolean alice)
			throws IOException {

		Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
		if (plugin == null || !(plugin instanceof DuplexPlugin)) {
			throw new IOException("Tor plugin not available");
		}
		DuplexPlugin torPlugin = (DuplexPlugin) plugin;

		TransportProperties remoteProperties = new TransportProperties();
		remoteProperties.put(TorConstants.PROP_ONION_V3, remoteOnion);

		int maxAttempts = 6;
		long[] delays = {0, 1000, 2000, 4000, 8000, 16000};
		final long connectionTimeout = 10000;

		DuplexTransportConnection conn = null;
		IOException lastException = null;

		for (int attempt = 0; attempt < maxAttempts && conn == null; attempt++) {
			if (attempt > 0) {
				try {
					Thread.sleep(delays[attempt]);
				} catch (InterruptedException e) {
					throw new IOException("Connection interrupted", e);
				}
			}

			java.util.concurrent.ExecutorService executor =
					java.util.concurrent.Executors.newSingleThreadExecutor();
			try {
				java.util.concurrent.Future<DuplexTransportConnection> future =
						executor.submit(() -> torPlugin.createConnection(remoteProperties));

				try {
					conn = future.get(connectionTimeout, java.util.concurrent.TimeUnit.MILLISECONDS);
				} catch (java.util.concurrent.TimeoutException e) {
					future.cancel(true);
					lastException = new IOException("Connection timeout after " +
							connectionTimeout + "ms");
				} catch (java.util.concurrent.ExecutionException e) {
					lastException = new IOException("Connection attempt failed", e.getCause());
				}

				if (conn != null) {
					break;
				}
			} catch (InterruptedException e) {
				throw new IOException("Connection interrupted", e);
			} catch (Exception e) {
				lastException = new IOException("Connection attempt failed", e);
			} finally {
				executor.shutdownNow();
				try {
					executor.awaitTermination(2, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		if (conn == null) {
			if (lastException != null) {
				throw lastException;
			}
			throw new IOException("Failed to connect to remote peer after " +
					maxAttempts + " attempts");
		}

		DuplexTransportConnection oldConn = activeConnections.put(callId, conn);
		if (oldConn != null) {
			tryToClose(oldConn);
		}

		return conn;
	}

	@Override
	public void closeEndpoint(String callId) {
		RendezvousEndpoint endpoint = activeEndpoints.remove(callId);
		if (endpoint != null) {
			tryToClose(endpoint);
		}

		DuplexTransportConnection conn = activeConnections.remove(callId);
		if (conn != null) {
			tryToClose(conn);
		}

		endpointCreationTimes.remove(callId);
	}

	@Override
	public void shutdown() {
		cleanupScheduler.shutdownNow();
		try {
			cleanupScheduler.awaitTermination(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void tryToClose(RendezvousEndpoint endpoint) {
		try {
			endpoint.close();
		} catch (IOException e) {
		}
	}

	private void tryToClose(DuplexTransportConnection conn) {
		try {
			conn.getReader().dispose(false, true);
		} catch (IOException e) {
		}

		try {
			conn.getWriter().dispose(false);
		} catch (IOException e) {
		}
	}
}
