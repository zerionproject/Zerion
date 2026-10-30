package com.professor.zerion.android.settings;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class MeshBluetoothPromptTest {

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void turningTheMeshOnWithBluetoothOffAsksToTurnItOn() {
		shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(false);
		Intent ask = ConnectionsFragment.bluetoothOnRequest(ctx);
		assertNotNull(ask);
		assertEquals(BluetoothAdapter.ACTION_REQUEST_ENABLE, ask.getAction());
	}

	@Test
	public void withBluetoothOnNothingIsAsked() {
		shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true);
		assertNull(ConnectionsFragment.bluetoothOnRequest(ctx));
	}
}
