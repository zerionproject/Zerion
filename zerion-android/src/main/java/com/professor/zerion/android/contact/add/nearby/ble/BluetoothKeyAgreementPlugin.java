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
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSet;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;

import com.professor.zerion.android.mesh.BleAdvertising;
import com.professor.zerion.android.mesh.BluetoothNameGuard;

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
import java.security.SecureRandom;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static com.professor.zerion.android.contact.add.nearby.ble.BluetoothKeyAgreementConstants.CCCD;
import static com.professor.zerion.android.contact.add.nearby.ble.BluetoothKeyAgreementConstants.FRAME_CHARACTERISTIC;
import static com.professor.zerion.android.contact.add.nearby.ble.BluetoothKeyAgreementConstants.PROOF_CHARACTERISTIC;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.TRANSPORT_ID_BLUETOOTH;
import static org.zerionproject.core.api.plugin.Plugin.State.ACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.INACTIVE;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;

@SuppressLint("MissingPermission")
@ThreadSafe
@NotNullByDefault
public class BluetoothKeyAgreementPlugin implements DuplexPlugin {

	private static final int TARGET_MTU = 512;
	private static final int MAX_ATTR_LEN = 509;
	private static final int DEFAULT_CHUNK = 20;
	private static final long OP_TIMEOUT_MS = 8000;
	private static final long ACCEPT_TIMEOUT_MS = 30_000;
	private static final long ACCEPT_POLL_MS = 250;
	private static final long CONNECT_TIMEOUT_MS = 15_000;
	private static final int SEND_RETRIES = 3;

	private final Context appContext;
	private final PluginCallback callback;
	private final BluetoothNameGuard nameGuard;
	@Nullable
	private final BluetoothManager bluetoothManager;
	private final long proofTimeoutMs;
	private final long backOffMs;
	private final Set<String> ownLinks = ConcurrentHashMap.newKeySet();
	private final Set<Runnable> closers =
			Collections.newSetFromMap(new ConcurrentHashMap<>());

	private final Object nameLock = new Object();
	private int nameHolds = 0;

	private volatile State state = STARTING_STOPPING;

	BluetoothKeyAgreementPlugin(Context context, PluginCallback callback,
			BluetoothNameGuard nameGuard) {
		this(context, callback, nameGuard, (BluetoothManager) context
				.getApplicationContext()
				.getSystemService(Context.BLUETOOTH_SERVICE));
	}

	BluetoothKeyAgreementPlugin(Context context, PluginCallback callback,
			BluetoothNameGuard nameGuard,
			@Nullable BluetoothManager bluetoothManager) {
		this(context, callback, nameGuard, bluetoothManager,
				PairingCentralGate.PROOF_TIMEOUT_MS,
				PairingCentralGate.BACK_OFF_MS);
	}

	BluetoothKeyAgreementPlugin(Context context, PluginCallback callback,
			BluetoothNameGuard nameGuard,
			@Nullable BluetoothManager bluetoothManager, long proofTimeoutMs,
			long backOffMs) {
		this.appContext = context.getApplicationContext();
		this.callback = callback;
		this.nameGuard = nameGuard;
		this.bluetoothManager = bluetoothManager;
		this.proofTimeoutMs = proofTimeoutMs;
		this.backOffMs = backOffMs;
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
		Acceptor acceptor =
				new Acceptor(adapter, serviceUuid, localCommitment);
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
		Connector connector =
				new Connector(adapter, serviceUuid, remoteCommitment);
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
		if (android.os.Build.VERSION.SDK_INT
				< android.os.Build.VERSION_CODES.S) return null;
		return bluetoothManager == null ? null : bluetoothManager.getAdapter();
	}

	private void holdName() {
		synchronized (nameLock) {
			nameHolds++;
			nameGuard.acquire(BluetoothNameGuard.HOLDER_PAIRING);
		}
	}

	private void releaseName() {
		synchronized (nameLock) {
			if (nameHolds == 0) return;
			nameHolds--;
			if (nameHolds == 0) {
				nameGuard.release(BluetoothNameGuard.HOLDER_PAIRING);
			}
		}
	}

	private void setState(State s) {
		state = s;
		callback.pluginStateChanged(s);
	}

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
		private final AtomicBoolean nameHeld = new AtomicBoolean(false);
		private final LinkedBlockingQueue<BleGattStream> ready =
				new LinkedBlockingQueue<>(1);
		private final PairingCentralGate gate;
		private final Map<String, Integer> mtuByAddress =
				new ConcurrentHashMap<>();
		private final ScheduledExecutorService timer =
				Executors.newSingleThreadScheduledExecutor(r -> {
					Thread t = new Thread(r, "PairNearbyGate");
					t.setDaemon(true);
					return t;
				});
		private volatile int mtu = 23;
		private volatile boolean streamOpened = false;
		private volatile boolean closed = false;
		private volatile boolean advertisingWanted = true;

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

		Acceptor(BluetoothAdapter adapter, UUID serviceUuid,
				byte[] commitment) {
			this.adapter = adapter;
			this.serviceUuid = serviceUuid;
			this.gate = new PairingCentralGate(commitment);
		}

		boolean startServerAndAdvertise() {
			if (bluetoothManager == null) return false;
			if (nameHeld.compareAndSet(false, true)) holdName();
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
				service.addCharacteristic(new BluetoothGattCharacteristic(
						PROOF_CHARACTERISTIC,
						BluetoothGattCharacteristic.PROPERTY_WRITE,
						BluetoothGattCharacteristic.PERMISSION_WRITE));
				s.addService(service);
				server = s;
				characteristic = ch;
				BluetoothLeAdvertiser a = adapter.getBluetoothLeAdvertiser();
				if (a == null) return false;
				advertiser = a;
				closers.add(self);
				return startAdvertising();
			} catch (Exception e) {
				return false;
			}
		}

		private boolean startAdvertising() {
			BluetoothLeAdvertiser a = advertiser;
			if (a == null || closed) return false;
			AdvertiseData data = new AdvertiseData.Builder()
					.setIncludeDeviceName(false)
					.addServiceUuid(new ParcelUuid(serviceUuid))
					.build();
			AdvertisingSetCallback cb = new AdvertisingSetCallback() {
				@Override
				public void onAdvertisingSetStarted(
						@Nullable AdvertisingSet set, int txPower,
						int status) {
					if (status != ADVERTISE_SUCCESS
							&& advertiseCallback == this) {
						close();
					}
				}
			};
			advertiseCallback = cb;
			try {
				a.startAdvertisingSet(BleAdvertising.parameters(
								AdvertisingSetParameters.TX_POWER_LOW,
								new SecureRandom()),
						data, null, null, null, cb);
				return true;
			} catch (RuntimeException e) {
				return false;
			}
		}

		@Nullable
		private volatile AdvertisingSetCallback advertiseCallback;

		private final BluetoothGattServerCallback serverCallback =
				new BluetoothGattServerCallback() {
					@Override
					public void onConnectionStateChange(BluetoothDevice device,
							int status, int newState) {
						String address = device.getAddress();
						if (newState == BluetoothProfile.STATE_CONNECTED) {
							if (!ownLinks.contains(address)
									&& gate.onConnected(address)) {
								awaitProof(address);
							}
						} else if (newState
								== BluetoothProfile.STATE_DISCONNECTED) {
							if (device.equals(central)) {
								close();
							} else {
								gate.onDisconnected(address);
								mtuByAddress.remove(address);
							}
						}
					}

					@Override
					public void onMtuChanged(BluetoothDevice device, int m) {
						mtuByAddress.put(device.getAddress(), m);
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
						boolean ok = device.equals(central);
						respond(device, rsp, requestId, ok, offset);
						if (ok) openStreamOnce();
					}

					@Override
					public void onCharacteristicWriteRequest(
							BluetoothDevice device, int requestId,
							BluetoothGattCharacteristic ch, boolean prep,
							boolean rsp, int offset, byte[] value) {
						if (PROOF_CHARACTERISTIC.equals(ch.getUuid())) {
							onProof(device, rsp, requestId, offset, value);
							return;
						}
						boolean ok = device.equals(central);
						respond(device, rsp, requestId, ok, offset);
						if (!ok) return;
						if (FRAME_CHARACTERISTIC.equals(ch.getUuid())) {
							openStreamOnce();
							BleGattStream st = stream;
							if (value != null && st != null) st.onReceive(value);
						}
					}
				};

		private void awaitProof(String address) {
			try {
				timer.schedule(() -> gate.onTimeout(address), proofTimeoutMs,
						TimeUnit.MILLISECONDS);
			} catch (RejectedExecutionException e) {
				gate.onTimeout(address);
			}
		}

		private void onProof(BluetoothDevice device, boolean rsp,
				int requestId, int offset, @Nullable byte[] value) {
			String address = device.getAddress();
			PairingCentralGate.Verdict verdict = gate.onProof(address, value,
					!ownLinks.contains(address));
			if (verdict == PairingCentralGate.Verdict.ACCEPTED) {
				if (!device.equals(central)) {
					Integer m = mtuByAddress.get(address);
					if (m != null) mtu = m;
					central = device;
				}
				respond(device, rsp, requestId, true, offset);
				return;
			}
			respond(device, rsp, requestId, false, offset);
			if (verdict == PairingCentralGate.Verdict.BACK_OFF) backOff();
		}

		private void backOff() {
			stopAdvertising();
			try {
				timer.schedule(() -> {
					gate.endBackOff();
					if (advertisingWanted && !gate.hasChosen()) {
						startAdvertising();
					}
				}, backOffMs, TimeUnit.MILLISECONDS);
			} catch (RejectedExecutionException e) {
				gate.endBackOff();
			}
		}

		private void respond(BluetoothDevice device, boolean rsp,
				int requestId, boolean ok, int offset) {
			BluetoothGattServer s = server;
			if (!rsp || s == null) return;
			int status = ok ? BluetoothGatt.GATT_SUCCESS
					: BluetoothGatt.GATT_WRITE_NOT_PERMITTED;
			try {
				s.sendResponse(device, requestId, status, offset, null);
			} catch (RuntimeException e) {
			}
		}

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
			AdvertisingSetCallback cb = advertiseCallback;
			advertiseCallback = null;
			try {
				BluetoothLeAdvertiser a = advertiser;
				if (a != null && cb != null) a.stopAdvertisingSet(cb);
			} catch (Exception e) {
			}
		}

		void close() {
			closed = true;
			advertisingWanted = false;
			try {
				stopAdvertising();
				BleGattStream st = stream;
				if (st != null) st.setEof();
				BluetoothGattServer s = server;
				if (s != null) s.close();
			} catch (Exception e) {
			} finally {
				timer.shutdownNow();
				closers.remove(self);
				if (nameHeld.compareAndSet(true, false)) releaseName();
			}
		}

		class Listener extends KeyAgreementListener {

			Listener(BdfList descriptor) {
				super(descriptor);
			}

			@Override
			public KeyAgreementConnection accept() throws java.io.IOException {
				try {
					long end = System.nanoTime()
							+ TimeUnit.MILLISECONDS.toNanos(ACCEPT_TIMEOUT_MS);
					BleGattStream st = null;
					while (st == null && !closed && System.nanoTime() < end) {
						st = ready.poll(ACCEPT_POLL_MS, TimeUnit.MILLISECONDS);
					}
					if (st == null) st = ready.poll();
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
				advertisingWanted = false;
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
		private final AtomicBoolean nameHeld = new AtomicBoolean(false);
		private final byte[] proof;
		private volatile int mtu = 23;
		@Nullable
		private volatile String remoteAddress;

		@Nullable
		private volatile BluetoothLeScanner scanner;
		@Nullable
		private volatile BluetoothGatt gatt;
		@Nullable
		private volatile BleGattStream stream;
		@Nullable
		private volatile CountDownLatch pending;

		Connector(BluetoothAdapter adapter, UUID serviceUuid,
				byte[] commitment) {
			this.adapter = adapter;
			this.serviceUuid = serviceUuid;
			this.proof = PairingCentralGate.proof(commitment);
		}

		@Nullable
		DuplexTransportConnection scanConnectAndOpen() {
			BluetoothLeScanner s = adapter.getBluetoothLeScanner();
			if (s == null) return null;
			if (nameHeld.compareAndSet(false, true)) holdName();
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
				String address = result.getDevice().getAddress();
				if (address != null) {
					remoteAddress = address;
					ownLinks.add(address);
				}
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
							ready.countDown();
							if (st != null) close();
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
						BluetoothGattCharacteristic p =
								service.getCharacteristic(PROOF_CHARACTERISTIC);
						if (p == null) {
							enableNotifications(g, ch);
							return;
						}
						p.setValue(proof);
						p.setWriteType(BluetoothGattCharacteristic
								.WRITE_TYPE_DEFAULT);
						if (!g.writeCharacteristic(p)) ready.countDown();
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
						if (PROOF_CHARACTERISTIC.equals(ch.getUuid())) {
							onProofWritten(g, status);
							return;
						}
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

		private void onProofWritten(BluetoothGatt g, int status) {
			BluetoothGattService service = g.getService(serviceUuid);
			BluetoothGattCharacteristic ch = service == null ? null
					: service.getCharacteristic(FRAME_CHARACTERISTIC);
			if (status != BluetoothGatt.GATT_SUCCESS || ch == null) {
				ready.countDown();
				return;
			}
			enableNotifications(g, ch);
		}

		private void enableNotifications(BluetoothGatt g,
				BluetoothGattCharacteristic ch) {
			g.setCharacteristicNotification(ch, true);
			BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
			if (cccd == null) {
				ready.countDown();
				return;
			}
			cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			if (!g.writeDescriptor(cccd)) ready.countDown();
		}

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
				String address = remoteAddress;
				if (address != null) ownLinks.remove(address);
				closers.remove(self);
				if (nameHeld.compareAndSet(true, false)) releaseName();
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
