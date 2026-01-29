package org.briarproject.bramble.api.plugin;

public interface BluetoothConstants {

	TransportId ID = new TransportId("org.briarproject.bramble.bluetooth");

	int UUID_BYTES = 16;
	String PROP_ADDRESS = "address";
	String PROP_UUID = "uuid";
	String PREF_ADDRESS_IS_REFLECTED = "addressIsReflected";
	String PREF_EVER_CONNECTED = "everConnected";
	boolean DEFAULT_PREF_PLUGIN_ENABLE = false;
	boolean DEFAULT_PREF_ADDRESS_IS_REFLECTED = false;
	boolean DEFAULT_PREF_EVER_CONNECTED = false;
}
