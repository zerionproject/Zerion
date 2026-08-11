package com.professor.zerion.android.contact.add.nearby.ble;

import org.zerionproject.core.api.plugin.BluetoothConstants;
import org.zerionproject.core.api.plugin.TransportId;

import java.util.UUID;

public interface BluetoothKeyAgreementConstants {

	TransportId ID = BluetoothConstants.ID;

	UUID FRAME_CHARACTERISTIC =
			UUID.fromString("a4c1d830-7e59-4b21-9f6d-1c8b0e3a52d7");

	UUID CCCD =
			UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

	int MAX_LATENCY = 30_000;
	int MAX_IDLE_TIME = 30_000;
}
