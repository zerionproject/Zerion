package org.zerionproject.core.api.plugin;

import static java.util.concurrent.TimeUnit.SECONDS;

public interface I2pConstants {

	TransportId ID = new TransportId("org.zerionproject.core.i2p");

	String PROP_I2P_DEST = "i2pDest";

	String I2P_PRIVATE_KEY = "i2pPrivKey";

	String DEFAULT_SAM_HOST = "127.0.0.1";
	int DEFAULT_SAM_PORT = 7656;

	String SESSION_ID = "zerion";

	int SAM_CONNECT_TIMEOUT = (int) SECONDS.toMillis(30);
	int STREAM_SOCKET_TIMEOUT = (int) SECONDS.toMillis(60);

	boolean DEFAULT_PREF_PLUGIN_ENABLE = false;

	String PREF_I2P_DIRECT_RESEED = "directReseed";

	boolean DEFAULT_PREF_I2P_DIRECT_RESEED = false;
}
