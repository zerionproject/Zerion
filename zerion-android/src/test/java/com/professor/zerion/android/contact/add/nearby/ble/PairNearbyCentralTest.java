package com.professor.zerion.android.contact.add.nearby.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;

import com.professor.zerion.android.mesh.BluetoothNameGuard;

import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;
import org.zerionproject.core.api.keyagreement.KeyAgreementListener;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
public class PairNearbyCentralTest {

	private static final long PROOF_TIMEOUT_MS = 100;
	private static final long BACK_OFF_MS = 200;

	private final BluetoothManager manager = mock(BluetoothManager.class);
	private final BluetoothAdapter adapter = mock(BluetoothAdapter.class);
	private final BluetoothGattServer server = mock(BluetoothGattServer.class);
	private final BluetoothLeAdvertiser advertiser =
			mock(BluetoothLeAdvertiser.class);
	private final BluetoothLeScanner scanner = mock(BluetoothLeScanner.class);
	private final BluetoothNameGuard guard = mock(BluetoothNameGuard.class);
	private final byte[] commitment = new byte[16];
	private final ExecutorService exec = Executors.newCachedThreadPool();
	private Context context;
	private BluetoothKeyAgreementPlugin plugin;

	@Before
	public void setUp() {
		context = RuntimeEnvironment.getApplication();
		when(manager.getAdapter()).thenReturn(adapter);
		when(adapter.isEnabled()).thenReturn(true);
		when(adapter.getBluetoothLeAdvertiser()).thenReturn(advertiser);
		when(adapter.getBluetoothLeScanner()).thenReturn(scanner);
		when(manager.openGattServer(any(Context.class),
				any(BluetoothGattServerCallback.class))).thenReturn(server);
		plugin = new BluetoothKeyAgreementPlugin(context,
				mock(PluginCallback.class), guard, manager, PROOF_TIMEOUT_MS,
				BACK_OFF_MS);
		for (int i = 0; i < commitment.length; i++) commitment[i] = (byte) i;
	}

	@After
	public void tearDown() {
		exec.shutdownNow();
	}

	@Test
	public void aSilentCentralIsNotCutOffAndDoesNotBlockTheNextOne()
			throws Exception {
		KeyAgreementListener listener =
				plugin.createKeyAgreementListener(commitment);
		assertNotNull(listener);
		BluetoothGattServerCallback cb = serverCallback();
		BluetoothDevice stranger = device("AA:00:00:00:00:01");
		connected(cb, stranger);
		verify(server, after(5 * PROOF_TIMEOUT_MS).never())
				.cancelConnection(any());
		BluetoothDevice friend = device("BB:00:00:00:00:02");
		connected(cb, friend);
		proveAndSubscribe(cb, friend);
		assertNotNull(listener.accept());
	}

	@Test
	public void aProofArrivingAfterThreeSecondsIsAccepted() throws Exception {
		plugin = new BluetoothKeyAgreementPlugin(context,
				mock(PluginCallback.class), guard, manager);
		plugin.createKeyAgreementListener(commitment);
		BluetoothGattServerCallback cb = serverCallback();
		BluetoothDevice friend = device("BB:00:00:00:00:02");
		connected(cb, friend);
		Thread.sleep(3_000);
		cb.onCharacteristicWriteRequest(friend, 1, proofCharacteristic(),
				false, true, 0, expectedProof());
		verify(server).sendResponse(friend, 1, BluetoothGatt.GATT_SUCCESS, 0,
				null);
		verify(server, never()).cancelConnection(any());
	}

	@Test
	public void aWrongProofIsRefusedWithoutCuttingTheLink() {
		plugin.createKeyAgreementListener(commitment);
		BluetoothGattServerCallback cb = serverCallback();
		BluetoothDevice stranger = device("AA:00:00:00:00:01");
		connected(cb, stranger);
		cb.onCharacteristicWriteRequest(stranger, 1, proofCharacteristic(),
				false, true, 0, wrongProof());
		verify(server, never()).sendResponse(stranger, 1,
				BluetoothGatt.GATT_SUCCESS, 0, null);
		verify(server, never()).cancelConnection(any());
		cb.onDescriptorWriteRequest(stranger, 2, cccd(), false, true, 0,
				BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		verify(server, never()).sendResponse(stranger, 2,
				BluetoothGatt.GATT_SUCCESS, 0, null);
	}

	@Test
	public void aCentralWithoutProofCannotOpenTheStream() {
		plugin.createKeyAgreementListener(commitment);
		BluetoothGattServerCallback cb = serverCallback();
		BluetoothDevice stranger = device("AA:00:00:00:00:01");
		connected(cb, stranger);
		cb.onDescriptorWriteRequest(stranger, 7, cccd(), false, true, 0,
				BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		ArgumentCaptor<Integer> status = ArgumentCaptor.forClass(Integer.class);
		verify(server).sendResponse(eq(stranger), eq(7), status.capture(),
				eq(0), isNull());
		assertNotEquals(BluetoothGatt.GATT_SUCCESS, (int) status.getValue());
	}

	@Test
	public void aReusedLinkWithTheRightProofIsAccepted() throws Exception {
		KeyAgreementListener listener =
				plugin.createKeyAgreementListener(commitment);
		BluetoothGattServerCallback cb = serverCallback();
		BluetoothDevice friend = device("BB:00:00:00:00:02");
		proveAndSubscribe(cb, friend);
		assertNotNull(listener.accept());
	}

	@Test
	public void meshLinksComingUpDuringPairingAreNotCounted() throws Exception {
		KeyAgreementListener listener =
				plugin.createKeyAgreementListener(commitment);
		BluetoothGattServerCallback cb = serverCallback();
		for (int i = 0; i < 10; i++) {
			connected(cb, device("DD:00:00:00:00:1" + i));
		}
		verify(server, after(5 * PROOF_TIMEOUT_MS).never())
				.cancelConnection(any());
		verify(advertiser, never()).stopAdvertisingSet(any());
		BluetoothDevice friend = device("BB:00:00:00:00:02");
		connected(cb, friend);
		proveAndSubscribe(cb, friend);
		assertNotNull(listener.accept());
	}

	@Test
	public void ourOwnLinkIsNeverCountedOrCut() throws Exception {
		plugin.createKeyAgreementListener(commitment);
		BluetoothGattServerCallback serverCb = serverCallback();
		BluetoothDevice remote = device("CC:00:00:00:00:05");
		BluetoothGatt gatt = mock(BluetoothGatt.class);
		when(remote.connectGatt(any(Context.class), anyBoolean(),
				any(BluetoothGattCallback.class), anyInt())).thenReturn(gatt);
		when(gatt.getDevice()).thenReturn(remote);
		exec.submit(() -> plugin.createKeyAgreementConnection(
				new byte[] {9, 9, 9}, new BdfList()));
		connect(remote);
		connected(serverCb, remote);
		for (int i = 0; i < 6; i++) {
			serverCb.onCharacteristicWriteRequest(remote, i,
					proofCharacteristic(), false, true, 0, wrongProof());
		}
		verify(advertiser, after(5 * PROOF_TIMEOUT_MS).never())
				.stopAdvertisingSet(any());
		verify(server, never()).cancelConnection(any());
	}

	@Test
	public void fiveWrongProofsPauseAdvertisingThenItResumesWithAFreshCount()
			throws Exception {
		plugin.createKeyAgreementListener(commitment);
		BluetoothGattServerCallback cb = serverCallback();
		for (int i = 0; i < 5; i++) {
			cb.onCharacteristicWriteRequest(device("EE:00:00:00:00:1" + i), i,
					proofCharacteristic(), false, true, 0, wrongProof());
		}
		verify(advertiser, timeout(2_000)).stopAdvertisingSet(any());
		verify(advertiser, timeout(5_000).times(2)).startAdvertisingSet(
				any(AdvertisingSetParameters.class), any(AdvertiseData.class),
				isNull(), isNull(), isNull(),
				any(AdvertisingSetCallback.class));
		for (int i = 0; i < 4; i++) {
			cb.onCharacteristicWriteRequest(device("EF:00:00:00:00:1" + i), i,
					proofCharacteristic(), false, true, 0, wrongProof());
		}
		verify(advertiser, after(3 * BACK_OFF_MS).times(1))
				.stopAdvertisingSet(any());
		cb.onCharacteristicWriteRequest(device("EF:00:00:00:00:20"), 9,
				proofCharacteristic(), false, true, 0, wrongProof());
		verify(advertiser, timeout(2_000).times(2)).stopAdvertisingSet(any());
	}

	@Test
	public void theNameIsGivenBackWhenThePairedPhoneDisconnects()
			throws Exception {
		plugin.createKeyAgreementListener(commitment);
		BluetoothGattServerCallback cb = serverCallback();
		BluetoothDevice friend = device("BB:00:00:00:00:02");
		connected(cb, friend);
		proveAndSubscribe(cb, friend);
		cb.onConnectionStateChange(friend, BluetoothGatt.GATT_SUCCESS,
				BluetoothProfile.STATE_DISCONNECTED);
		verify(guard, timeout(2_000))
				.release(BluetoothNameGuard.HOLDER_PAIRING);
		verify(server).close();
	}

	@Test
	public void aListenerWhoseAdvertisingFailsClosesAtOnce() throws Exception {
		KeyAgreementListener listener =
				plugin.createKeyAgreementListener(commitment);
		ArgumentCaptor<AdvertisingSetCallback> adv =
				ArgumentCaptor.forClass(AdvertisingSetCallback.class);
		verify(advertiser).startAdvertisingSet(any(), any(), isNull(),
				isNull(), isNull(), adv.capture());
		adv.getValue().onAdvertisingSetStarted(null, 0,
				AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR);
		verify(server).close();
		verify(guard).release(BluetoothNameGuard.HOLDER_PAIRING);
		Future<KeyAgreementConnection> accepted = exec.submit(listener::accept);
		try {
			accepted.get(3, TimeUnit.SECONDS);
			fail();
		} catch (ExecutionException e) {
			assertTrue(e.getCause() instanceof IOException);
		}
	}

	@Test
	public void theConnectorProvesTheCodeBeforeAskingForData()
			throws Exception {
		BluetoothGatt gatt = mock(BluetoothGatt.class);
		BluetoothDevice remote = device("CC:00:00:00:00:03");
		BluetoothGattService service = service(true);
		stubGatt(remote, gatt, service);
		Future<DuplexTransportConnection> result = exec.submit(() ->
				plugin.createKeyAgreementConnection(commitment, new BdfList()));
		BluetoothGattCallback cb = connect(remote);
		cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS,
				BluetoothProfile.STATE_CONNECTED);
		cb.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS);
		cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS);
		BluetoothGattCharacteristic proof = service.getCharacteristic(
				BluetoothKeyAgreementConstants.PROOF_CHARACTERISTIC);
		verify(gatt).writeCharacteristic(proof);
		assertArrayEquals(expectedProof(), proof.getValue());
		verify(gatt, never()).writeDescriptor(any());
		cb.onCharacteristicWrite(gatt, proof, BluetoothGatt.GATT_SUCCESS);
		BluetoothGattDescriptor cccd = service.getCharacteristic(
				BluetoothKeyAgreementConstants.FRAME_CHARACTERISTIC)
				.getDescriptor(BluetoothKeyAgreementConstants.CCCD);
		verify(gatt).writeDescriptor(cccd);
		cb.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS);
		assertNotNull(result.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void anOlderPhoneWithoutTheProofStepStillPairs() throws Exception {
		BluetoothGatt gatt = mock(BluetoothGatt.class);
		BluetoothDevice remote = device("CC:00:00:00:00:04");
		BluetoothGattService service = service(false);
		stubGatt(remote, gatt, service);
		Future<DuplexTransportConnection> result = exec.submit(() ->
				plugin.createKeyAgreementConnection(commitment, new BdfList()));
		BluetoothGattCallback cb = connect(remote);
		cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS,
				BluetoothProfile.STATE_CONNECTED);
		cb.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS);
		cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS);
		BluetoothGattDescriptor cccd = service.getCharacteristic(
				BluetoothKeyAgreementConstants.FRAME_CHARACTERISTIC)
				.getDescriptor(BluetoothKeyAgreementConstants.CCCD);
		verify(gatt).writeDescriptor(cccd);
		verify(gatt, never()).writeCharacteristic(any());
		cb.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS);
		assertNotNull(result.get(5, TimeUnit.SECONDS));
	}

	@Test
	public void theConnectorGivesTheNameBackWhenTheLinkDrops()
			throws Exception {
		BluetoothGatt gatt = mock(BluetoothGatt.class);
		BluetoothDevice remote = device("CC:00:00:00:00:06");
		BluetoothGattService service = service(false);
		stubGatt(remote, gatt, service);
		Future<DuplexTransportConnection> result = exec.submit(() ->
				plugin.createKeyAgreementConnection(commitment, new BdfList()));
		BluetoothGattCallback cb = connect(remote);
		cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS,
				BluetoothProfile.STATE_CONNECTED);
		cb.onMtuChanged(gatt, 247, BluetoothGatt.GATT_SUCCESS);
		cb.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS);
		BluetoothGattDescriptor cccd = service.getCharacteristic(
				BluetoothKeyAgreementConstants.FRAME_CHARACTERISTIC)
				.getDescriptor(BluetoothKeyAgreementConstants.CCCD);
		cb.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS);
		assertNotNull(result.get(5, TimeUnit.SECONDS));
		cb.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS,
				BluetoothProfile.STATE_DISCONNECTED);
		verify(guard, timeout(2_000))
				.release(BluetoothNameGuard.HOLDER_PAIRING);
		verify(gatt).close();
	}

	private void stubGatt(BluetoothDevice remote, BluetoothGatt gatt,
			BluetoothGattService service) {
		when(remote.connectGatt(any(Context.class), anyBoolean(),
				any(BluetoothGattCallback.class), anyInt())).thenReturn(gatt);
		when(gatt.getDevice()).thenReturn(remote);
		when(gatt.requestMtu(anyInt())).thenReturn(true);
		when(gatt.discoverServices()).thenReturn(true);
		when(gatt.writeCharacteristic(any())).thenReturn(true);
		when(gatt.writeDescriptor(any())).thenReturn(true);
		when(gatt.getService(any(UUID.class))).thenReturn(service);
	}

	private void connected(BluetoothGattServerCallback cb, BluetoothDevice d) {
		cb.onConnectionStateChange(d, BluetoothGatt.GATT_SUCCESS,
				BluetoothProfile.STATE_CONNECTED);
	}

	private void proveAndSubscribe(BluetoothGattServerCallback cb,
			BluetoothDevice friend) {
		cb.onCharacteristicWriteRequest(friend, 101, proofCharacteristic(),
				false, true, 0, expectedProof());
		verify(server).sendResponse(friend, 101, BluetoothGatt.GATT_SUCCESS, 0,
				null);
		cb.onDescriptorWriteRequest(friend, 102, cccd(), false, true, 0,
				BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		verify(server).sendResponse(friend, 102, BluetoothGatt.GATT_SUCCESS, 0,
				null);
	}

	private byte[] wrongProof() {
		byte[] wrong = expectedProof();
		wrong[0] ^= 1;
		return wrong;
	}

	private byte[] expectedProof() {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(commitment);
			return Arrays.copyOf(md.digest("ZPAIRPROOF".getBytes("US-ASCII")),
					16);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private BluetoothGattCallback connect(BluetoothDevice remote) {
		ArgumentCaptor<ScanCallback> scan =
				ArgumentCaptor.forClass(ScanCallback.class);
		verify(scanner, timeout(5_000)).startScan(
				org.mockito.ArgumentMatchers.<List<ScanFilter>>any(),
				any(ScanSettings.class), scan.capture());
		scan.getValue().onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES,
				new ScanResult(remote, null, -40, 0L));
		ArgumentCaptor<BluetoothGattCallback> cb =
				ArgumentCaptor.forClass(BluetoothGattCallback.class);
		verify(remote).connectGatt(any(Context.class), anyBoolean(),
				cb.capture(), anyInt());
		return cb.getValue();
	}

	private static BluetoothGattService service(boolean withProof) {
		BluetoothGattService service = new BluetoothGattService(
				UUID.randomUUID(), BluetoothGattService.SERVICE_TYPE_PRIMARY);
		BluetoothGattCharacteristic frame = new BluetoothGattCharacteristic(
				BluetoothKeyAgreementConstants.FRAME_CHARACTERISTIC,
				BluetoothGattCharacteristic.PROPERTY_WRITE
						| BluetoothGattCharacteristic.PROPERTY_NOTIFY,
				BluetoothGattCharacteristic.PERMISSION_WRITE);
		frame.addDescriptor(new BluetoothGattDescriptor(
				BluetoothKeyAgreementConstants.CCCD,
				BluetoothGattDescriptor.PERMISSION_WRITE));
		service.addCharacteristic(frame);
		if (withProof) service.addCharacteristic(proofCharacteristic());
		return service;
	}

	private static BluetoothGattCharacteristic proofCharacteristic() {
		return new BluetoothGattCharacteristic(
				BluetoothKeyAgreementConstants.PROOF_CHARACTERISTIC,
				BluetoothGattCharacteristic.PROPERTY_WRITE,
				BluetoothGattCharacteristic.PERMISSION_WRITE);
	}

	private static BluetoothGattDescriptor cccd() {
		return new BluetoothGattDescriptor(BluetoothKeyAgreementConstants.CCCD,
				BluetoothGattDescriptor.PERMISSION_WRITE);
	}

	private BluetoothGattServerCallback serverCallback() {
		ArgumentCaptor<BluetoothGattServerCallback> cb =
				ArgumentCaptor.forClass(BluetoothGattServerCallback.class);
		verify(manager).openGattServer(any(Context.class), cb.capture());
		return cb.getValue();
	}

	private static BluetoothDevice device(String address) {
		BluetoothDevice d = mock(BluetoothDevice.class);
		when(d.getAddress()).thenReturn(address);
		return d;
	}
}
