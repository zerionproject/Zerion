package com.professor.zerion.android.mesh;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.ParcelUuid;

import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshLink;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

@SuppressLint("MissingPermission")
@NotNullByDefault
public class BleMeshTransport implements MeshLink {

	public static final String LINK_ID = "ble";

	private static final UUID GATT_SERVICE_UUID =
			UUID.fromString("9f2a7c14-6b38-4d0e-8a51-c3e7d924b6f8");
	private static final UUID FRAME_UUID =
			UUID.fromString("9f2a7c14-6b38-4d0e-8a51-c3e7d924b6f9");


	private static final int TARGET_MTU = 512;
	private static final int DEFAULT_CHUNK = 20;
	private static final int LENGTH_PREFIX = 4;
	private static final int MAX_FRAME_BYTES = 128 * 1024;
	private static final long OP_TIMEOUT_MS = 2000;
	private static final int MAX_ATTR_LEN = 509;

	private static final int MANUFACTURER_ID = 0xFFFF;
	private static final int NONCE_BYTES = 8;
	private static final long CONNECT_COOLDOWN_MS = 4000;
	private volatile byte[] sessionNonce = new byte[NONCE_BYTES];
	@Nullable
	private volatile java.util.concurrent.ScheduledExecutorService nonceRotator;

	private final Context appContext;
	private final MeshForwarder forwarder;
	private final BluetoothManager bluetoothManager;
	@Nullable
	private final Runnable onRadioUp;
	@Nullable
	private final Runnable onPeerConnected;

	private final ExecutorService sendExecutor =
			Executors.newSingleThreadExecutor();
	private final ExecutorService receiveExecutor =
			Executors.newSingleThreadExecutor();
	private final Semaphore opComplete = new Semaphore(0);

	@Nullable
	private volatile BluetoothLeAdvertiser advertiser;
	@Nullable
	private volatile BluetoothLeScanner scanner;
	@Nullable
	private volatile BluetoothGattServer gattServer;
	@Nullable
	private volatile BluetoothGattCharacteristic frameCharacteristic;

	private final Object radioLock = new Object();
	private volatile boolean running;
	private boolean radioUp;
	private boolean receiverRegistered;

	private final Map<String, BluetoothDevice> connectedCentrals =
			new ConcurrentHashMap<>();
	private final Map<String, BluetoothGatt> connectedClients =
			new ConcurrentHashMap<>();
	private final Map<String, Integer> mtuByDevice =
			new ConcurrentHashMap<>();
	private final Map<String, MeshFrameReassembler> reassemblers =
			new ConcurrentHashMap<>();
	private final Map<String, Long> lastConnectAttempt =
			new ConcurrentHashMap<>();

	public BleMeshTransport(Context context, MeshForwarder forwarder,
			@Nullable Runnable onRadioUp, @Nullable Runnable onPeerConnected) {
		this.appContext = context.getApplicationContext();
		this.forwarder = forwarder;
		this.onRadioUp = onRadioUp;
		this.onPeerConnected = onPeerConnected;
		this.bluetoothManager = (BluetoothManager)
				appContext.getSystemService(Context.BLUETOOTH_SERVICE);
		new java.security.SecureRandom().nextBytes(sessionNonce);
	}

	@Override
	public String getId() {
		return LINK_ID;
	}

	public int getPeerCount() {
		java.util.Set<String> addrs =
				new java.util.HashSet<>(connectedCentrals.keySet());
		addrs.addAll(connectedClients.keySet());
		return addrs.size();
	}

	public void start() {
		running = true;
		registerStateReceiver();
		forwarder.addLink(this);
		java.util.concurrent.ScheduledExecutorService rot =
				Executors.newSingleThreadScheduledExecutor(r -> {
					Thread t = new Thread(r, "BleNonceRotate");
					t.setDaemon(true);
					return t;
				});
		rot.scheduleWithFixedDelay(this::rotate, MeshDiscovery.EPOCH_MS, MeshDiscovery.EPOCH_MS,
				TimeUnit.MILLISECONDS);
		nonceRotator = rot;
		BluetoothAdapter adapter = bluetoothManager.getAdapter();
		if (adapter != null && adapter.isEnabled()) {
			startRadio(adapter);
		}
	}

	private void startRadio(BluetoothAdapter adapter) {
		synchronized (radioLock) {
			if (radioUp || !running) return;
			radioUp = true;
			try {
				openServer();
			} catch (Exception e) {
			}
			try {
				startAdvertising(adapter);
			} catch (Exception e) {
			}
			try {
				startScanning(adapter);
			} catch (Exception e) {
			}
		}
		Runnable hook = onRadioUp;
		if (hook != null) {
			Thread t = new Thread(hook, "BleRadioUp");
			t.setDaemon(true);
			t.start();
		}
	}

	private void stopRadio() {
		synchronized (radioLock) {
			if (!radioUp) return;
			radioUp = false;
			BluetoothLeAdvertiser a = advertiser;
			if (a != null) {
				try {
					a.stopAdvertising(advertiseCallback);
				} catch (Exception e) {
				}
				advertiser = null;
			}
			BluetoothLeScanner s = scanner;
			if (s != null) {
				try {
					s.stopScan(scanCallback);
				} catch (Exception e) {
				}
				scanner = null;
			}
			for (BluetoothGatt g : connectedClients.values()) closeQuietly(g);
			connectedClients.clear();
			BluetoothGattServer server = gattServer;
			if (server != null) {
				try {
					server.close();
				} catch (Exception e) {
				}
				gattServer = null;
			}
			frameCharacteristic = null;
			connectedCentrals.clear();
			reassemblers.clear();
			mtuByDevice.clear();
			lastConnectAttempt.clear();
		}
	}

	private void firePeerConnected() {
		Runnable hook = onPeerConnected;
		if (hook != null) {
			try {
				hook.run();
			} catch (Exception e) {
			}
		}
	}

	private void registerStateReceiver() {
		synchronized (radioLock) {
			if (receiverRegistered) return;
			receiverRegistered = true;
		}
		appContext.registerReceiver(stateReceiver,
				new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
	}

	private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(
					intent.getAction())) {
				return;
			}
			int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
					BluetoothAdapter.ERROR);
			if (state == BluetoothAdapter.STATE_ON) {
				if (!running) return;
				BluetoothAdapter adapter = bluetoothManager.getAdapter();
				if (adapter != null && adapter.isEnabled()) {
					startRadio(adapter);
				}
			} else if (state == BluetoothAdapter.STATE_TURNING_OFF
					|| state == BluetoothAdapter.STATE_OFF) {
				stopRadio();
			}
		}
	};

	private void rotate() {
		try {
			byte[] fresh = new byte[NONCE_BYTES];
			new java.security.SecureRandom().nextBytes(fresh);
			synchronized (radioLock) {
				if (!radioUp || !running) return;
				sessionNonce = fresh;
				BluetoothAdapter adapter = bluetoothManager.getAdapter();
				if (adapter == null || !adapter.isEnabled()) return;
				BluetoothLeAdvertiser a = advertiser;
				if (a != null) {
					a.stopAdvertising(advertiseCallback);
					startAdvertising(adapter);
				}
				BluetoothLeScanner sc = scanner;
				if (sc != null) {
					sc.stopScan(scanCallback);
					startScanning(adapter);
				}
			}
		} catch (Exception e) {
		}
	}

	public void stop() {
		running = false;
		forwarder.removeLink(LINK_ID);
		synchronized (radioLock) {
			if (receiverRegistered) {
				receiverRegistered = false;
				try {
					appContext.unregisterReceiver(stateReceiver);
				} catch (IllegalArgumentException e) {
				}
			}
		}
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		if (rot != null) rot.shutdownNow();
		nonceRotator = null;
		stopRadio();
		sendExecutor.shutdownNow();
		receiveExecutor.shutdownNow();
	}

	@Override
	public void broadcast(byte[] frame) {
		byte[] framed = withLengthPrefix(frame);
		sendExecutor.execute(() -> doBroadcast(framed));
	}

	private void doBroadcast(byte[] framed) {
		BluetoothGattServer server = gattServer;
		BluetoothGattCharacteristic ch = frameCharacteristic;
		if (server != null && ch != null) {
			for (BluetoothDevice central : connectedCentrals.values()) {
				try {
					notifyInChunks(server, central, ch, framed);
				} catch (Exception e) {
				}
			}
		}
		for (Map.Entry<String, BluetoothGatt> e : connectedClients.entrySet()) {
			try {
				writeInChunks(e.getValue(), e.getKey(), framed);
			} catch (Exception ex) {
			}
		}
	}

	private boolean awaitOp() {
		try {
			return opComplete.tryAcquire(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private void openServer() {
		BluetoothGattServer server =
				bluetoothManager.openGattServer(appContext, serverCallback);
		if (server == null) return;
		BluetoothGattCharacteristic ch = new BluetoothGattCharacteristic(
				FRAME_UUID,
				BluetoothGattCharacteristic.PROPERTY_WRITE
						| BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
						| BluetoothGattCharacteristic.PROPERTY_NOTIFY,
				BluetoothGattCharacteristic.PERMISSION_WRITE);
		BluetoothGattService service = new BluetoothGattService(
				GATT_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
		service.addCharacteristic(ch);
		server.addService(service);
		gattServer = server;
		frameCharacteristic = ch;
	}

	private final BluetoothGattServerCallback serverCallback =
			new BluetoothGattServerCallback() {
				@Override
				public void onConnectionStateChange(BluetoothDevice device,
						int status, int newState) {
					if (newState == BluetoothProfile.STATE_CONNECTED) {
						connectedCentrals.put(device.getAddress(), device);
						firePeerConnected();
					} else if (newState
							== BluetoothProfile.STATE_DISCONNECTED) {
						connectedCentrals.remove(device.getAddress());
						reassemblers.remove(serverKey(device.getAddress()));
						mtuByDevice.remove(device.getAddress());
					}
				}

				@Override
				public void onMtuChanged(BluetoothDevice device, int mtu) {
					mtuByDevice.put(device.getAddress(), mtu);
				}

				@Override
				public void onNotificationSent(BluetoothDevice device,
						int status) {
					opComplete.release();
				}

				@Override
				public void onCharacteristicWriteRequest(
						BluetoothDevice device, int requestId,
						BluetoothGattCharacteristic characteristic,
						boolean preparedWrite, boolean responseNeeded,
						int offset, byte[] value) {
					if (FRAME_UUID.equals(characteristic.getUuid())) {
						onBytes(serverKey(device.getAddress()), value);
					}
					BluetoothGattServer server = gattServer;
					if (responseNeeded && server != null) {
						server.sendResponse(device, requestId,
								BluetoothGatt.GATT_SUCCESS, offset, null);
					}
				}
			};

	private void startScanning(BluetoothAdapter adapter) {
		BluetoothLeScanner s = adapter.getBluetoothLeScanner();
		if (s == null) return;
		scanner = s;
		long epoch = MeshDiscovery.currentEpoch();
		List<ScanFilter> filters = new java.util.ArrayList<>();
		for (long e = epoch - 3; e <= epoch + 3; e++) {
			filters.add(new ScanFilter.Builder()
					.setServiceUuid(new ParcelUuid(MeshDiscovery.discoveryUuid(e, GATT_SERVICE_UUID)))
					.build());
		}
		ScanSettings settings = new ScanSettings.Builder()
				.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
				.build();
		s.startScan(filters, settings, scanCallback);
	}

	private final ScanCallback scanCallback = new ScanCallback() {
		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			BluetoothDevice device = result.getDevice();
			String address = device.getAddress();
			if (connectedClients.containsKey(address)) return;
			byte[] peerNonce = null;
			if (result.getScanRecord() != null) {
				peerNonce = result.getScanRecord()
						.getManufacturerSpecificData(MANUFACTURER_ID);
			}
			if (peerNonce == null) return;
			if (MeshDiscovery.compareNonce(sessionNonce, peerNonce) <= 0) return;
			long now = clock();
			Long last = lastConnectAttempt.get(address);
			if (last != null && now - last < CONNECT_COOLDOWN_MS) return;
			lastConnectAttempt.put(address, now);
			BluetoothGatt gatt = device.connectGatt(appContext, false,
					clientCallback, BluetoothDevice.TRANSPORT_LE);
			if (gatt != null
					&& connectedClients.putIfAbsent(address, gatt) != null) {
				closeQuietly(gatt);
			}
		}
	};

	private static long clock() {
		return android.os.SystemClock.elapsedRealtime();
	}

	private final BluetoothGattCallback clientCallback =
			new BluetoothGattCallback() {
				@Override
				public void onConnectionStateChange(BluetoothGatt gatt,
						int status, int newState) {
					String address = gatt.getDevice().getAddress();
					if (status == BluetoothGatt.GATT_SUCCESS
							&& newState == BluetoothProfile.STATE_CONNECTED) {
						gatt.requestMtu(TARGET_MTU);
					} else if (newState != BluetoothProfile.STATE_CONNECTING) {
						connectedClients.remove(address, gatt);
						reassemblers.remove(clientKey(address));
						mtuByDevice.remove(address);
						lastConnectAttempt.remove(address);
						closeQuietly(gatt);
					}
				}

				@Override
				public void onMtuChanged(BluetoothGatt gatt, int mtu,
						int status) {
					mtuByDevice.put(gatt.getDevice().getAddress(), mtu);
					gatt.discoverServices();
				}

				@Override
				public void onCharacteristicWrite(BluetoothGatt gatt,
						BluetoothGattCharacteristic characteristic,
						int status) {
					opComplete.release();
				}

				@Override
				public void onServicesDiscovered(BluetoothGatt gatt,
						int status) {
					BluetoothGattService service =
							gatt.getService(GATT_SERVICE_UUID);
					if (service == null) return;
					BluetoothGattCharacteristic ch =
							service.getCharacteristic(FRAME_UUID);
					if (ch != null) {
						gatt.setCharacteristicNotification(ch, true);
					}
					firePeerConnected();
				}

				@Override
				public void onCharacteristicChanged(BluetoothGatt gatt,
						BluetoothGattCharacteristic characteristic) {
					if (FRAME_UUID.equals(characteristic.getUuid())) {
						onBytes(clientKey(gatt.getDevice().getAddress()),
								characteristic.getValue());
					}
				}
			};

	private void startAdvertising(BluetoothAdapter adapter) {
		BluetoothLeAdvertiser a = adapter.getBluetoothLeAdvertiser();
		if (a == null) return;
		advertiser = a;
		AdvertiseSettings settings = new AdvertiseSettings.Builder()
				.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
				.setConnectable(true)
				.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
				.build();
		AdvertiseData data = new AdvertiseData.Builder()
				.setIncludeDeviceName(false)
				.addServiceUuid(new ParcelUuid(MeshDiscovery.discoveryUuid(MeshDiscovery.currentEpoch(), GATT_SERVICE_UUID)))
				.build();
		AdvertiseData scanResponse = new AdvertiseData.Builder()
				.setIncludeDeviceName(false)
				.addManufacturerData(MANUFACTURER_ID, sessionNonce)
				.build();
		a.startAdvertising(settings, data, scanResponse, advertiseCallback);
	}

	private final AdvertiseCallback advertiseCallback =
			new AdvertiseCallback() {
			};

	private void onBytes(String key, @Nullable byte[] value) {
		if (value == null || value.length == 0) return;
		MeshFrameReassembler r = reassemblers.computeIfAbsent(key,
				k -> new MeshFrameReassembler(MAX_FRAME_BYTES));
		r.append(value);
		byte[] frame;
		while ((frame = r.poll()) != null) {
			byte[] f = frame;
			try {
				receiveExecutor.execute(() -> forwarder.onReceive(f, LINK_ID));
			} catch (java.util.concurrent.RejectedExecutionException e) {
			}
		}
	}

	private static String serverKey(String address) {
		return "s:" + address;
	}

	private static String clientKey(String address) {
		return "c:" + address;
	}

	private int chunkSize(String address) {
		Integer mtu = mtuByDevice.get(address);
		int usable = (mtu == null ? DEFAULT_CHUNK + 3 : mtu) - 3;
		usable = Math.min(usable, MAX_ATTR_LEN);
		return Math.max(DEFAULT_CHUNK, usable);
	}

	private void writeInChunks(BluetoothGatt gatt, String address,
			byte[] framed) {
		int chunk = chunkSize(address);
		BluetoothGattService service = gatt.getService(GATT_SERVICE_UUID);
		if (service == null) return;
		BluetoothGattCharacteristic ch =
				service.getCharacteristic(FRAME_UUID);
		if (ch == null) return;
		for (int off = 0; off < framed.length; off += chunk) {
			int len = Math.min(chunk, framed.length - off);
			byte[] part = new byte[len];
			System.arraycopy(framed, off, part, 0, len);
			ch.setValue(part);
			ch.setWriteType(BluetoothGattCharacteristic
					.WRITE_TYPE_NO_RESPONSE);
			opComplete.drainPermits();
			gatt.writeCharacteristic(ch);
			if (!awaitOp()) break;
		}
	}

	private void notifyInChunks(BluetoothGattServer server,
			BluetoothDevice central, BluetoothGattCharacteristic ch,
			byte[] framed) {
		int chunk = chunkSize(central.getAddress());
		for (int off = 0; off < framed.length; off += chunk) {
			int len = Math.min(chunk, framed.length - off);
			byte[] part = new byte[len];
			System.arraycopy(framed, off, part, 0, len);
			ch.setValue(part);
			opComplete.drainPermits();
			server.notifyCharacteristicChanged(central, ch, false);
			if (!awaitOp()) break;
		}
	}

	private static byte[] withLengthPrefix(byte[] frame) {
		byte[] out = new byte[LENGTH_PREFIX + frame.length];
		int len = frame.length;
		out[0] = (byte) (len >>> 24);
		out[1] = (byte) (len >>> 16);
		out[2] = (byte) (len >>> 8);
		out[3] = (byte) len;
		System.arraycopy(frame, 0, out, LENGTH_PREFIX, frame.length);
		return out;
	}

	private static void closeQuietly(BluetoothGatt gatt) {
		try {
			gatt.disconnect();
			gatt.close();
		} catch (RuntimeException ignored) {
		}
	}
}
