package org.briarproject.bramble.plugin.tcp;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.keyagreement.KeyAgreementConnection;
import org.briarproject.bramble.api.keyagreement.KeyAgreementListener;
import org.briarproject.bramble.api.plugin.Backoff;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;

import static java.lang.Integer.parseInt;
import static java.util.Collections.addAll;
import static java.util.Collections.emptyList;
import static java.util.Collections.sort;
import static org.briarproject.bramble.api.keyagreement.KeyAgreementConstants.TRANSPORT_ID_LAN;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.DEFAULT_PREF_PLUGIN_ENABLE;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.ID;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PREF_IPV6;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PREF_LAN_IP_PORTS;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PROP_IPV6;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PROP_IP_PORTS;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.PROP_PORT;
import static org.briarproject.bramble.api.properties.TransportPropertyConstants.MAX_PROPERTY_LENGTH;
import static org.briarproject.bramble.util.ByteUtils.MAX_16_BIT_UNSIGNED;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.PrivacyUtils.scrubSocketAddress;
import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.bramble.util.StringUtils.join;
import static org.briarproject.bramble.util.StringUtils.toHexString;
import static org.briarproject.bramble.util.StringUtils.utf8IsTooLong;

@NotNullByDefault
class LanTcpPlugin extends TcpPlugin {
	private static final String SEPARATOR = ",";

	
	private static final InetAddress WIFI_AP_ADDRESS;

	
	private static final InetAddress WIFI_DIRECT_AP_ADDRESS;

	static {
		try {
			WIFI_AP_ADDRESS = InetAddress.getByAddress(
					new byte[] {(byte) 192, (byte) 168, 43, 1});
			WIFI_DIRECT_AP_ADDRESS = InetAddress.getByAddress(
					new byte[] {(byte) 192, (byte) 168, 49, 1});
		} catch (UnknownHostException e) {
			throw new AssertionError(e);
		}
	}

	LanTcpPlugin(Executor ioExecutor,
			Executor wakefulIoExecutor,
			Backoff backoff,
			PluginCallback callback,
			long maxLatency,
			int maxIdleTime,
			int connectionTimeout) {
		super(ioExecutor, wakefulIoExecutor, backoff, callback, maxLatency,
				maxIdleTime, connectionTimeout);
	}

	@Override
	public TransportId getId() {
		return ID;
	}

	@Override
	public void start() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		initialisePortProperty();
		Settings settings = callback.getSettings();
		state.setStarted(settings.getBoolean(PREF_PLUGIN_ENABLE,
				DEFAULT_PREF_PLUGIN_ENABLE));
		bind();
	}

	protected void initialisePortProperty() {
		TransportProperties p = callback.getLocalProperties();
		if (isNullOrEmpty(p.get(PROP_PORT))) {
			int port = chooseEphemeralPort();
			p.put(PROP_PORT, String.valueOf(port));
			callback.mergeLocalProperties(p);
		}
	}

	@Override
	protected boolean isEnabledByDefault() {
		return DEFAULT_PREF_PLUGIN_ENABLE;
	}

	@Override
	protected List<InetSocketAddress> getLocalSocketAddresses(boolean ipv4) {
		TransportProperties p = callback.getLocalProperties();
		int preferredPort = parsePortProperty(p.get(PROP_PORT));
		String oldIpPorts = p.get(PROP_IP_PORTS);
		List<InetSocketAddress> olds = parseIpv4SocketAddresses(oldIpPorts);

		List<InetSocketAddress> locals = new ArrayList<>();
		List<InetSocketAddress> fallbacks = new ArrayList<>();
		for (InetAddress local : getUsableLocalInetAddresses(ipv4)) {
			int port = preferredPort;
			for (InetSocketAddress old : olds) {
				if (old.getAddress().equals(local)) {
					port = old.getPort();
					break;
				}
			}
			locals.add(new InetSocketAddress(local, port));
			fallbacks.add(new InetSocketAddress(local, 0));
		}
		locals.addAll(fallbacks);
		return locals;
	}

	private int parsePortProperty(@Nullable String portProperty) {
		if (isNullOrEmpty(portProperty)) return 0;
		try {
			return parseInt(portProperty);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private List<InetSocketAddress> parseIpv4SocketAddresses(String ipPorts) {
		List<InetSocketAddress> addresses = new ArrayList<>();
		if (isNullOrEmpty(ipPorts)) return addresses;
		for (String ipPort : ipPorts.split(SEPARATOR)) {
			InetSocketAddress a = parseIpv4SocketAddress(ipPort);
			if (a != null) addresses.add(a);
		}
		return addresses;
	}

	protected List<InetAddress> getUsableLocalInetAddresses(boolean ipv4) {
		List<InterfaceAddress> ifAddrs =
				new ArrayList<>(getLocalInterfaceAddresses());
		sort(ifAddrs, (a, b) ->
				b.getNetworkPrefixLength() - a.getNetworkPrefixLength());
		List<InetAddress> addrs = new ArrayList<>();
		for (InterfaceAddress ifAddr : ifAddrs) {
			InetAddress addr = ifAddr.getAddress();
			if (isAcceptableAddress(addr, ipv4)) addrs.add(addr);
		}
		return addrs;
	}

	@Override
	protected void setLocalSocketAddress(InetSocketAddress a, boolean ipv4) {
		if (ipv4) setLocalIpv4SocketAddress(a);
		else setLocalIpv6SocketAddress(a);
	}

	private void setLocalIpv4SocketAddress(InetSocketAddress a) {
		String ipPort = getIpPortString(a);
		updateRecentAddresses(PREF_LAN_IP_PORTS, PROP_IP_PORTS, ipPort);
	}

	private void setLocalIpv6SocketAddress(InetSocketAddress a) {
		String hex = toHexString(a.getAddress().getAddress());
		updateRecentAddresses(PREF_IPV6, PROP_IPV6, hex);
	}

	private void updateRecentAddresses(String settingKey, String propertyKey,
			String item) {
		String setting = callback.getSettings().get(settingKey);
		Deque<String> recent = new LinkedList<>();
		if (!isNullOrEmpty(setting)) {
			addAll(recent, setting.split(SEPARATOR));
		}
		if (recent.remove(item)) {
			recent.addFirst(item);
			setting = join(recent, SEPARATOR);
		} else {
			recent.addFirst(item);
			setting = join(recent, SEPARATOR);
			while (utf8IsTooLong(setting, MAX_PROPERTY_LENGTH)) {
				recent.removeLast();
				setting = join(recent, SEPARATOR);
			}
			TransportProperties properties = new TransportProperties();
			properties.put(propertyKey, setting);
			callback.mergeLocalProperties(properties);
		}
		Settings settings = new Settings();
		settings.put(settingKey, setting);
		callback.mergeSettings(settings);
	}

	protected boolean isIpv6LinkLocalAddress(InetAddress a) {
		return a instanceof Inet6Address && a.isLinkLocalAddress();
	}

	@Override
	protected List<InetSocketAddress> getRemoteSocketAddresses(
			TransportProperties p, boolean ipv4) {
		if (ipv4) return getRemoteIpv4SocketAddresses(p);
		else return getRemoteIpv6SocketAddresses(p);
	}

	private List<InetSocketAddress> getRemoteIpv4SocketAddresses(
			TransportProperties p) {
		String ipPorts = p.get(PROP_IP_PORTS);
		List<InetSocketAddress> remotes = parseIpv4SocketAddresses(ipPorts);
		int port = parsePortProperty(p.get(PROP_PORT));
		if (port != 0) {
			InetSocketAddress wifiAp =
					new InetSocketAddress(WIFI_AP_ADDRESS, port);
			if (!remotes.contains(wifiAp)) remotes.add(wifiAp);
			InetSocketAddress wifiDirectAp =
					new InetSocketAddress(WIFI_DIRECT_AP_ADDRESS, port);
			if (!remotes.contains(wifiDirectAp)) remotes.add(wifiDirectAp);
		}
		return remotes;
	}

	private List<InetSocketAddress> getRemoteIpv6SocketAddresses(
			TransportProperties p) {
		List<InetAddress> addrs = parseIpv6Addresses(p.get(PROP_IPV6));
		int port = parsePortProperty(p.get(PROP_PORT));
		if (addrs.isEmpty() || port == 0) return emptyList();
		List<InetSocketAddress> remotes = new ArrayList<>();
		for (InetAddress addr : addrs) {
			remotes.add(new InetSocketAddress(addr, port));
		}
		return remotes;
	}

	private List<InetAddress> parseIpv6Addresses(String property) {
		if (isNullOrEmpty(property)) return emptyList();
		try {
			List<InetAddress> addrs = new ArrayList<>();
			for (String hex : property.split(SEPARATOR)) {
				byte[] ip = fromHexString(hex);
				if (ip.length == 16) addrs.add(InetAddress.getByAddress(ip));
			}
			return addrs;
		} catch (FormatException | UnknownHostException e) {
			return emptyList();
		}
	}

	private boolean isAcceptableAddress(InetAddress a, boolean ipv4) {
		if (ipv4) {
			boolean isIpv4 = a instanceof Inet4Address;
			boolean link = a.isLinkLocalAddress();
			boolean site = a.isSiteLocalAddress();
			return isIpv4 && (link || site);
		} else {
			return isIpv6LinkLocalAddress(a);
		}
	}

	@Override
	protected boolean isConnectable(InterfaceAddress local,
			InetSocketAddress remote) {
		if (remote.getPort() == 0) return false;
		InetAddress remoteAddress = remote.getAddress();
		boolean ipv4 = local.getAddress() instanceof Inet4Address;
		if (!isAcceptableAddress(remoteAddress, ipv4)) return false;
		byte[] localIp = local.getAddress().getAddress();
		byte[] remoteIp = remote.getAddress().getAddress();
		int prefixLength = local.getNetworkPrefixLength();
		return areAddressesInSameNetwork(localIp, remoteIp, prefixLength);
	}
	static boolean areAddressesInSameNetwork(byte[] localIp, byte[] remoteIp,
			int prefixLength) {
		if (localIp.length != remoteIp.length) return false;
		for (int i = 0; i < prefixLength; i++) {
			int byteIndex = i >> 3;
			int bitIndex = i & 7;
			int mask = 128 >> bitIndex;
			if ((localIp[byteIndex] & mask) != (remoteIp[byteIndex] & mask)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean supportsKeyAgreement() {
		return true;
	}

	@Override
	public KeyAgreementListener createKeyAgreementListener(byte[] commitment) {
		ServerSocket ss = null;
		for (InetSocketAddress addr : getLocalSocketAddresses()) {
			addr = new InetSocketAddress(addr.getAddress(), 0);
			try {
				ss = new ServerSocket();
				ss.bind(addr);
				break;
			} catch (IOException e) {
			}
		}
		if (ss == null || !ss.isBound()) {
			return null;
		}
		BdfList descriptor = new BdfList();
		descriptor.add(TRANSPORT_ID_LAN);
		InetSocketAddress local =
				(InetSocketAddress) ss.getLocalSocketAddress();
		descriptor.add(local.getAddress().getAddress());
		descriptor.add(local.getPort());
		return new LanKeyAgreementListener(descriptor, ss);
	}

	private List<InetSocketAddress> getLocalSocketAddresses() {
		List<InetSocketAddress> addrs = new ArrayList<>();
		addrs.addAll(getLocalSocketAddresses(true));
		addrs.addAll(getLocalSocketAddresses(false));
		return addrs;
	}

	@Override
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] commitment, BdfList descriptor) {
		ServerSocket ss = state.getServerSocket(true);
		if (ss == null) return null;
		InterfaceAddress local = getLocalInterfaceAddress(ss.getInetAddress());
		if (local == null) {
			return null;
		}
		InetSocketAddress remote;
		try {
			remote = parseSocketAddress(descriptor);
		} catch (FormatException e) {
			return null;
		}
		if (!isConnectable(local, remote)) {
			return null;
		}
		Socket s = new Socket();
		try {
			s.bind(new InetSocketAddress(ss.getInetAddress(), 0));
			s.connect(remote, connectionTimeout);
			s.setSoTimeout(socketTimeout);
			return new TcpTransportConnection(this, s);
		} catch (IOException e) {
			tryToClose(s);
			return null;
		}
	}

	private InetSocketAddress parseSocketAddress(BdfList descriptor)
			throws FormatException {
		byte[] address = descriptor.getRaw(1);
		int port = descriptor.getInt(2);
		if (port < 1 || port > MAX_16_BIT_UNSIGNED) throw new FormatException();
		try {
			InetAddress addr = InetAddress.getByAddress(address);
			return new InetSocketAddress(addr, port);
		} catch (UnknownHostException e) {
			throw new FormatException();
		}
	}

	private class LanKeyAgreementListener extends KeyAgreementListener {

		private final ServerSocket ss;

		private LanKeyAgreementListener(BdfList descriptor,
				ServerSocket ss) {
			super(descriptor);
			this.ss = ss;
		}

		@Override
		public KeyAgreementConnection accept() throws IOException {
			Socket s = ss.accept();
			return new KeyAgreementConnection(new TcpTransportConnection(
					LanTcpPlugin.this, s), ID);
		}

		@Override
		public void close() {
			tryToClose(ss);
		}
	}
}
