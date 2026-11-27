package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;

import org.zerionproject.transport.mesh.MeshForwarder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.security.SecureRandom;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BleMeshScreenPauseTest {

	private static final long PAUSE_AFTER_MS = 50;

	private final BluetoothManager manager = mock(BluetoothManager.class);
	private final BluetoothAdapter adapter = mock(BluetoothAdapter.class);
	private final BluetoothLeAdvertiser advertiser =
			mock(BluetoothLeAdvertiser.class);
	private final BluetoothLeScanner scanner = mock(BluetoothLeScanner.class);
	private Context context;
	private BleMeshTransport transport;

	@Before
	public void setUp() {
		context = RuntimeEnvironment.getApplication();
		when(manager.getAdapter()).thenReturn(adapter);
		when(adapter.isEnabled()).thenReturn(true);
		when(adapter.getBluetoothLeAdvertiser()).thenReturn(advertiser);
		when(adapter.getBluetoothLeScanner()).thenReturn(scanner);
		when(manager.openGattServer(any(Context.class),
				any(BluetoothGattServerCallback.class)))
				.thenReturn(mock(BluetoothGattServer.class));
		MeshForwarder forwarder = new MeshForwarder(payload -> {
		}, new SecureRandom());
		transport = new BleMeshTransport(context, forwarder, null, null,
				manager, PAUSE_AFTER_MS);
	}

	@After
	public void tearDown() {
		transport.stop();
	}

	@Test
	public void byDefaultTheMeshKeepsRunningWithTheScreenOff() {
		transport.start();
		screen(Intent.ACTION_SCREEN_OFF);
		verify(advertiser, after(10 * PAUSE_AFTER_MS).never())
				.stopAdvertisingSet(any());
		verify(scanner, never()).stopScan(any(ScanCallback.class));
	}

	@Test
	public void theMeshFallsSilentWhileTheScreenIsOffAndComesBack() {
		transport.setPauseWhenScreenOff(true);
		transport.start();
		verify(advertiser).startAdvertisingSet(any(), any(), any(),
				isNull(), isNull(), any(AdvertisingSetCallback.class));
		screen(Intent.ACTION_SCREEN_OFF);
		verify(advertiser, timeout(2_000)).stopAdvertisingSet(any());
		verify(scanner, timeout(2_000)).stopScan(any(ScanCallback.class));
		screen(Intent.ACTION_SCREEN_ON);
		verify(advertiser, timeout(2_000).times(2)).startAdvertisingSet(
				any(), any(), any(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
	}

	@Test
	public void bluetoothComingBackDoesNotWakeAPausedMesh() {
		transport.setPauseWhenScreenOff(true);
		transport.start();
		screen(Intent.ACTION_SCREEN_OFF);
		verify(advertiser, timeout(2_000)).stopAdvertisingSet(any());
		Intent on = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
		on.putExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
		context.sendBroadcast(on);
		shadowOf(Looper.getMainLooper()).idle();
		verify(advertiser, after(10 * PAUSE_AFTER_MS).times(1))
				.startAdvertisingSet(any(), any(), any(), isNull(), isNull(),
						any(AdvertisingSetCallback.class));
	}

	private void screen(String action) {
		context.sendBroadcast(new Intent(action));
		shadowOf(Looper.getMainLooper()).idle();
	}
}
