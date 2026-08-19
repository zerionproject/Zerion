package org.zerionproject.transport.i2p;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.keyagreement.KeyAgreementListener;
import org.zerionproject.core.api.plugin.ConnectionHandler;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.PluginException;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.transport.ZtpPoller;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.plugin.I2pConstants.DEFAULT_PREF_PLUGIN_ENABLE;
import static org.zerionproject.core.api.plugin.I2pConstants.I2P_PRIVATE_KEY;
import static org.zerionproject.core.api.plugin.I2pConstants.PROP_I2P_DEST;
import static org.zerionproject.core.api.plugin.Plugin.PREF_PLUGIN_ENABLE;
import static org.zerionproject.core.api.plugin.Plugin.State.ACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.DISABLED;
import static org.zerionproject.core.api.plugin.Plugin.State.ENABLING;
import static org.zerionproject.core.api.plugin.Plugin.State.INACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;

/**
 * The I2P transport exposed as a {@link DuplexPlugin} and the owner of the I2P
 * transport lifecycle, mirroring the Tor plugin. Contact traffic runs through
 * {@link I2pTransport} and its poller, not the plugin surface. First-contact
 * pairing, key agreement and rendezvous stay on Tor, so those methods are
 * unsupported here.
 *
 * <p>The plugin is registered but starts dormant: {@link #start} does nothing
 * unless the per-plugin enable preference is set, which is off by default until
 * an I2P router is bundled. This keeps the plugin from attempting a SAM
 * connection (and failing) on devices without a router.
 */
@NotNullByDefault
class I2pDuplexPlugin implements DuplexPlugin {

	static final int MAX_LATENCY = 60 * 1000;
	static final int MAX_IDLE_TIME = 30 * 1000;

	private final I2pOverlayTransport transport;
	private final ZtpPoller poller;
	private final PluginCallback callback;
	private final AtomicBoolean used = new AtomicBoolean(false);

	private volatile State state = STARTING_STOPPING;

	I2pDuplexPlugin(I2pOverlayTransport transport, ZtpPoller poller,
			PluginCallback callback) {
		this.transport = transport;
		this.poller = poller;
		this.callback = callback;
	}

	@Override
	public TransportId getId() {
		return I2pConstants.ID;
	}

	@Override
	public long getMaxLatency() {
		return MAX_LATENCY;
	}

	@Override
	public int getMaxIdleTime() {
		return MAX_IDLE_TIME;
	}

	@Override
	public void start() throws PluginException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		Settings settings = callback.getSettings();
		if (!settings.getBoolean(PREF_PLUGIN_ENABLE,
				DEFAULT_PREF_PLUGIN_ENABLE)) {
			setState(DISABLED);
			return;
		}
		I2pDestination dest;
		@Nullable String privateKey = settings.get(I2P_PRIVATE_KEY);
		try {
			dest = transport.start(privateKey);
		} catch (IOException e) {
			setState(INACTIVE);
			throw new PluginException(e);
		}
		if (privateKey == null) {
			Settings updated = new Settings();
			updated.put(I2P_PRIVATE_KEY, dest.getPrivateKey());
			callback.mergeSettings(updated);
		}
		TransportProperties props = new TransportProperties();
		props.put(PROP_I2P_DEST, dest.getDestination());
		callback.mergeLocalProperties(props);
		poller.start();
		setState(ENABLING);
		transport.setOnSessionReady(() -> {
			if (state == ENABLING) setState(ACTIVE);
		});
		poller.pollNow();
	}

	@Override
	public void stop() {
		if (state == ACTIVE || state == ENABLING) {
			poller.stop();
			transport.stop();
		}
		setState(INACTIVE);
	}

	private void setState(State s) {
		state = s;
		callback.pluginStateChanged(s);
	}

	@Override
	public State getState() {
		return state;
	}

	@Override
	public int getReasonsDisabled() {
		return 0;
	}

	@Override
	public boolean shouldPoll() {
		return false;
	}

	@Override
	public int getPollingInterval() {
		return MAX_LATENCY;
	}

	@Override
	public void poll(Collection<Pair<TransportProperties, ConnectionHandler>>
			properties) {
		// Contact traffic is driven by the transport's own poller.
	}

	@Override
	@Nullable
	public DuplexTransportConnection createConnection(TransportProperties p) {
		// Contact connections go through I2pTransport, not the plugin surface.
		return null;
	}

	@Override
	public boolean supportsKeyAgreement() {
		return false;
	}

	@Override
	public KeyAgreementListener createKeyAgreementListener(
			byte[] localCommitment) {
		throw new UnsupportedOperationException();
	}

	@Override
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] remoteCommitment, BdfList descriptor) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean supportsRendezvous() {
		return false;
	}

	@Override
	public RendezvousEndpoint createRendezvousEndpoint(KeyMaterialSource k,
			boolean alice, ConnectionHandler incoming) {
		throw new UnsupportedOperationException();
	}
}
