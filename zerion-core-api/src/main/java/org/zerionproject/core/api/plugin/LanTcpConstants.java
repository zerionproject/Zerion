package org.zerionproject.core.api.plugin;

public interface LanTcpConstants {

	TransportId ID = new TransportId("org.zerionproject.core.lan");
	String PROP_IP_PORTS = "ipPorts";
	String PROP_PORT = "port";
	String PROP_IPV6 = "ipv6";
	String PREF_LAN_IP_PORTS = "ipPorts";
	String PREF_IPV6 = "ipv6";
	boolean DEFAULT_PREF_PLUGIN_ENABLE = true;
}
