package org.zerionproject.core.api.plugin;

import static java.util.concurrent.TimeUnit.SECONDS;

public interface I2pConstants {

	TransportId ID = new TransportId("org.zerionproject.core.i2p");

	/** The peer's (and our own) base64 I2P destination, exchanged at pairing. */
	String PROP_I2P_DEST = "i2pDest";

	/** The persisted destination private key that recreates the same
	 * destination across restarts, analogous to the onion private key. */
	String I2P_PRIVATE_KEY = "i2pPrivKey";

	/** Local SAM v3 bridge exposed by the bundled I2P router. */
	String DEFAULT_SAM_HOST = "127.0.0.1";
	int DEFAULT_SAM_PORT = 7656;

	/** Nickname for the SAM session. */
	String SESSION_ID = "zerion";

	/** Connecting to the local SAM bridge is fast; building I2P tunnels is
	 * not, so the per-stream socket keeps a generous read timeout. */
	int SAM_CONNECT_TIMEOUT = (int) SECONDS.toMillis(30);
	int STREAM_SOCKET_TIMEOUT = (int) SECONDS.toMillis(60);

	/** Default for the shared {@link Plugin#PREF_PLUGIN_ENABLE} preference.
	 * Off by default: I2P is a separate anonymity network from Tor and its
	 * router makes direct connections that reveal the device's IP to I2P peers,
	 * so it must be a conscious, warned opt-in and never start on its own. The
	 * user turns it on with the I2P toggle. */
	boolean DEFAULT_PREF_PLUGIN_ENABLE = false;

	String PREF_I2P_DIRECT_RESEED = "directReseed";

	boolean DEFAULT_PREF_I2P_DIRECT_RESEED = false;
}
