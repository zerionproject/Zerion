package com.professor.zerion.android.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_CONTROL_PORT;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_SOCKS_PORT;
import static org.briarproject.bramble.api.plugin.TorConstants.MAX_DYNAMIC_PORT;
import static org.briarproject.bramble.api.plugin.TorConstants.MIN_DYNAMIC_PORT;

/**
 * Manages Tor port selection to avoid conflicts with other apps like Briar.
 * Checks if ports are available and dynamically selects alternatives if needed.
 */
@NotNullByDefault
public class TorPortManager {

	private static final Logger LOG = getLogger(TorPortManager.class.getName());

	private static final String PREFS_NAME = "zerion_tor_ports";
	private static final String PREF_SOCKS_PORT = "socks_port";
	private static final String PREF_CONTROL_PORT = "control_port";

	private final SharedPreferences prefs;
	private int socksPort = -1;
	private int controlPort = -1;

	public TorPortManager(Context context) {
		this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
		initializePorts();
	}

	/**
	 * Initialize ports - either from saved preferences or by finding available ports.
	 */
	private void initializePorts() {
		// Try to load saved ports first
		int savedSocksPort = prefs.getInt(PREF_SOCKS_PORT, -1);
		int savedControlPort = prefs.getInt(PREF_CONTROL_PORT, -1);

		if (savedSocksPort > 0 && savedControlPort > 0) {
			// Check if saved ports are still available
			if (isPortAvailable(savedSocksPort) && isPortAvailable(savedControlPort)) {
				socksPort = savedSocksPort;
				controlPort = savedControlPort;
				LOG.log(INFO, "Using saved Tor ports: SOCKS={0}, Control={1}",
						new Object[]{socksPort, controlPort});
				return;
			}
			LOG.log(WARNING, "Saved ports no longer available, finding new ports");
		}

		// Try default ports first
		if (isPortAvailable(DEFAULT_SOCKS_PORT) && isPortAvailable(DEFAULT_CONTROL_PORT)) {
			socksPort = DEFAULT_SOCKS_PORT;
			controlPort = DEFAULT_CONTROL_PORT;
			savePorts();
			LOG.log(INFO, "Using default Tor ports: SOCKS={0}, Control={1}",
					new Object[]{socksPort, controlPort});
			return;
		}

		LOG.log(INFO, "Default ports unavailable, searching for available ports...");

		// Find available ports in the dynamic range
		socksPort = findAvailablePort(MIN_DYNAMIC_PORT);
		if (socksPort > 0) {
			// Control port is next to SOCKS port
			int preferredControlPort = socksPort + 1;
			if (isPortAvailable(preferredControlPort)) {
				controlPort = preferredControlPort;
			} else {
				controlPort = findAvailablePort(socksPort + 2);
			}
		}

		if (socksPort > 0 && controlPort > 0) {
			savePorts();
			LOG.log(INFO, "Using dynamic Tor ports: SOCKS={0}, Control={1}",
					new Object[]{socksPort, controlPort});
		} else {
			// Fallback to defaults even if they might conflict
			// Tor will handle the error
			socksPort = DEFAULT_SOCKS_PORT;
			controlPort = DEFAULT_CONTROL_PORT;
			LOG.log(WARNING, "Could not find available ports, using defaults which may conflict");
		}
	}

	/**
	 * Check if a port is available for binding.
	 */
	private boolean isPortAvailable(int port) {
		if (port < 1 || port > 65535) return false;

		ServerSocket socket = null;
		try {
			socket = new ServerSocket(port);
			socket.setReuseAddress(true);
			return true;
		} catch (IOException e) {
			return false;
		} finally {
			if (socket != null) {
				try {
					socket.close();
				} catch (IOException e) {
					// Ignore
				}
			}
		}
	}

	/**
	 * Find an available port starting from the given port.
	 */
	private int findAvailablePort(int startPort) {
		for (int port = startPort; port <= MAX_DYNAMIC_PORT; port++) {
			if (isPortAvailable(port)) {
				return port;
			}
		}
		return -1;
	}

	/**
	 * Save the selected ports to preferences.
	 */
	private void savePorts() {
		prefs.edit()
				.putInt(PREF_SOCKS_PORT, socksPort)
				.putInt(PREF_CONTROL_PORT, controlPort)
				.apply();
	}

	/**
	 * Get the SOCKS port to use for Tor.
	 */
	public int getSocksPort() {
		return socksPort;
	}

	/**
	 * Get the control port to use for Tor.
	 */
	public int getControlPort() {
		return controlPort;
	}

	/**
	 * Force re-selection of ports. Call this if there's a port conflict at runtime.
	 */
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
