package org.zerionproject.core.api.plugin;

import static java.util.concurrent.TimeUnit.SECONDS;

public interface TorConstants {

	TransportId ID = new TransportId("org.zerionproject.core.tor");
	String PROP_ONION_V3 = "onion3";
	int DEFAULT_SOCKS_PORT = 59060;
	int DEFAULT_CONTROL_PORT = 59061;
	int MIN_DYNAMIC_PORT = 59100;
	int MAX_DYNAMIC_PORT = 59199;

	int CONNECT_TO_PROXY_TIMEOUT = (int) SECONDS.toMillis(5);
	int EXTRA_CONNECT_TIMEOUT = (int) SECONDS.toMillis(60);
	int FAST_CONNECT_TIMEOUT = (int) SECONDS.toMillis(20);
	int EXTRA_SOCKET_TIMEOUT = (int) SECONDS.toMillis(30);
	String PREF_TOR_NETWORK = "network2";
	String PREF_TOR_PORT = "port";
	String PREF_TOR_MOBILE = "useMobileData";
	String PREF_TOR_ONLY_WHEN_CHARGING = "onlyWhenCharging";
	String PREF_TOR_CUSTOM_BRIDGES = "customBridges";
	String HS_PRIVATE_KEY_V3 = "onionPrivKey3";
	int PREF_TOR_NETWORK_AUTOMATIC = 0;
	int PREF_TOR_NETWORK_WITHOUT_BRIDGES = 1;
	int PREF_TOR_NETWORK_WITH_BRIDGES = 2;
	boolean DEFAULT_PREF_PLUGIN_ENABLE = true;
	int DEFAULT_PREF_TOR_NETWORK = PREF_TOR_NETWORK_AUTOMATIC;
	boolean DEFAULT_PREF_TOR_MOBILE = true;
	boolean DEFAULT_PREF_TOR_ONLY_WHEN_CHARGING = false;

	int REASON_BATTERY = 2;

	int REASON_MOBILE_DATA = 4;

	int REASON_COUNTRY_BLOCKED = 8;
}
