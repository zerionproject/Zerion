package com.professor.zerion.android.contact.add.nearby.ble;

import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.duplex.AbstractDuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.annotation.concurrent.ThreadSafe;

/**
 * A duplex connection over a point-to-point BLE GATT link, used only to carry
 * the offline pairing key agreement and contact exchange.
 */
@ThreadSafe
@NotNullByDefault
class BluetoothKeyAgreementConnection extends AbstractDuplexTransportConnection {

	private final BleGattStream stream;

	BluetoothKeyAgreementConnection(Plugin plugin, BleGattStream stream) {
		super(plugin);
		this.stream = stream;
	}

	@Override
	protected InputStream getInputStream() throws IOException {
		return stream.getInputStream();
	}

	@Override
	protected OutputStream getOutputStream() throws IOException {
		return stream.getOutputStream();
	}

	@Override
	protected void closeConnection(boolean exception) throws IOException {
		stream.close();
	}
}
