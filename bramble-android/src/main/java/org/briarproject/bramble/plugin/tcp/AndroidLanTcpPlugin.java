package org.briarproject.bramble.plugin.tcp;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.briarproject.bramble.PoliteExecutor;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.network.event.NetworkStatusEvent;
import org.briarproject.bramble.api.plugin.Backoff;
import org.briarproject.bramble.api.plugin.PluginCallback;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static android.content.Context.WIFI_SERVICE;
import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static java.util.Collections.emptyList;
import static java.util.Collections.list;
import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.plugin.LanTcpConstants.DEFAULT_PREF_PLUGIN_ENABLE;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.api.plugin.Plugin.State.INACTIVE;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static org.briarproject.bramble.util.NetworkUtils.getNetworkInterfaces;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@NotNullByDefault
class AndroidLanTcpPlugin extends LanTcpPlugin {
	
	private static final Pattern AP_INTERFACE_NAME =
			Pattern.compile("^(wlan|ap|p2p)[-0-9]");

	private final Executor connectionStatusExecutor;
	private final ConnectivityManager connectivityManager;
	@Nullable
	private final WifiManager wifiManager;

	private volatile SocketFactory socketFactory;

	AndroidLanTcpPlugin(Executor ioExecutor,
			Executor wakefulIoExecutor,
			Application app,
			Backoff backoff,
			PluginCallback callback,
			long maxLatency,
			int maxIdleTime,
			int connectionTimeout) {
		super(ioExecutor, wakefulIoExecutor, backoff, callback, maxLatency,
				maxIdleTime, connectionTimeout);
		connectionStatusExecutor =
				new PoliteExecutor("AndroidLanTcpPlugin", ioExecutor, 1);
		connectivityManager = (ConnectivityManager)
				requireNonNull(app.getSystemService(CONNECTIVITY_SERVICE));
		wifiManager = (WifiManager) app.getSystemService(WIFI_SERVICE);
		socketFactory = SocketFactory.getDefault();
	}

	@Override
	public void start() {
		if (used.getAndSet(true)) throw new IllegalStateException();
		initialisePortProperty();
		Settings settings = callback.getSettings();
		state.setStarted(settings.getBoolean(PREF_PLUGIN_ENABLE,
				DEFAULT_PREF_PLUGIN_ENABLE));
		updateConnectionStatus();
	}

	@Override
	protected Socket createSocket() throws IOException {
		return socketFactory.createSocket();
	}

	@Override
	protected List<InetAddress> getUsableLocalInetAddresses(boolean ipv4) {
		InetAddress addr = getWifiAddress(ipv4);
		return addr == null ? emptyList() : singletonList(addr);
	}

	@Nullable
	private InetAddress getWifiAddress(boolean ipv4) {
		Pair<InetAddress, Boolean> wifi = getWifiIpv4Address();
		if (ipv4) return wifi == null ? null : wifi.getFirst();
		if (wifi == null) {
			return getWifiClientIpv6Address();
		}
		return getIpv6AddressForInterface(wifi.getFirst());
	}

	
	@Nullable
	private Pair<InetAddress, Boolean> getWifiIpv4Address() {
		if (wifiManager == null) return null;
		WifiInfo info = wifiManager.getConnectionInfo();
		if (info != null && info.getIpAddress() != 0) {
			return new Pair<>(intToInetAddress(info.getIpAddress()), false);
		}
		for (NetworkInterface iface : getNetworkInterfaces()) {
			if (AP_INTERFACE_NAME.matcher(iface.getName()).find()) {
				for (InterfaceAddress ifAddr : iface.getInterfaceAddresses()) {
					if (isPossibleWifiApInterface(ifAddr)) {
						return new Pair<>(ifAddr.getAddress(), true);
					}
				}
			}
		}
		return null;
	}

	
	private boolean isPossibleWifiApInterface(InterfaceAddress ifAddr) {
		if (ifAddr.getNetworkPrefixLength() != 24) return false;
		byte[] ip = ifAddr.getAddress().getAddress();
		return ip.length == 4
				&& ip[0] == (byte) 192
				&& ip[1] == (byte) 168;
	}

	
	@Nullable
	private InetAddress getWifiClientIpv6Address() {
		try {
			for (Network net : connectivityManager.getAllNetworks()) {
				NetworkCapabilities caps =
						connectivityManager.getNetworkCapabilities(net);
				if (caps == null || !caps.hasTransport(TRANSPORT_WIFI)) {
					continue;
				}
				LinkProperties props =
						connectivityManager.getLinkProperties(net);
				if (props == null) continue;
				for (LinkAddress linkAddress : props.getLinkAddresses()) {
					InetAddress addr = linkAddress.getAddress();
					if (isIpv6LinkLocalAddress(addr)) return addr;
				}
			}
		} catch (SecurityException e) {
		}
		return null;
	}

	
	@Nullable
	private InetAddress getIpv6AddressForInterface(InetAddress ipv4) {
		try {
			NetworkInterface iface = NetworkInterface.getByInetAddress(ipv4);
			if (iface == null) return null;
			for (InetAddress addr : list(iface.getInetAddresses())) {
				if (isIpv6LinkLocalAddress(addr)) return addr;
			}
			return null;
		} catch (SocketException | NullPointerException e) {
			return null;
		}
	}

	private InetAddress intToInetAddress(int ip) {
		byte[] ipBytes = new byte[4];
		ipBytes[0] = (byte) (ip & 0xFF);
		ipBytes[1] = (byte) ((ip >> 8) & 0xFF);
		ipBytes[2] = (byte) ((ip >> 16) & 0xFF);
		ipBytes[3] = (byte) ((ip >> 24) & 0xFF);
		try {
			return InetAddress.getByAddress(ipBytes);
		} catch (UnknownHostException e) {
			throw new AssertionError(e);
		}
	}
	private SocketFactory getSocketFactory() {
		try {
			for (Network net : connectivityManager.getAllNetworks()) {
				NetworkCapabilities caps =
						connectivityManager.getNetworkCapabilities(net);
				if (caps != null && caps.hasTransport(TRANSPORT_WIFI)) {
					return net.getSocketFactory();
				}
			}
		} catch (SecurityException e) {
		}
		return SocketFactory.getDefault();
	}

	@Override
	public void eventOccurred(Event e) {
		super.eventOccurred(e);
		if (e instanceof NetworkStatusEvent) updateConnectionStatus();
	}

	private void updateConnectionStatus() {
		connectionStatusExecutor.execute(() -> {
			State s = getState();
			if (s != ACTIVE && s != INACTIVE) return;
			Pair<InetAddress, Boolean> wifi = getPreferredWifiAddress();
			if (wifi == null) {
				socketFactory = SocketFactory.getDefault();
				if (s == ACTIVE) {
					tryToClose(state.getServerSocket(true));
					tryToClose(state.getServerSocket(false));
				}
			} else if (wifi.getSecond()) {
				socketFactory = SocketFactory.getDefault();
				bind();
			} else {
				socketFactory = getSocketFactory();
				bind();
			}
		});
	}

	
	@Nullable
	private Pair<InetAddress, Boolean> getPreferredWifiAddress() {
		Pair<InetAddress, Boolean> wifi = getWifiIpv4Address();
		if (wifi == null) {
			InetAddress ipv6 = getWifiClientIpv6Address();
			if (ipv6 != null) return new Pair<>(ipv6, false);
		}
		return wifi;
	}
}