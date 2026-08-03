package org.zerionproject.transport.lan;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;
import org.zerionproject.core.api.keyagreement.KeyAgreementListener;
import org.zerionproject.core.api.plugin.ConnectionHandler;
import org.zerionproject.core.api.plugin.LanTcpConstants;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.TRANSPORT_ID_LAN;
import static org.zerionproject.core.api.plugin.Plugin.State.ACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.INACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;

/**
 * A key-agreement-only transport over a local point-to-point TCP socket, used
 * to pair contacts offline over a shared local link (a Wi-Fi Direct group, a
 * hotspot, or a common Wi-Fi network with no internet). It carries no contact
 * traffic and publishes no address, so it is not an ongoing messaging transport;
 * it only lets the existing Bramble QR key-agreement run without internet.
 *
 * <p>It registers under {@link LanTcpConstants#ID}, which the key-agreement
 * subsystem already expects for {@code TRANSPORT_ID_LAN}, so no change to the
 * payload codec, connector, or crypto is needed. Modelled on Briar's
 * LanTcpPlugin key-agreement methods.
 */
@ThreadSafe
@NotNullByDefault
public class LanKeyAgreementPlugin implements DuplexPlugin {

	static final int MAX_LATENCY = 30_000;
	static final int MAX_IDLE_TIME = 30_000;
	private static final int CONNECT_TIMEOUT = 8_000;

	private final PluginCallback callback;
	private volatile State state = STARTING_STOPPING;

	LanKeyAgreementPlugin(PluginCallback callback) {
		this.callback = callback;
	}

	@Override
	public TransportId getId() {
		return LanTcpConstants.ID;
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
	public void start() {
		setState(ACTIVE);
	}

	@Override
	public void stop() {
		setState(INACTIVE);
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
		return Integer.MAX_VALUE;
	}

	@Override
	public void poll(Collection<Pair<TransportProperties, ConnectionHandler>>
			properties) {
		// No contact traffic runs over this transport.
	}

	@Override
	@Nullable
	public DuplexTransportConnection createConnection(TransportProperties p) {
		return null;
	}

	@Override
	public boolean supportsKeyAgreement() {
		return true;
	}

	@Override
	@Nullable
	public KeyAgreementListener createKeyAgreementListener(
			byte[] localCommitment) {
		List<InetAddress> addrs = getLocalIpv4Addresses();
		if (addrs.isEmpty()) return null;
		try {
			// Bind to all interfaces so the server is reachable whichever local
			// network the two devices share (hotspot, Wi-Fi, or Wi-Fi Direct).
			ServerSocket ss = new ServerSocket();
			ss.bind(new InetSocketAddress((InetAddress) null, 0));
			BdfList descriptor = new BdfList();
			descriptor.add(TRANSPORT_ID_LAN);
			descriptor.add(addrs.get(0).getAddress());
			descriptor.add(ss.getLocalPort());
			// Advertise every local address so the peer can try each until one
			// on a shared subnet connects.
			for (int i = 1; i < addrs.size(); i++) {
				descriptor.add(addrs.get(i).getAddress());
			}
			return new Listener(descriptor, ss);
		} catch (IOException e) {
			return null;
		}
	}

	@Override
	@Nullable
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] remoteCommitment, BdfList descriptor) {
		try {
			int port = descriptor.getInt(2);
			if (port < 1 || port > 65535) return null;
			List<byte[]> ips = new ArrayList<>();
			ips.add(descriptor.getRaw(1));
			for (int i = 3; i < descriptor.size(); i++) {
				ips.add(descriptor.getRaw(i));
			}
			for (byte[] ipBytes : ips) {
				Socket s = null;
				try {
					InetAddress remote = InetAddress.getByAddress(ipBytes);
					InetAddress local = findLocalAddressOnSameNetwork(remote);
					if (local == null) continue;
					s = new Socket();
					s.bind(new InetSocketAddress(local, 0));
					s.connect(new InetSocketAddress(remote, port),
							CONNECT_TIMEOUT);
					return new LanKeyAgreementConnection(this, s);
				} catch (IOException e) {
					tryToClose(s);
				}
			}
			return null;
		} catch (Exception e) {
			return null;
		}
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

	private void setState(State s) {
		state = s;
		callback.pluginStateChanged(s);
	}

	/** Site-local IPv4 addresses of up, non-loopback interfaces (Wi-Fi, Wi-Fi
	 * Direct p2p, or hotspot), which two nearby devices can reach directly. */
	@Nullable
	private static InetAddress findLocalAddressOnSameNetwork(
			InetAddress remote) {
		if (!(remote instanceof Inet4Address)) return null;
		if (!remote.isSiteLocalAddress() && !remote.isLinkLocalAddress()) {
			return null;
		}
		try {
			for (NetworkInterface iface :
					Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (iface.isLoopback() || !iface.isUp()) continue;
				for (java.net.InterfaceAddress ia :
						iface.getInterfaceAddresses()) {
					InetAddress local = ia.getAddress();
					if (!(local instanceof Inet4Address)) continue;
					if (sameNetwork(local, remote,
							ia.getNetworkPrefixLength())) {
						return local;
					}
				}
			}
		} catch (SocketException e) {
		}
		return null;
	}

	private static boolean sameNetwork(InetAddress local, InetAddress remote,
			int prefixLength) {
		if (prefixLength < 1 || prefixLength > 32) return false;
		byte[] l = local.getAddress();
		byte[] r = remote.getAddress();
		if (l.length != r.length) return false;
		int fullBytes = prefixLength / 8;
		int bits = prefixLength % 8;
		for (int i = 0; i < fullBytes; i++) {
			if (l[i] != r[i]) return false;
		}
		if (bits > 0) {
			int mask = 0xFF << (8 - bits);
			if ((l[fullBytes] & mask) != (r[fullBytes] & mask)) return false;
		}
		return true;
	}

	private static List<InetAddress> getLocalIpv4Addresses() {
		List<InetAddress> result = new ArrayList<>();
		try {
			for (NetworkInterface iface :
					Collections.list(NetworkInterface.getNetworkInterfaces())) {
				if (iface.isLoopback() || !iface.isUp()) continue;
				for (InetAddress a :
						Collections.list(iface.getInetAddresses())) {
					if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
						result.add(a);
					}
				}
			}
		} catch (SocketException e) {
			// none available
		}
		return result;
	}

	private static void tryToClose(@Nullable java.io.Closeable c) {
		try {
			if (c != null) c.close();
		} catch (IOException e) {
			// best effort
		}
	}

	private class Listener extends KeyAgreementListener {

		private final ServerSocket ss;

		Listener(BdfList descriptor, ServerSocket ss) {
			super(descriptor);
			this.ss = ss;
		}

		@Override
		public KeyAgreementConnection accept() throws IOException {
			Socket s = ss.accept();
			return new KeyAgreementConnection(
					new LanKeyAgreementConnection(LanKeyAgreementPlugin.this, s),
					LanTcpConstants.ID);
		}

		@Override
		public void close() {
			tryToClose(ss);
		}
	}
}
