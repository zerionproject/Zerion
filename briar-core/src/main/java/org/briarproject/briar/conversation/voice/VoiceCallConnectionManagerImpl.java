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
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;

@Singleton
@ThreadSafe
@NotNullByDefault
class VoiceCallConnectionManagerImpl implements VoiceCallConnectionManager {

	private static final Logger LOG =
			getLogger(VoiceCallConnectionManagerImpl.class.getName());

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

		// Schedule periodic cleanup of expired endpoints every minute
		cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredEndpoints,
				1, 1, TimeUnit.MINUTES);

		if (LOG.isLoggable(INFO)) {
			LOG.info("VoiceCallConnectionManager initialized with auto-cleanup");
		}
	}

	/**
	 * Clean up expired endpoints that weren't properly closed
	 * (e.g., due to app crash or force stop)
	 */
	private void cleanupExpiredEndpoints() {
		long now = System.currentTimeMillis();
		int cleaned = 0;

		Iterator<Map.Entry<String, Long>> iterator =
				endpointCreationTimes.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<String, Long> entry = iterator.next();
			String callId = entry.getKey();
			long creationTime = entry.getValue();

			if (now - creationTime > ENDPOINT_TIMEOUT_MS) {
				if (LOG.isLoggable(INFO)) {
					LOG.info("Cleaning up expired endpoint for call " + callId +
							" (age: " + ((now - creationTime) / 1000) + "s)");
				}
				closeEndpoint(callId);
				iterator.remove();
				cleaned++;
			}
		}

		if (cleaned > 0 && LOG.isLoggable(INFO)) {
			LOG.info("Cleaned up " + cleaned + " expired endpoints");
		}
	}

	@Override
	public EndpointInfo createIncomingEndpoint(String callId,
			SecretKey voiceCallKey, boolean alice,
			VoiceCallConnectionHandler handler) throws IOException {

		if (LOG.isLoggable(INFO)) {
			LOG.info("Creating rendezvous endpoint for call " + callId +
					" (alice=" + alice + ")");
		}

		// 1. Get TorPlugin from PluginManager
		Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
		if (plugin == null || !(plugin instanceof DuplexPlugin)) {
			throw new IOException("Tor plugin not available");
		}
		DuplexPlugin torPlugin = (DuplexPlugin) plugin;

		// 2. Check if plugin supports rendezvous
		if (!torPlugin.supportsRendezvous()) {
			throw new IOException("Tor plugin does not support rendezvous");
		}

		// 3. CRITICAL: Create TWO independent KeyMaterialSource instances
		// KeyMaterialSource is stateful - each getKeyMaterial() call advances a cursor
		// TorPlugin.createRendezvousEndpoint() consumes seeds #1 and #2
		// crypto.getLocalOnion() also needs seeds #1 and #2
		// If we share the same instance, getLocalOnion() would get seeds #3 and #4
		// resulting in a different onion than where Tor actually listens!
		KeyMaterialSource keyMaterialForOnion = crypto.createKeyMaterialSource(
				voiceCallKey, TorConstants.ID);
		KeyMaterialSource keyMaterialForEndpoint = crypto.createKeyMaterialSource(
				voiceCallKey, TorConstants.ID);

		// 4. Compute LOCAL onion address (where THIS endpoint will listen)
		// This is the critical fix - we must advertise the onion where the hidden
		// service is actually published, not the remote peer's hypothetical onion
		String localOnion = crypto.getLocalOnion(keyMaterialForOnion, alice);

		if (localOnion == null || localOnion.isEmpty()) {
			throw new IOException("Failed to derive local onion address");
		}

		if (LOG.isLoggable(INFO)) {
			LOG.info("Computed localOnion: " + scrubOnion(localOnion) +
					" for alice=" + alice + " (callId=" + callId + ")");
		}

		// 5. Create a ConnectionHandler that wraps our VoiceCallConnectionHandler
		ConnectionHandler connectionHandler = new ConnectionHandler() {
			@Override
			public void handleConnection(DuplexTransportConnection conn) {
				if (LOG.isLoggable(INFO)) {
					LOG.info("Incoming voice call connection for call " + callId);
				}
				// Forward to the voice call handler
				handler.handleConnection(conn);
			}

			@Override
			public void handleReader(TransportConnectionReader r) {
				// Voice calls only use duplex connections, not simplex readers
				if (LOG.isLoggable(WARNING)) {
					LOG.warning("Unexpected simplex reader for voice call " + callId);
				}
			}

			@Override
			public void handleWriter(TransportConnectionWriter w) {
				// Voice calls only use duplex connections, not simplex writers
				if (LOG.isLoggable(WARNING)) {
					LOG.warning("Unexpected simplex writer for voice call " + callId);
				}
			}
		};

		// 6. Create rendezvous endpoint
		// This actually creates the Tor hidden service and registers it
		// Use the dedicated KeyMaterialSource for the endpoint (not the one we used for onion computation)
		RendezvousEndpoint endpoint = torPlugin.createRendezvousEndpoint(
				keyMaterialForEndpoint,
				alice,
				connectionHandler
		);

		if (endpoint == null) {
			throw new IOException("Failed to create rendezvous endpoint");
		}

		// 7. Store endpoint for later cleanup and track creation time
		RendezvousEndpoint oldEndpoint = activeEndpoints.put(callId, endpoint);
		if (oldEndpoint != null) {
			// Clean up old endpoint if one existed
			LOG.warning("Replacing existing endpoint for call " + callId);
			tryToClose(oldEndpoint);
		}

		// Track creation time for automatic cleanup after TTL
		endpointCreationTimes.put(callId, System.currentTimeMillis());

		if (LOG.isLoggable(INFO)) {
			LOG.info("Created rendezvous endpoint, advertising: " +
					scrubOnion(localOnion) + ":80");
		}

		// 8. Return LOCAL onion (where hidden service is listening)
		// NOT the remote onion from endpoint.getRemoteTransportProperties()
		// This was the critical bug - the callee was advertising the wrong onion
		return new EndpointInfo(localOnion, 80);
	}

	@Override
	@Nullable
	public DuplexTransportConnection connectToRemote(String callId,
			String remoteOnion, SecretKey voiceCallKey, boolean alice)
			throws IOException {

		if (LOG.isLoggable(INFO)) {
			LOG.info("Connecting to remote endpoint " + scrubOnion(remoteOnion) +
					" for call " + callId + " (alice=" + alice + ")");
		}

		// For rendezvous connections, the caller (Alice) actively connects
		// to the callee's (Bob) onion address using TorPlugin.createConnection()
		// This is how RendezvousPoller works - see TorPlugin.poll()

		// 1. Get TorPlugin
		Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
		if (plugin == null || !(plugin instanceof DuplexPlugin)) {
			throw new IOException("Tor plugin not available");
		}
		DuplexPlugin torPlugin = (DuplexPlugin) plugin;

		// 2. Create TransportProperties with the remote onion address
		TransportProperties remoteProperties = new TransportProperties();
		remoteProperties.put(TorConstants.PROP_ONION_V3, remoteOnion);

		// 3. Retry connection with exponential backoff
		// Hidden services may take several seconds to become reachable
		// RendezvousPoller uses similar retry logic with backoff
		// Extended to 6 attempts (~31s total) to account for descriptor propagation
		int maxAttempts = 6;
		long[] delays = {0, 1000, 2000, 4000, 8000, 16000}; // milliseconds
		final long connectionTimeout = 10000; // 10 second timeout per attempt

		DuplexTransportConnection conn = null;
		IOException lastException = null;

		for (int attempt = 0; attempt < maxAttempts && conn == null; attempt++) {
			if (attempt > 0) {
				if (LOG.isLoggable(INFO)) {
					LOG.info("Retry attempt " + attempt + " after " +
							delays[attempt] + "ms delay");
				}
				try {
					Thread.sleep(delays[attempt]);
				} catch (InterruptedException e) {
					throw new IOException("Connection interrupted", e);
				}
			}

			if (LOG.isLoggable(INFO)) {
				LOG.info("Attempting to connect to " + scrubOnion(remoteOnion) +
						" (attempt " + (attempt + 1) + "/" + maxAttempts + ")");
			}

			try {
				// Create connection with timeout using ExecutorService
				java.util.concurrent.ExecutorService executor =
						java.util.concurrent.Executors.newSingleThreadExecutor();
				java.util.concurrent.Future<DuplexTransportConnection> future =
						executor.submit(() -> torPlugin.createConnection(remoteProperties));

				try {
					conn = future.get(connectionTimeout, java.util.concurrent.TimeUnit.MILLISECONDS);
					if (conn != null) {
						if (LOG.isLoggable(INFO)) {
							LOG.info("Successfully connected on attempt " + (attempt + 1));
						}
					}
				} catch (java.util.concurrent.TimeoutException e) {
					future.cancel(true);
					lastException = new IOException("Connection timeout after " +
							connectionTimeout + "ms");
					if (LOG.isLoggable(WARNING)) {
						LOG.warning("Attempt " + (attempt + 1) + " timed out");
					}
				} catch (java.util.concurrent.ExecutionException e) {
					lastException = new IOException("Connection attempt failed", e.getCause());
					if (LOG.isLoggable(WARNING)) {
						LOG.warning("Attempt " + (attempt + 1) + " failed: " +
								e.getCause().getMessage());
					}
				} finally {
					executor.shutdownNow();
				}

				if (conn != null) {
					break;
				}
			} catch (InterruptedException e) {
				throw new IOException("Connection interrupted", e);
			} catch (Exception e) {
				lastException = new IOException("Connection attempt failed", e);
				if (LOG.isLoggable(WARNING)) {
					LOG.warning("Attempt " + (attempt + 1) + " failed: " +
							e.getMessage());
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

		// 4. Store connection for cleanup
		DuplexTransportConnection oldConn = activeConnections.put(callId, conn);
		if (oldConn != null) {
			LOG.warning("Replacing existing connection for call " + callId);
			tryToClose(oldConn);
		}

		if (LOG.isLoggable(INFO)) {
			LOG.info("Successfully connected to " + scrubOnion(remoteOnion));
		}

		return conn;
	}

	@Override
	public void closeEndpoint(String callId) {
		if (LOG.isLoggable(INFO)) {
			LOG.info("Closing endpoint for call " + callId);
		}

		// Close rendezvous endpoint if exists
		RendezvousEndpoint endpoint = activeEndpoints.remove(callId);
		if (endpoint != null) {
			tryToClose(endpoint);
		}

		// Close connection if exists
		DuplexTransportConnection conn = activeConnections.remove(callId);
		if (conn != null) {
			tryToClose(conn);
		}

		// Remove creation time tracking
		endpointCreationTimes.remove(callId);
	}

	/**
	 * Attempts to close a rendezvous endpoint, logging any errors.
	 */
	private void tryToClose(RendezvousEndpoint endpoint) {
		try {
			endpoint.close();
			if (LOG.isLoggable(INFO)) {
				LOG.info("Closed rendezvous endpoint");
			}
		} catch (IOException e) {
			if (LOG.isLoggable(WARNING)) {
				LOG.warning("Error closing rendezvous endpoint: " + e.getMessage());
			}
		}
	}

	/**
	 * Attempts to close a duplex transport connection, logging any errors.
	 */
	private void tryToClose(DuplexTransportConnection conn) {
		try {
			conn.getReader().dispose(false, true);
			if (LOG.isLoggable(INFO)) {
				LOG.info("Closed connection reader");
			}
		} catch (IOException e) {
			if (LOG.isLoggable(WARNING)) {
				LOG.warning("Error closing reader: " + e.getMessage());
			}
		}

		try {
			conn.getWriter().dispose(false);
			if (LOG.isLoggable(INFO)) {
				LOG.info("Closed connection writer");
			}
		} catch (IOException e) {
			if (LOG.isLoggable(WARNING)) {
				LOG.warning("Error closing writer: " + e.getMessage());
			}
		}
	}

	/**
	 * Scrubs an onion address for logging (shows first 6 and last 4 chars).
	 */
	private String scrubOnion(String onion) {
		if (onion == null || onion.length() < 15) {
			return "[invalid]";
		}
		String clean = onion.replace(".onion", "");
		if (clean.length() < 15) {
			return "[short]";
		}
		return clean.substring(0, 6) + "..." + clean.substring(clean.length() - 4);
	}
}
