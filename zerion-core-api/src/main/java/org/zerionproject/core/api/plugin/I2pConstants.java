package org.zerionproject.core.api.plugin;

public interface I2pConstants {

	TransportId ID = new TransportId("org.zerionproject.core.i2p");

	String PROP_I2P_DEST = "i2pDest";

	String I2P_PRIVATE_KEY = "i2pPrivKey";

	String SESSION_ID = "zerion";

	boolean DEFAULT_PREF_PLUGIN_ENABLE = false;

	String PREF_I2P_DIRECT_RESEED = "directReseed";

	boolean DEFAULT_PREF_I2P_DIRECT_RESEED = false;
}
