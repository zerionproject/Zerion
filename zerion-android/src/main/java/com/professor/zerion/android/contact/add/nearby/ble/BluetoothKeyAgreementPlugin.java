package com.professor.zerion.android.contact.add.nearby.ble;

import android.annotation.SuppressLint;
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
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;
import org.zerionproject.core.api.keyagreement.KeyAgreementListener;
import org.zerionproject.core.api.plugin.ConnectionHandler;
import org.zerionproject.core.api.plugin.PluginCallback;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static com.professor.zerion.android.contact.add.nearby.ble.BluetoothKeyAgreementConstants.CCCD;
import static com.professor.zerion.android.contact.add.nearby.ble.BluetoothKeyAgreementConstants.FRAME_CHARACTERISTIC;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.TRANSPORT_ID_BLUETOOTH;
import static org.zerionproject.core.api.plugin.Plugin.State.ACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.INACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;

/**
 * A key-agreement-only transport over a point-to-point BLE GATT link, so two
 * nearby devices can pair offline with no Wi-Fi and no network. It carries the
 * MITM-protected QR key agreement; the device showing the QR runs a GATT server
 * and advertises a service id derived from its commitment, and the device
 * scanning the QR derives the same id, finds it, and connects. Roles are fixed
 * by who scans, so there is no connection glare. Each endpoint has its own
 * per-chunk completion latch; writes are accepted only from the bound peer.
 */
@SuppressLint("MissingPermission")
@ThreadSafe
@NotNullByDefault
public class BluetoothKeyAgreementPlugin implements DuplexPlugin {

	private static final int TARGET_MTU = 512;
	private static final int MAX_ATTR_LEN = 509;
	private static final int DEFAULT_CHUNK = 20;
	private static final long OP_TIMEOUT_MS = 8000;
	private static final long ACCEPT_TIMEOUT_MS = 30_000;
	private static final long CONNECT_TIMEOUT_MS = 15_000;
	private static final int SEND_RETRIES = 3;

	private final Context appContext;
	private final PluginCallback callback;
	@Nullable
	private final BluetoothManager bluetoothManager;
	private final Set<Runnable> closers =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

	private volatile State state = STARTING_STOPPING;

	BluetoothKeyAgreementPlugin(Context context, PluginCallback callback) {
		this.appContext = context.getApplicationContext();
		this.callback = callback;
		this.bluetoothManager = (BluetoothManager)
				appContext.getSystemService(Context.BLUETOOTH_SERVICE);
	}

	@Override
	public TransportId getId() {
		return BluetoothKeyAgreementConstants.ID;
	}

	@Override
	public long getMaxLatency() {
		return BluetoothKeyAgreementConstants.MAX_LATENCY;
	}

	@Override
	public int getMaxIdleTime() {
		return BluetoothKeyAgreementConstants.MAX_IDLE_TIME;
	}

	@Override
	public void start() {
		setState(ACTIVE);
	}

	@Override
	public void stop() {
		setState(INACTIVE);
		for (Runnable r : closers) {
			try {
				r.run();
			} catch (Exception e) {
			}
		}
		closers.clear();
	}

	@Override
	public State getState() {
		return state;
	}

	@Override
	public int getReasonsDisabled() {
		return 0;
	}

	@Override
	public boolean shouldPoll() {
		return false;
	}

	@Override
	public int getPollingInterval() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void poll(Collection<Pair<TransportProperties, ConnectionHandler>> p) {
	}

	@Override
	@Nullable
	public DuplexTransportConnection createConnection(TransportProperties p) {
		return null;
	}

	@Override
	public boolean supportsKeyAgreement() {
		BluetoothAdapter adapter = getAdapter();
		return adapter != null && adapter.isEnabled()
				&& adapter.getBluetoothLeAdvertiser() != null;
	}

	@Override
	@Nullable
	public KeyAgreementListener createKeyAgreementListener(
			byte[] localCommitment) {
		BluetoothAdapter adapter = getAdapter();
		if (adapter == null || !adapter.isEnabled()) return null;
		UUID serviceUuid = pairingUuid(localCommitment);
		if (serviceUuid == null) return null;
		Acceptor acceptor = new Acceptor(adapter, serviceUuid);
		if (!acceptor.startServerAndAdvertise()) {
			acceptor.close();
			return null;
		}
		BdfList descriptor = new BdfList();
		descriptor.add(TRANSPORT_ID_BLUETOOTH);
		return acceptor.new Listener(descriptor);
	}

	@Override
	@Nullable
	public DuplexTransportConnection createKeyAgreementConnection(
			byte[] remoteCommitment, BdfList descriptor) {
		BluetoothAdapter adapter = getAdapter();
		if (adapter == null || !adapter.isEnabled()) return null;
		UUID serviceUuid = pairingUuid(remoteCommitment);
		if (serviceUuid == null) return null;
		Connector connector = new Connector(adapter, serviceUuid);
		DuplexTransportConnection conn = connector.scanConnectAndOpen();
		if (conn == null) connector.close();
		return conn;
	}

	@Override
	public boolean supportsRendezvous() {
		return false;
	}

	@Override
	public RendezvousEndpoint createRendezvousEndpoint(KeyMaterialSource k,
			boolean alice, ConnectionHandler incoming) {
		throw new UnsupportedOperationException();
	}

	@Nullable
	private BluetoothAdapter getAdapter() {
		return bluetoothManager == null ? null : bluetoothManager.getAdapter();
	}

	private void setState(State s) {
		state = s;
		callback.pluginStateChanged(s);
	}

	/** Derives the discovery service UUID from a 16-byte commitment so both sides
	 * compute the same value without exchanging an address. Null on failure so the
	 * caller does not fall back to a shared, guessable UUID. */
	@Nullable
	private static UUID pairingUuid(byte[] commitment) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(commitment);
			byte[] h = md.digest("ZPAIR".getBytes());
			long msb = 0, lsb = 0;
			for (int i = 0; i < 8; i++) msb = (msb << 8) | (h[i] & 0xFF);
			for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (h[i] & 0xFF);
			return new UUID(msb, lsb);
		} catch (Exception e) {
			return null;
		}
	}

	private static int chunkSize(int mtu) {
		int usable = Math.min(mtu - 3, MAX_ATTR_LEN);
		return Math.max(DEFAULT_CHUNK, usable);
	}

	/** Awaits a single op's completion latch, ignoring late releases of an
	 * already-completed op because the field is cleared before the next send. */
	private static boolean awaitLatch(@Nullable CountDownLatch latch) {
		if (latch == null) return false;
		try {
			return latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}


	private class Acceptor {

		private final BluetoothAdapter adapter;
		private final UUID serviceUuid;
		private final Runnable self = this::close;
		private final LinkedBlockingQueue<BleGattStream> ready =
				new LinkedBlockingQueue<>(1);
		private volatile int mtu = 23;
		private volatile boolean streamOpened = false;

		@Nullable
		private volatile BluetoothGattServer server;
		@Nullable
		private volatile BluetoothGattCharacteristic characteristic;
		@Nullable
		private volatile BluetoothLeAdvertiser advertiser;
		@Nullable
		private volatile BluetoothDevice central;
		@Nullable
		private volatile BleGattStream stream;
		@Nullable
		private volatile CountDownLatch pending;

		Acceptor(BluetoothAdapter adapter, UUID serviceUuid) {
			this.adapter = adapter;
			this.serviceUuid = serviceUuid;
		}

		boolean startServerAndAdvertise() {
			try {
				BluetoothGattServer s = bluetoothManager.openGattServer(
						appContext, serverCallback);
				if (s == null) return false;
				BluetoothGattCharacteristic ch =
						new BluetoothGattCharacteristic(FRAME_CHARACTERISTIC,
								BluetoothGattCharacteristic.PROPERTY_WRITE
										| BluetoothGattCharacteristic
										.PROPERTY_WRITE_NO_RESPONSE
										| BluetoothGattCharacteristic
										.PROPERTY_NOTIFY,
								BluetoothGattCharacteristic.PERMISSION_WRITE);
				ch.addDescriptor(new BluetoothGattDescriptor(CCCD,
						BluetoothGattDescriptor.PERMISSION_READ
								| BluetoothGattDescriptor.PERMISSION_WRITE));
				BluetoothGattService service = new BluetoothGattService(
						serviceUuid,
						BluetoothGattService.SERVICE_TYPE_PRIMARY);
				service.addCharacteristic(ch);
				s.addService(service);
				server = s;
				characteristic = ch;
				BluetoothLeAdvertiser a = adapter.getBluetoothLeAdvertiser();
				if (a == null) return false;
				advertiser = a;
				AdvertiseSettings settings = new AdvertiseSettings.Builder()
						.setAdvertiseMode(
								AdvertiseSettings.ADVERTISE_MODE_BALANCED)
						.setConnectable(true)
						.setTxPowerLevel(
								AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
						.build();
				AdvertiseData data = new AdvertiseData.Builder()
						.setIncludeDeviceName(false)
						.addServiceUuid(new ParcelUuid(serviceUuid))
						.build();
				a.startAdvertising(settings, data, advertiseCallback);
				closers.add(self);
				return true;
			} catch (Exception e) {
				return false;
			}
		}

		private final AdvertiseCallback advertiseCallback =
				new AdvertiseCallback() {
					@Override
					public void onStartFailure(int errorCode) {
					}
				};

		private final BluetoothGattServerCallback serverCallback =
				new BluetoothGattServerCallback() {
					@Override
					public void onConnectionStateChange(BluetoothDevice device,
							int status, int newState) {
						if (newState == BluetoothProfile.STATE_CONNECTED) {
							if (central == null) {
								central = device;
							}
						} else if (newState
								== BluetoothProfile.STATE_DISCONNECTED
								&& device.equals(central)) {
							BleGattStream st = stream;
							if (st != null) st.setEof();
						}
					}

					@Override
					public void onMtuChanged(BluetoothDevice device, int m) {
						if (device.equals(central)) mtu = m;
					}

					@Override
					public void onNotificationSent(BluetoothDevice device,
							int status) {
						CountDownLatch l = pending;
						if (l != null) l.countDown();
					}

					@Override
					public void onDescriptorWriteRequest(BluetoothDevice device,
							int requestId, BluetoothGattDescriptor descriptor,
							boolean prep, boolean rsp, int offset,
							byte[] value) {
						BluetoothGattServer s = server;
						if (rsp && s != null) {
							s.sendResponse(device, requestId,
									BluetoothGatt.GATT_SUCCESS, offset, null);
						}
						if (device.equals(central)) openStreamOnce();
					}

					@Override
					public void onCharacteristicWriteRequest(
							BluetoothDevice device, int requestId,
							BluetoothGattCharacteristic ch, boolean prep,
							boolean rsp, int offset, byte[] value) {
						BluetoothGattServer s = server;
						if (rsp && s != null) {
							s.sendResponse(device, requestId,
									BluetoothGatt.GATT_SUCCESS, offset, null);
						}
						if (!device.equals(central)) return;
						if (FRAME_CHARACTERISTIC.equals(ch.getUuid())) {
							openStreamOnce();
							BleGattStream st = stream;
							if (value != null && st != null) st.onReceive(value);
						}
					}
				};

		private synchronized void openStreamOnce() {
			if (streamOpened) return;
			BluetoothDevice c = central;
			if (c == null) return;
			streamOpened = true;
			int chunk = chunkSize(mtu);
			BleGattStream st = new BleGattStream(chunk, chunkOut -> {
				BluetoothGattServer s = server;
				BluetoothGattCharacteristic ch = characteristic;
				if (s == null || ch == null) return false;
				ch.setValue(chunkOut);
				for (int attempt = 0; attempt < SEND_RETRIES; attempt++) {
					CountDownLatch latch = new CountDownLatch(1);
					pending = latch;
					boolean queued = s.notifyCharacteristicChanged(c, ch, false);
					if (!queued) {
						pending = null;
						sleep();
						continue;
					}
					boolean ok = awaitLatch(latch);
					pending = null;
					return ok;
				}
				return false;
			}, this::close);
			stream = st;
			ready.offer(st);
		}

		void stopAdvertising() {
			try {
				BluetoothLeAdvertiser a = advertiser;
				if (a != null) a.stopAdvertising(advertiseCallback);
			} catch (Exception e) {
			}
		}

		void close() {
			try {
				stopAdvertising();
				BleGattStream st = stream;
				if (st != null) st.setEof();
				BluetoothGattServer s = server;
				if (s != null) s.close();
			} catch (Exception e) {
			} finally {
				closers.remove(self);
			}
		}

		class Listener extends KeyAgreementListener {

			Listener(BdfList descriptor) {
				super(descriptor);
			}

			@Override
			public KeyAgreementConnection accept() throws java.io.IOException {
				try {
					BleGattStream st = ready.poll(ACCEPT_TIMEOUT_MS,
							TimeUnit.MILLISECONDS);
					if (st == null) throw new java.io.IOException("no peer");
					return new KeyAgreementConnection(
							new BluetoothKeyAgreementConnection(
									BluetoothKeyAgreementPlugin.this, st),
							BluetoothKeyAgreementConstants.ID);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new java.io.IOException("interrupted");
				}
			}

			@Override
			public void close() {
				stopAdvertising();
				if (stream == null) Acceptor.this.close();
			}
		}
	}


	private class Connector {

		private final BluetoothAdapter adapter;
		private final UUID serviceUuid;
		private final Runnable self = this::close;
		private final CountDownLatch ready = new CountDownLatch(1);
		private final AtomicBoolean connecting = new AtomicBoolean(false);
		private volatile int mtu = 23;

		@Nullable
		private volatile BluetoothLeScanner scanner;
		@Nullable
		private volatile BluetoothGatt gatt;
		@Nullable
		private volatile BleGattStream stream;
		@Nullable
		private volatile CountDownLatch pending;

		Connector(BluetoothAdapter adapter, UUID serviceUuid) {
			this.adapter = adapter;
			this.serviceUuid = serviceUuid;
		}

		@Nullable
		DuplexTransportConnection scanConnectAndOpen() {
			BluetoothLeScanner s = adapter.getBluetoothLeScanner();
			if (s == null) return null;
			scanner = s;
			closers.add(self);
			ScanFilter filter = new ScanFilter.Builder()
					.setServiceUuid(new ParcelUuid(serviceUuid))
					.build();
			ScanSettings settings = new ScanSettings.Builder()
					.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
					.build();
			s.startScan(Collections.singletonList(filter), settings,
					scanCallback);
			try {
				if (!ready.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					return null;
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
			BleGattStream st = stream;
			if (st == null) return null;
			return new BluetoothKeyAgreementConnection(
					BluetoothKeyAgreementPlugin.this, st);
		}

		private final ScanCallback scanCallback = new ScanCallback() {
			@Override
			public void onScanResult(int type, ScanResult result) {
				if (!connecting.compareAndSet(false, true)) return;
				BluetoothLeScanner s = scanner;
				if (s != null) s.stopScan(this);
				gatt = result.getDevice().connectGatt(appContext, false,
						clientCallback, BluetoothDevice.TRANSPORT_LE);
			}

			@Override
			public void onScanFailed(int errorCode) {
				if (!connecting.get()) ready.countDown();
			}
		};

		private final BluetoothGattCallback clientCallback =
				new BluetoothGattCallback() {
					@Override
					public void onConnectionStateChange(BluetoothGatt g,
							int status, int newState) {
						if (status == BluetoothGatt.GATT_SUCCESS
								&& newState == BluetoothProfile.STATE_CONNECTED) {
							if (!g.requestMtu(TARGET_MTU)
									&& !g.discoverServices()) {
								ready.countDown();
							}
						} else if (newState
								!= BluetoothProfile.STATE_CONNECTING) {
							BleGattStream st = stream;
							if (st != null) st.setEof();
							ready.countDown();
						}
					}

					@Override
					public void onMtuChanged(BluetoothGatt g, int m,
							int status) {
						mtu = m;
						if (!g.discoverServices()) ready.countDown();
					}

					@Override
					public void onServicesDiscovered(BluetoothGatt g,
							int status) {
						BluetoothGattService service = g.getService(serviceUuid);
						if (service == null) {
							ready.countDown();
							return;
						}
						BluetoothGattCharacteristic ch =
								service.getCharacteristic(FRAME_CHARACTERISTIC);
						if (ch == null) {
							ready.countDown();
							return;
						}
						g.setCharacteristicNotification(ch, true);
						BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
						if (cccd == null) {
							ready.countDown();
							return;
						}
						cccd.setValue(BluetoothGattDescriptor
								.ENABLE_NOTIFICATION_VALUE);
						if (!g.writeDescriptor(cccd)) ready.countDown();
					}

					@Override
					public void onDescriptorWrite(BluetoothGatt g,
							BluetoothGattDescriptor descriptor, int status) {
						if (status == BluetoothGatt.GATT_SUCCESS) openStream(g);
						ready.countDown();
					}

					@Override
					public void onCharacteristicWrite(BluetoothGatt g,
							BluetoothGattCharacteristic ch, int status) {
						CountDownLatch l = pending;
						if (l != null) l.countDown();
					}

					@Override
					public void onCharacteristicChanged(BluetoothGatt g,
							BluetoothGattCharacteristic ch) {
						if (FRAME_CHARACTERISTIC.equals(ch.getUuid())) {
							byte[] v = ch.getValue();
							BleGattStream st = stream;
							if (v != null && st != null) st.onReceive(v);
						}
					}
				};

		private synchronized void openStream(BluetoothGatt g) {
			if (stream != null) return;
			int chunk = chunkSize(mtu);
			BleGattStream st = new BleGattStream(chunk, chunkOut -> {
				BluetoothGatt gt = gatt;
				if (gt == null) return false;
				BluetoothGattService svc = gt.getService(serviceUuid);
				if (svc == null) return false;
				BluetoothGattCharacteristic c =
						svc.getCharacteristic(FRAME_CHARACTERISTIC);
				if (c == null) return false;
				c.setValue(chunkOut);
				c.setWriteType(BluetoothGattCharacteristic
						.WRITE_TYPE_NO_RESPONSE);
				for (int attempt = 0; attempt < SEND_RETRIES; attempt++) {
					CountDownLatch latch = new CountDownLatch(1);
					pending = latch;
					boolean queued = gt.writeCharacteristic(c);
					if (!queued) {
						pending = null;
						sleep();
						continue;
					}
					boolean ok = awaitLatch(latch);
					pending = null;
					return ok;
				}
				return false;
			}, this::close);
			stream = st;
		}

		void close() {
			try {
				BluetoothLeScanner s = scanner;
				if (s != null) s.stopScan(scanCallback);
				BleGattStream st = stream;
				if (st != null) st.setEof();
				BluetoothGatt g = gatt;
				if (g != null) {
					g.disconnect();
					g.close();
				}
			} catch (Exception e) {
			} finally {
				closers.remove(self);
			}
		}
	}

	private static void sleep() {
		try {
			Thread.sleep(20);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
