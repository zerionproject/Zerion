package org.briarproject.bramble.plugin.bluetooth;

import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface BluetoothConnectionLimiter {

	
	void startLimiting();

	
	void endLimiting();

	
	boolean canOpenContactConnection();

	
	void connectionOpened(DuplexTransportConnection conn);

	
	void connectionClosed(DuplexTransportConnection conn);

	
	void allConnectionsClosed();
}
