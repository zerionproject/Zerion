package com.professor.zerion.android.mesh;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.os.ParcelUuid;

import org.zerionproject.transport.mesh.MeshForwarder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32)
public class BleMeshAdvertisingTest {

	private static final UUID GATT_SERVICE_UUID =
			UUID.fromString("9f2a7c14-6b38-4d0e-8a51-c3e7d924b6f8");

	private final BluetoothManager manager = mock(BluetoothManager.class);
	private final BluetoothAdapter adapter = mock(BluetoothAdapter.class);
	private final BluetoothLeAdvertiser advertiser =
			mock(BluetoothLeAdvertiser.class);
	private BleMeshTransport transport;

	@Before
	public void setUp() {
		Context context = RuntimeEnvironment.getApplication();
		when(manager.getAdapter()).thenReturn(adapter);
		when(adapter.isEnabled()).thenReturn(true);
		when(adapter.getBluetoothLeAdvertiser()).thenReturn(advertiser);
		when(adapter.getBluetoothLeScanner())
				.thenReturn(mock(BluetoothLeScanner.class));
		when(manager.openGattServer(any(Context.class),
				any(BluetoothGattServerCallback.class)))
				.thenReturn(mock(BluetoothGattServer.class));
		MeshForwarder forwarder = new MeshForwarder(payload -> {
		}, new SecureRandom());
		transport = new BleMeshTransport(context, forwarder, null, null,
				manager);
	}

	@After
	public void tearDown() {
		transport.stop();
	}

	@Test
	public void theMeshAdvertisesTheSameDataThroughAnAdvertisingSet() {
		transport.start();
		ArgumentCaptor<AdvertisingSetParameters> params =
				ArgumentCaptor.forClass(AdvertisingSetParameters.class);
		ArgumentCaptor<AdvertiseData> data =
				ArgumentCaptor.forClass(AdvertiseData.class);
		ArgumentCaptor<AdvertiseData> response =
				ArgumentCaptor.forClass(AdvertiseData.class);
		verify(advertiser, never()).startAdvertising(
				any(AdvertiseSettings.class), any(AdvertiseData.class),
				any(AdvertiseData.class), any(AdvertiseCallback.class));
		verify(advertiser).startAdvertisingSet(params.capture(),
				data.capture(), response.capture(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
		AdvertisingSetParameters p = params.getValue();
		assertTrue(p.isLegacy());
		assertTrue(p.isConnectable());
		assertTrue(p.isScannable());
		assertFalse(p.includeTxPower());
		assertEquals(AdvertisingSetParameters.TX_POWER_MEDIUM,
				p.getTxPowerLevel());
		assertTrue(p.getInterval() >= 160 && p.getInterval() <= 400);
		AdvertiseData d = data.getValue();
		assertFalse(d.getIncludeDeviceName());
		assertFalse(d.getIncludeTxPowerLevel());
		assertEquals(Collections.singletonList(new ParcelUuid(
						MeshDiscovery.discoveryUuid(
								MeshDiscovery.currentEpoch(),
								GATT_SERVICE_UUID))),
				d.getServiceUuids());
		assertEquals(0, d.getManufacturerSpecificData().size());
		AdvertiseData r = response.getValue();
		assertFalse(r.getIncludeDeviceName());
		byte[] nonce = r.getManufacturerSpecificData().get(0xFFFF);
		assertEquals(MeshLinkPolicy.NONCE_BYTES, nonce.length);
		assertEquals((byte) 0xFF, nonce[0]);
		assertEquals((byte) 0xFF, nonce[1]);
	}

	@Test
	public void everyAdvertisingSetPicksItsOwnInterval() throws Exception {
		transport.start();
		for (int i = 0; i < 40; i++) {
			startAdvertising(new MeshLinkPolicy(new SecureRandom())
					.freshNonce());
		}
		ArgumentCaptor<AdvertisingSetParameters> params =
				ArgumentCaptor.forClass(AdvertisingSetParameters.class);
		verify(advertiser, times(41)).startAdvertisingSet(params.capture(),
				any(), any(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
		Set<Integer> intervals = new HashSet<>();
		for (AdvertisingSetParameters p : params.getAllValues()) {
			assertTrue(p.getInterval() >= 160 && p.getInterval() <= 400);
			intervals.add(p.getInterval());
		}
		assertTrue("intervals " + intervals, intervals.size() > 1);
	}

	@Test
	public void aReplacedSetStopsAtOnceAndTheNextStartsAfterAPause()
			throws Exception {
		transport.start();
		ArgumentCaptor<AdvertisingSetCallback> first =
				ArgumentCaptor.forClass(AdvertisingSetCallback.class);
		verify(advertiser).startAdvertisingSet(any(), any(), any(),
				isNull(), isNull(), first.capture());
		byte[] fresh = new MeshLinkPolicy(new SecureRandom()).freshNonce();
		Method m = BleMeshTransport.class.getDeclaredMethod(
				"switchAdvertisingSet", byte[].class);
		m.setAccessible(true);
		m.invoke(transport, fresh);
		verify(advertiser).stopAdvertisingSet(first.getValue());
		verify(advertiser, after(200).times(1)).startAdvertisingSet(any(),
				any(), any(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
		ArgumentCaptor<AdvertiseData> response =
				ArgumentCaptor.forClass(AdvertiseData.class);
		ArgumentCaptor<AdvertisingSetCallback> callbacks =
				ArgumentCaptor.forClass(AdvertisingSetCallback.class);
		verify(advertiser, timeout(5_000).times(2)).startAdvertisingSet(
				any(), any(), response.capture(), isNull(), isNull(),
				callbacks.capture());
		List<AdvertiseData> all = response.getAllValues();
		assertArrayEquals(fresh, all.get(all.size() - 1)
				.getManufacturerSpecificData().get(0xFFFF));
		List<AdvertisingSetCallback> cbs = callbacks.getAllValues();
		assertNotSame(cbs.get(0), cbs.get(cbs.size() - 1));
	}

	@Test
	public void theShortRangeAdvertisesAtLowPower() {
		transport.setShortRange(true);
		transport.start();
		ArgumentCaptor<AdvertisingSetParameters> params =
				ArgumentCaptor.forClass(AdvertisingSetParameters.class);
		verify(advertiser).startAdvertisingSet(params.capture(), any(),
				any(), isNull(), isNull(), any(AdvertisingSetCallback.class));
		assertEquals(AdvertisingSetParameters.TX_POWER_LOW,
				params.getValue().getTxPowerLevel());
	}

	@Test
	public void changingTheRangeReplacesTheRunningSet() {
		transport.start();
		ArgumentCaptor<AdvertisingSetCallback> first =
				ArgumentCaptor.forClass(AdvertisingSetCallback.class);
		verify(advertiser).startAdvertisingSet(any(), any(), any(),
				isNull(), isNull(), first.capture());
		transport.setShortRange(true);
		verify(advertiser, timeout(2_000)).stopAdvertisingSet(
				first.getValue());
		ArgumentCaptor<AdvertisingSetParameters> params =
				ArgumentCaptor.forClass(AdvertisingSetParameters.class);
		verify(advertiser, timeout(5_000).times(2)).startAdvertisingSet(
				params.capture(), any(), any(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
		List<AdvertisingSetParameters> all = params.getAllValues();
		assertEquals(AdvertisingSetParameters.TX_POWER_LOW,
				all.get(all.size() - 1).getTxPowerLevel());
	}

	private void startAdvertising(byte[] nonce) throws Exception {
		Method m = BleMeshTransport.class.getDeclaredMethod(
				"startAdvertising", BluetoothAdapter.class, byte[].class);
		m.setAccessible(true);
		m.invoke(transport, adapter, nonce);
	}
}
