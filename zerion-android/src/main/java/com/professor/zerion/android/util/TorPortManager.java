package com.professor.zerion.android.util;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.ServerSocket;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_CONTROL_PORT;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_SOCKS_PORT;
import static org.briarproject.bramble.api.plugin.TorConstants.MAX_DYNAMIC_PORT;
import static org.briarproject.bramble.api.plugin.TorConstants.MIN_DYNAMIC_PORT;


@NotNullByDefault
public class TorPortManager {

	private static final String PREF_SOCKS_PORT = "tp_socks";
	private static final String PREF_CONTROL_PORT = "tp_ctrl";

	private final SharedPreferences prefs;
	private int socksPort = -1;
	private int controlPort = -1;

	public TorPortManager(SharedPreferences prefs) {
		this.prefs = prefs;
		initializePorts();
	}

	
	private void initializePorts() {
		int savedSocksPort = prefs.getInt(PREF_SOCKS_PORT, -1);
		int savedControlPort = prefs.getInt(PREF_CONTROL_PORT, -1);

		if (savedSocksPort > 0 && savedControlPort > 0) {
			if (isPortAvailable(savedSocksPort) && isPortAvailable(savedControlPort)) {
				socksPort = savedSocksPort;
				controlPort = savedControlPort;
				return;
			}
		}

		if (isPortAvailable(DEFAULT_SOCKS_PORT) && isPortAvailable(DEFAULT_CONTROL_PORT)) {
			socksPort = DEFAULT_SOCKS_PORT;
			controlPort = DEFAULT_CONTROL_PORT;
			savePorts();
			return;
		}
		socksPort = findAvailablePort(MIN_DYNAMIC_PORT);
		if (socksPort > 0) {
			int preferredControlPort = socksPort + 1;
			if (isPortAvailable(preferredControlPort)) {
				controlPort = preferredControlPort;
			} else {
				controlPort = findAvailablePort(socksPort + 2);
			}
		}

		if (socksPort > 0 && controlPort > 0) {
			savePorts();
		} else {
			socksPort = DEFAULT_SOCKS_PORT;
			controlPort = DEFAULT_CONTROL_PORT;
		}
	}

	
	private boolean isPortAvailable(int port) {
		if (port < 1 || port > 65535) return false;

		int savedTag = android.net.TrafficStats.getThreadStatsTag();
		android.net.TrafficStats.setThreadStatsTag(0xFE);
		ServerSocket socket = null;
		try {
			socket = new ServerSocket(port);
			socket.setReuseAddress(true);
			return true;
		} catch (IOException e) {
			return false;
		} finally {
			android.net.TrafficStats.setThreadStatsTag(savedTag);
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
				}
			}
		}
	}

	
	private int findAvailablePort(int startPort) {
		for (int port = startPort; port <= MAX_DYNAMIC_PORT; port++) {
			if (isPortAvailable(port)) {
				return port;
			}
		}
		return -1;
	}

	
	private void savePorts() {
		prefs.edit()
				.putInt(PREF_SOCKS_PORT, socksPort)
				.putInt(PREF_CONTROL_PORT, controlPort)
				.apply();
	}

	
	public int getSocksPort() {
		return socksPort;
	}

	
	public int getControlPort() {
		return controlPort;
	}

	
	public void resetPorts() {
		prefs.edit()
				.remove(PREF_SOCKS_PORT)
				.remove(PREF_CONTROL_PORT)
				.apply();
		socksPort = -1;
		controlPort = -1;
		initializePorts();
	}
}
