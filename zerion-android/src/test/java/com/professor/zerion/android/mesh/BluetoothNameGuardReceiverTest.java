package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BluetoothNameGuardReceiverTest {

	@Test
	public void bluetoothSwitchedOnFinishesAPendingRestore() {
		Context context = RuntimeEnvironment.getApplication();
		BluetoothNameGuardTest.FakeAdapter adapter =
				new BluetoothNameGuardTest.FakeAdapter("Alice's Pixel");
		BluetoothNameGuard guard = new BluetoothNameGuard(context,
				new MeshTestSettings(), () -> adapter, Runnable::run);
		guard.acquire(BluetoothNameGuard.HOLDER_MESH);
		adapter.refuse = true;
		guard.release(BluetoothNameGuard.HOLDER_MESH);
		adapter.refuse = false;
		Intent on = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
		on.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
		context.sendBroadcast(on);
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals("Alice's Pixel", adapter.name);
	}
}
