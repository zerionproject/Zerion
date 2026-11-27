package com.professor.zerion.android.mesh;

import android.bluetooth.le.AdvertisingSetParameters;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Random;

@NotNullByDefault
public final class BleAdvertising {

	public static final int MIN_INTERVAL =
			AdvertisingSetParameters.INTERVAL_LOW;
	public static final int MAX_INTERVAL =
			AdvertisingSetParameters.INTERVAL_MEDIUM;
	static final long MIN_REPLACEMENT_DELAY_MS = 250L;
	static final int REPLACEMENT_DELAY_SPREAD_MS = 2_250;

	private BleAdvertising() {
	}

	public static AdvertisingSetParameters parameters(int txPowerLevel,
			Random random) {
		return new AdvertisingSetParameters.Builder()
				.setLegacyMode(true)
				.setConnectable(true)
				.setScannable(true)
				.setIncludeTxPower(false)
				.setInterval(MIN_INTERVAL
						+ random.nextInt(MAX_INTERVAL - MIN_INTERVAL + 1))
				.setTxPowerLevel(txPowerLevel)
				.build();
	}

	static long replacementDelayMs(Random random) {
		return MIN_REPLACEMENT_DELAY_MS
				+ random.nextInt(REPLACEMENT_DELAY_SPREAD_MS + 1);
	}
}
