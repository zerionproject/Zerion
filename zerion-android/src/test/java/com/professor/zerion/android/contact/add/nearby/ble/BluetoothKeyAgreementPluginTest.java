package com.professor.zerion.android.contact.add.nearby.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;

import com.professor.zerion.android.mesh.BluetoothNameGuard;

import org.zerionproject.core.api.keyagreement.KeyAgreementListener;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BluetoothKeyAgreementPluginTest {

	private final BluetoothManager manager = mock(BluetoothManager.class);
	private final BluetoothAdapter adapter = mock(BluetoothAdapter.class);
	private final BluetoothGattServer server = mock(BluetoothGattServer.class);
	private final BluetoothLeAdvertiser advertiser =
			mock(BluetoothLeAdvertiser.class);
	private final BluetoothNameGuard guard = mock(BluetoothNameGuard.class);
	private final byte[] commitment = new byte[16];
	private BluetoothKeyAgreementPlugin plugin;

	@Before
	public void setUp() {
		Context context = RuntimeEnvironment.getApplication();
		when(manager.getAdapter()).thenReturn(adapter);
		when(adapter.isEnabled()).thenReturn(true);
		when(adapter.getBluetoothLeAdvertiser()).thenReturn(advertiser);
		when(manager.openGattServer(any(Context.class),
				any(BluetoothGattServerCallback.class))).thenReturn(server);
		plugin = new BluetoothKeyAgreementPlugin(context,
				mock(PluginCallback.class), guard, manager);
		for (int i = 0; i < commitment.length; i++) commitment[i] = (byte) i;
	}

	@Test
	public void theRealNameIsHiddenBeforePairNearbyAdvertises() {
		KeyAgreementListener listener =
				plugin.createKeyAgreementListener(commitment);
		assertNotNull(listener);
		InOrder order = inOrder(guard, advertiser);
		order.verify(guard).acquire(BluetoothNameGuard.HOLDER_PAIRING);
		order.verify(advertiser).startAdvertisingSet(
				any(AdvertisingSetParameters.class), any(AdvertiseData.class),
				isNull(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
	}

	@Test
	public void theRealNameComesBackWhenPairNearbyStops() {
		KeyAgreementListener listener =
				plugin.createKeyAgreementListener(commitment);
		assertNotNull(listener);
		listener.close();
		verify(guard).release(BluetoothNameGuard.HOLDER_PAIRING);
	}

	@Test
	public void phonesSideBySideAdvertiseAtLowPower() {
		plugin.createKeyAgreementListener(commitment);
		ArgumentCaptor<AdvertisingSetParameters> params =
				ArgumentCaptor.forClass(AdvertisingSetParameters.class);
		verify(advertiser).startAdvertisingSet(params.capture(),
				any(AdvertiseData.class), isNull(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
		AdvertisingSetParameters p = params.getValue();
		assertEquals(AdvertisingSetParameters.TX_POWER_LOW,
				p.getTxPowerLevel());
		assertTrue(p.isLegacy());
		assertTrue(p.isConnectable());
		assertTrue(p.getInterval() >= 160 && p.getInterval() <= 400);
	}
}
