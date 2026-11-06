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
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertisingSetCallback;
import android.bluetooth.le.AdvertisingSetParameters;
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

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLock;
import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshLink;
import org.zerionproject.transport.mesh.MeshWorkQueue;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

@SuppressLint("MissingPermission")
@NotNullByDefault
public class BleMeshTransport implements MeshLink {

	public static final String LINK_ID = "ble";

	public interface PrivatePeers {

		@Nullable
		byte[] ownAdvertKey();

		List<MeshPeer> peers();
	}

	private static final class Pending {

		@Nullable
		private final MeshPeer peer;
		@Nullable
		private final UUID advert;
		@Nullable
		private volatile byte[] dialerNonce;
		@Nullable
		private volatile byte[] answererNonce;

		private Pending(@Nullable MeshPeer peer, @Nullable UUID advert) {
			this.peer = peer;
			this.advert = advert;
		}
	}

	static final long HANDSHAKE_TIMEOUT_MS = 15_000L;
	static final long PREVIOUS_ADVERT_MS = 30_000L;
	static final long DUPLICATE_WINDOW_MS = 20_000L;
	static final long SHORT_LINK_MS = 60_000L;
	static final long REDIAL_PAUSE_MS = 60_000L;
	static final int MAX_UNPROVED_CENTRALS = 3;
	private static final int MAX_DIALS_PER_CONTACT = 2;
	private static final int MAX_HELLOS_PER_LINK = 2;
	private static final int MAX_ADVERTS_REMEMBERED = 1024;
	private static final int[] NO_CONTACTS = new int[0];

	private static final UUID GATT_SERVICE_UUID =
			UUID.fromString("9f2a7c14-6b38-4d0e-8a51-c3e7d924b6f8");
	private static final UUID FRAME_UUID =
			UUID.fromString("9f2a7c14-6b38-4d0e-8a51-c3e7d924b6f9");

	private static final int TARGET_MTU = 512;
	private static final int DEFAULT_CHUNK = 20;
	private static final int LENGTH_PREFIX = 4;
	private static final int MAX_FRAME_BYTES = MeshForwarder.MAX_FRAME_BYTES;
	private static final long OP_TIMEOUT_MS = 2000;
	private static final int MAX_ATTR_LEN = 509;
	private static final int GATT_BUSY_RETRIES = 100;
	private static final long GATT_BUSY_BACKOFF_MS = 10;
	private static final int MAX_CLIENTS = MeshLinkPolicy.MAX_CLIENTS;
	private static final int MAX_CENTRALS = MeshLinkPolicy.MAX_CENTRALS;
	private static final long REAP_INTERVAL_MS = 10_000;
	private static final long CLIENT_STALE_MS = 60_000;

	private static final int MANUFACTURER_ID = 0xFFFF;
	private static final long SWAP_WAKE_LOCK_MS = 3_000L;
	private static final long ADVERTISING_RETRY_MS = 10_000L;
	private static final long CONNECT_COOLDOWN_MS = 4000;
	private final MeshLinkPolicy policy =
			new MeshLinkPolicy(new java.security.SecureRandom());
	private final java.security.SecureRandom advertisingRandom =
			new java.security.SecureRandom();
	private volatile byte[] sessionNonce;
	private volatile int txPowerLevel =
			AdvertisingSetParameters.TX_POWER_MEDIUM;
	@Nullable
	private volatile java.util.concurrent.ScheduledExecutorService nonceRotator;
	private long scanEpoch = Long.MIN_VALUE;

	private final Context appContext;
	private final MeshForwarder forwarder;
	private final BluetoothManager bluetoothManager;
	private final MeshScreenPause screenPause;
	@Nullable
	private final AndroidWakeLockManager wakeLocks;
	@Nullable
	private final TaskScheduler scheduler;
	private final AtomicReference<Runnable> swapRelease =
			new AtomicReference<>();
	private volatile long advertisingRetryMs = ADVERTISING_RETRY_MS;
	@Nullable
	private final Runnable beforeRadioUp;
	@Nullable
	private final Runnable onPeerConnected;

	private final MeshWorkQueue sendExecutor =
			new MeshWorkQueue("BleMeshSend");
	private final MeshWorkQueue receiveExecutor =
			new MeshWorkQueue("BleMeshReceive");
	private final MeshWorkQueue scanMatcher =
			new MeshWorkQueue("BleMeshScan");
	private final Semaphore opComplete = new Semaphore(0);

	@Nullable
	private volatile BluetoothLeAdvertiser advertiser;
	@Nullable
	private volatile AdvertisingSetCallback advertiseCallback;
	@Nullable
	private volatile BluetoothLeScanner scanner;
	@Nullable
	private volatile BluetoothGattServer gattServer;
	@Nullable
	private volatile BluetoothGattCharacteristic frameCharacteristic;

	private final Object radioLock = new Object();
	private volatile boolean running;
	private volatile boolean paused;
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
	private final Map<String, String> clientNonce =
			new ConcurrentHashMap<>();
	private final java.util.Set<String> activeNonces =
			ConcurrentHashMap.newKeySet();

	@Nullable
	private volatile PrivatePeers privatePeers;
	private volatile List<MeshPeer> peerSnapshot =
			java.util.Collections.emptyList();
	@Nullable
	private volatile byte[] ownAdvertKey;
	@Nullable
	private volatile UUID ownAdvert;
	@Nullable
	private volatile UUID startingAdvert;
	@Nullable
	private volatile UUID previousOwnAdvert;
	private volatile long previousAdvertUntil;
	private volatile long advertsEpoch = Long.MIN_VALUE;
	private final Object linkLock = new Object();
	private final Map<String, Integer> linkedContact =
			new ConcurrentHashMap<>();
	private final Map<String, Long> linkedSince = new ConcurrentHashMap<>();
	private final Map<String, Pending> pendingLinks =
			new ConcurrentHashMap<>();
	private final Map<String, Integer> hellosSeen = new ConcurrentHashMap<>();
	private final Map<String, Object> handshakeDue = new ConcurrentHashMap<>();
	private final java.util.Set<String> resetsSent =
			ConcurrentHashMap.newKeySet();
	private final Map<UUID, int[]> advertsSeen = remembered();
	private final Map<String, Boolean> failedDials = remembered();
	private final Map<String, Boolean> strays = remembered();
	private final Map<Integer, Long> redialAfter = new ConcurrentHashMap<>();

	private static <K, V> Map<K, V> remembered() {
		return java.util.Collections.synchronizedMap(
				new java.util.LinkedHashMap<K, V>(64, 0.75f, true) {
					@Override
					protected boolean removeEldestEntry(
							Map.Entry<K, V> eldest) {
						return size() > MAX_ADVERTS_REMEMBERED;
					}
				});
	}

	public BleMeshTransport(Context context, MeshForwarder forwarder,
			@Nullable Runnable beforeRadioUp,
			@Nullable Runnable onPeerConnected) {
		this(context, forwarder, beforeRadioUp, onPeerConnected, null, null);
	}

	public BleMeshTransport(Context context, MeshForwarder forwarder,
			@Nullable Runnable beforeRadioUp,
			@Nullable Runnable onPeerConnected,
			@Nullable AndroidWakeLockManager wakeLocks,
			@Nullable TaskScheduler scheduler) {
		this(context, forwarder, beforeRadioUp, onPeerConnected,
				(BluetoothManager) context.getApplicationContext()
						.getSystemService(Context.BLUETOOTH_SERVICE),
				MeshScreenPause.PAUSE_AFTER_MS, wakeLocks, scheduler);
	}

	BleMeshTransport(Context context, MeshForwarder forwarder,
			@Nullable Runnable beforeRadioUp,
			@Nullable Runnable onPeerConnected,
			BluetoothManager bluetoothManager) {
		this(context, forwarder, beforeRadioUp, onPeerConnected,
				bluetoothManager, MeshScreenPause.PAUSE_AFTER_MS);
	}

	BleMeshTransport(Context context, MeshForwarder forwarder,
			@Nullable Runnable beforeRadioUp,
			@Nullable Runnable onPeerConnected,
			BluetoothManager bluetoothManager, long pauseAfterMs) {
		this(context, forwarder, beforeRadioUp, onPeerConnected,
				bluetoothManager, pauseAfterMs, null, null);
	}

	BleMeshTransport(Context context, MeshForwarder forwarder,
			@Nullable Runnable beforeRadioUp,
			@Nullable Runnable onPeerConnected,
			BluetoothManager bluetoothManager, long pauseAfterMs,
			@Nullable AndroidWakeLockManager wakeLocks,
			@Nullable TaskScheduler scheduler) {
		this.appContext = context.getApplicationContext();
		this.forwarder = forwarder;
		this.beforeRadioUp = beforeRadioUp;
		this.onPeerConnected = onPeerConnected;
		this.bluetoothManager = bluetoothManager;
		this.wakeLocks = wakeLocks;
		this.scheduler = scheduler;
		this.screenPause = new MeshScreenPause(new ScreenRadio(),
				this::schedulePause, pauseAfterMs);
		sessionNonce = policy.freshNonce();
	}

	private MeshScreenPause.Cancellable schedulePause(Runnable task,
			long delayMs) {
		TaskScheduler s = scheduler;
		if (s == null) return scheduleOnRotator(task, delayMs);
		org.zerionproject.core.api.Cancellable c =
				s.schedule(task, Runnable::run, delayMs, TimeUnit.MILLISECONDS);
		return c::cancel;
	}

	public void setPauseWhenScreenOff(boolean pause) {
		screenPause.setEnabled(pause);
	}

	public void setPrivatePeers(@Nullable PrivatePeers peers) {
		privatePeers = peers;
	}

	private boolean contactsOnly() {
		return privatePeers != null;
	}

	private void refreshPeers() {
		PrivatePeers source = privatePeers;
		if (source == null) return;
		try {
			long epoch = MeshPrivateLink.currentEpoch();
			if (epoch != advertsEpoch) {
				advertsEpoch = epoch;
				advertsSeen.clear();
			}
			byte[] key = source.ownAdvertKey();
			List<MeshPeer> list = source.peers();
			byte[] before = ownAdvertKey;
			ownAdvertKey = key;
			if (list != peerSnapshot) {
				peerSnapshot = list;
				advertsSeen.clear();
				dropLinksOfFormerPeers(list);
			}
			if (key != null && !java.util.Arrays.equals(before, key)
					&& running && radioUp) {
				byte[] fresh = policy.freshNonce();
				sendExecutor.execute(() -> switchAdvertisingSet(fresh));
			}
		} catch (RuntimeException e) {
		}
	}

	private void dropLinksOfFormerPeers(List<MeshPeer> list) {
		java.util.Set<Integer> current = new java.util.HashSet<>();
		for (MeshPeer p : list) current.add(p.contact);
		for (Map.Entry<String, Integer> e : linkedContact.entrySet()) {
			if (!current.contains(e.getValue())) dropLink(e.getKey());
		}
	}

	private final class ScreenRadio implements MeshScreenPause.Radio {

		@Override
		public void pause() {
			paused = true;
			runOnRotator(BleMeshTransport.this::stopRadio);
		}

		@Override
		public void resume() {
			paused = false;
			runOnRotator(() -> {
				BluetoothAdapter adapter = bluetoothManager.getAdapter();
				if (adapter != null && adapter.isEnabled()) {
					startRadio(adapter);
				}
			});
		}
	}

	private void runOnRotator(Runnable task) {
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		if (rot == null) return;
		try {
			rot.execute(task);
		} catch (java.util.concurrent.RejectedExecutionException e) {
		}
	}

	private MeshScreenPause.Cancellable scheduleOnRotator(Runnable task,
			long delayMs) {
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		if (rot == null) return () -> {
		};
		try {
			java.util.concurrent.ScheduledFuture<?> f =
					rot.schedule(task, delayMs, TimeUnit.MILLISECONDS);
			return () -> f.cancel(false);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			return () -> {
			};
		}
	}

	@Override
	public String getId() {
		return LINK_ID;
	}

	public int getPeerCount() {
		if (contactsOnly()) {
			return new java.util.HashSet<>(linkedContact.values()).size();
		}
		java.util.Set<String> addrs =
				new java.util.HashSet<>(connectedCentrals.keySet());
		addrs.addAll(connectedClients.keySet());
		return addrs.size();
	}

	public void start() {
		if (android.os.Build.VERSION.SDK_INT
				< android.os.Build.VERSION_CODES.S) return;
		running = true;
		forwarder.addLink(this);
		java.util.concurrent.ScheduledExecutorService rot =
				new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
					Thread t = new Thread(r, "BleNonceRotate");
					t.setDaemon(true);
					return t;
				});
		nonceRotator = rot;
		registerStateReceiver();
		android.os.PowerManager pm = (android.os.PowerManager)
				appContext.getSystemService(Context.POWER_SERVICE);
		if (pm != null && !pm.isInteractive()) screenPause.onScreenOff();
		scheduleAdvertisingRotation(rot);
		rot.scheduleWithFixedDelay(this::reap, REAP_INTERVAL_MS,
				REAP_INTERVAL_MS, TimeUnit.MILLISECONDS);
		rot.scheduleWithFixedDelay(this::announceNonce,
				MeshLinkPolicy.NONCE_ANNOUNCE_INTERVAL_MS,
				MeshLinkPolicy.NONCE_ANNOUNCE_INTERVAL_MS,
				TimeUnit.MILLISECONDS);
		refreshPeers();
		BluetoothAdapter adapter = bluetoothManager.getAdapter();
		if (adapter != null && adapter.isEnabled()) {
			startRadio(adapter);
		}
	}

	private void startRadio(BluetoothAdapter adapter) {
		synchronized (radioLock) {
			if (radioUp || !running || paused) return;
		}
		refreshPeers();
		Runnable hook = beforeRadioUp;
		if (hook != null) {
			try {
				hook.run();
			} catch (RuntimeException e) {
			}
		}
		synchronized (radioLock) {
			if (radioUp || !running || paused) return;
			radioUp = true;
			try {
				openServer();
			} catch (Exception e) {
			}
			try {
				startAdvertising(adapter, policy.freshNonce());
			} catch (Exception e) {
			}
			try {
				startScanning(adapter);
			} catch (Exception e) {
			}
		}
	}

	private void stopRadio() {
		synchronized (radioLock) {
			if (!radioUp) return;
			radioUp = false;
			BluetoothLeAdvertiser a = advertiser;
			if (a != null) {
				stopAdvertisingSet(a);
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
			clientNonce.clear();
			activeNonces.clear();
			synchronized (linkLock) {
				linkedContact.clear();
				linkedSince.clear();
				pendingLinks.clear();
			}
			hellosSeen.clear();
			handshakeDue.clear();
			resetsSent.clear();
			advertsSeen.clear();
			redialAfter.clear();
			ownAdvert = null;
			startingAdvert = null;
			previousOwnAdvert = null;
			policy.clear();
		}
	}

	private void clearClientNonce(String address) {
		String hex = clientNonce.remove(address);
		if (hex != null) activeNonces.remove(hex);
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
		IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_OFF);
		screen.addAction(Intent.ACTION_SCREEN_ON);
		appContext.registerReceiver(screenReceiver, screen);
	}

	private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
				screenPause.onScreenOff();
			} else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
				screenPause.onScreenOn();
			}
		}
	};

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
				java.util.concurrent.ScheduledExecutorService rot =
						nonceRotator;
				if (adapter != null && adapter.isEnabled() && rot != null) {
					try {
						rot.execute(() -> startRadio(adapter));
					} catch (java.util.concurrent.RejectedExecutionException
							e) {
					}
				}
			} else if (state == BluetoothAdapter.STATE_TURNING_OFF
					|| state == BluetoothAdapter.STATE_OFF) {
				stopRadio();
			}
		}
	};

	private void scheduleAdvertisingRotation(
			java.util.concurrent.ScheduledExecutorService rot) {
		try {
			rot.schedule(this::rotate, policy.nextAdvertisingSetLifetimeMs(),
					TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
		}
	}

	private void rotate() {
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		try {
			if (!running) return;
			byte[] fresh = policy.freshNonce();
			sendExecutor.execute(() -> switchAdvertisingSet(fresh));
		} catch (Exception e) {
		} finally {
			if (rot != null && running) scheduleAdvertisingRotation(rot);
		}
	}

	private void switchAdvertisingSet(byte[] fresh) {
		try {
			synchronized (radioLock) {
				if (!radioUp || !running) return;
			}
			if (!contactsOnly()) {
				doBroadcast(withLengthPrefix(
						MeshLinkPolicy.encodeAnnounce(fresh)), null);
			}
			synchronized (radioLock) {
				if (!radioUp || !running) return;
				BluetoothAdapter adapter = bluetoothManager.getAdapter();
				if (adapter == null || !adapter.isEnabled()) return;
				holdAwakeForSwap();
				BluetoothLeAdvertiser a = advertiser;
				if (a != null) stopAdvertisingSet(a);
				sessionNonce = fresh;
				BluetoothLeScanner sc = scanner;
				if (sc != null && !contactsOnly()
						&& MeshDiscovery.currentEpoch() != scanEpoch) {
					sc.stopScan(scanCallback);
					startScanning(adapter);
				}
			}
			scheduleAdvertisingStart(fresh);
		} catch (Exception e) {
		}
	}

	private void scheduleAdvertisingStart(byte[] fresh) {
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		if (rot == null) return;
		try {
			rot.schedule(() -> resumeAdvertising(fresh),
					BleAdvertising.replacementDelayMs(advertisingRandom),
					TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			releaseSwapWakeLock();
		}
	}

	private void holdAwakeForSwap() {
		AndroidWakeLockManager w = wakeLocks;
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		if (w == null || rot == null) return;
		AndroidWakeLock lock = w.createWakeLock("MeshAdvertisingSwap");
		lock.acquire();
		AtomicBoolean released = new AtomicBoolean(false);
		Runnable release = () -> {
			if (released.compareAndSet(false, true)) lock.release();
		};
		Runnable previous = swapRelease.getAndSet(release);
		if (previous != null) previous.run();
		try {
			rot.schedule(release, SWAP_WAKE_LOCK_MS, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
			release.run();
		}
	}

	private void releaseSwapWakeLock() {
		Runnable release = swapRelease.getAndSet(null);
		if (release != null) release.run();
	}

	private void resumeAdvertising(byte[] fresh) {
		try {
			synchronized (radioLock) {
				if (!radioUp || !running || advertiseCallback != null) return;
				BluetoothAdapter adapter = bluetoothManager.getAdapter();
				if (adapter == null || !adapter.isEnabled()) return;
				startAdvertising(adapter, fresh);
			}
		} catch (Exception e) {
		} finally {
			releaseSwapWakeLock();
		}
	}

	private void onAdvertisingFailed(AdvertisingSetCallback cb) {
		synchronized (radioLock) {
			if (advertiseCallback != cb) return;
			advertiseCallback = null;
		}
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		if (rot == null) return;
		try {
			rot.schedule(() -> resumeAdvertising(policy.freshNonce()),
					advertisingRetryMs, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
		}
	}

	private void stopAdvertisingSet(BluetoothLeAdvertiser a) {
		AdvertisingSetCallback cb = advertiseCallback;
		advertiseCallback = null;
		if (cb == null) return;
		try {
			a.stopAdvertisingSet(cb);
		} catch (Exception e) {
		}
	}

	private void announceNonce() {
		if (contactsOnly()) return;
		try {
			synchronized (radioLock) {
				if (!radioUp || !running) return;
			}
			if (connectedCentrals.isEmpty() && connectedClients.isEmpty()) {
				return;
			}
			byte[] framed = withLengthPrefix(
					MeshLinkPolicy.encodeAnnounce(sessionNonce));
			sendExecutor.offerOwn(() -> doBroadcast(framed, null),
					framed.length);
		} catch (Exception e) {
		}
	}

	public void setShortRange(boolean shortRange) {
		int level = shortRange ? AdvertisingSetParameters.TX_POWER_LOW
				: AdvertisingSetParameters.TX_POWER_MEDIUM;
		if (level == txPowerLevel) return;
		txPowerLevel = level;
		if (!running) return;
		byte[] fresh = policy.freshNonce();
		try {
			sendExecutor.execute(() -> switchAdvertisingSet(fresh));
		} catch (java.util.concurrent.RejectedExecutionException e) {
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
				try {
					appContext.unregisterReceiver(screenReceiver);
				} catch (IllegalArgumentException e) {
				}
			}
		}
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		if (rot != null) rot.shutdownNow();
		nonceRotator = null;
		releaseSwapWakeLock();
		stopRadio();
		sendExecutor.shutdownNow();
		receiveExecutor.shutdownNow();
		scanMatcher.shutdownNow();
	}

	@Override
	public void broadcast(byte[] frame) {
		broadcast(frame, null);
	}

	@Override
	public void broadcast(byte[] frame, @Nullable String exceptPeerId) {
		byte[] framed = withLengthPrefix(frame);
		Runnable send = () -> doBroadcast(framed, exceptPeerId);
		if (exceptPeerId == null) sendExecutor.offerOwn(send, framed.length);
		else sendExecutor.offer(send, framed.length);
	}

	private void doBroadcast(byte[] framed, @Nullable String exceptPeerId) {
		boolean contactsOnly = contactsOnly();
		BluetoothGattServer server = gattServer;
		BluetoothGattCharacteristic ch = frameCharacteristic;
		if (server != null && ch != null) {
			for (BluetoothDevice central : connectedCentrals.values()) {
				String key = serverKey(central.getAddress());
				if (key.equals(exceptPeerId)) continue;
				if (contactsOnly && !linkedContact.containsKey(key)) continue;
				try {
					notifyInChunks(server, central, ch, framed);
				} catch (Exception e) {
				}
			}
		}
		for (Map.Entry<String, BluetoothGatt> e : connectedClients.entrySet()) {
			String key = clientKey(e.getKey());
			if (key.equals(exceptPeerId)) continue;
			if (contactsOnly && !linkedContact.containsKey(key)) continue;
			try {
				writeInChunks(e.getValue(), e.getKey(), framed);
			} catch (Exception ex) {
			}
		}
	}

	private void sendTo(String key, byte[] frame) {
		byte[] framed = withLengthPrefix(frame);
		sendExecutor.offerOwn(() -> doSendTo(key, framed), framed.length);
	}

	private void doSendTo(String key, byte[] framed) {
		try {
			if (key.startsWith(SERVER_PREFIX)) {
				BluetoothGattServer server = gattServer;
				BluetoothGattCharacteristic ch = frameCharacteristic;
				BluetoothDevice central = connectedCentrals.get(
						key.substring(SERVER_PREFIX.length()));
				if (server == null || ch == null || central == null) return;
				notifyInChunks(server, central, ch, framed);
			} else {
				String address = key.substring(CLIENT_PREFIX.length());
				BluetoothGatt gatt = connectedClients.get(address);
				if (gatt != null) writeInChunks(gatt, address, framed);
			}
		} catch (Exception e) {
		}
	}

	private void forgetLink(String key) {
		Pending unproved;
		Integer contact;
		Long since;
		synchronized (linkLock) {
			contact = linkedContact.remove(key);
			since = linkedSince.remove(key);
			unproved = pendingLinks.remove(key);
		}
		if (contact != null && since != null && key.startsWith(CLIENT_PREFIX)
				&& clock() - since < SHORT_LINK_MS) {
			redialAfter.put(contact, clock() + REDIAL_PAUSE_MS);
		}
		hellosSeen.remove(key);
		handshakeDue.remove(key);
		resetsSent.remove(key);
		if (unproved == null || !key.startsWith(CLIENT_PREFIX)) return;
		MeshPeer peer = unproved.peer;
		UUID advert = unproved.advert;
		if (peer != null && advert != null) {
			failedDials.put(advert + ":" + peer.contact, Boolean.TRUE);
		}
	}

	private void dropLink(String key) {
		if (key.startsWith(CLIENT_PREFIX)) {
			evictClient(key.substring(CLIENT_PREFIX.length()));
			return;
		}
		String address = key.substring(SERVER_PREFIX.length());
		BluetoothDevice central = connectedCentrals.remove(address);
		reassemblers.remove(key);
		mtuByDevice.remove(address);
		forgetLink(key);
		policy.onLinkDown(key);
		BluetoothGattServer server = gattServer;
		if (central != null && server != null) {
			try {
				server.cancelConnection(central);
			} catch (RuntimeException e) {
			}
		}
	}

	private void expectHandshake(String key) {
		if (!contactsOnly()) return;
		java.util.concurrent.ScheduledExecutorService rot = nonceRotator;
		if (rot == null) return;
		Object attempt = new Object();
		handshakeDue.put(key, attempt);
		try {
			rot.schedule(() -> {
				if (!handshakeDue.remove(key, attempt)) return;
				if (contactsOnly() && !linkedContact.containsKey(key)
						&& isUp(key)) {
					dropLink(key);
				}
			}, HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
		}
	}

	private boolean isUp(String key) {
		if (key.startsWith(SERVER_PREFIX)) {
			return connectedCentrals.containsKey(
					key.substring(SERVER_PREFIX.length()));
		}
		return connectedClients.containsKey(
				key.substring(CLIENT_PREFIX.length()));
	}

	private int dialsUnderWay(int contact) {
		int count = 0;
		for (Map.Entry<String, Pending> e : pendingLinks.entrySet()) {
			MeshPeer peer = e.getValue().peer;
			if (peer != null && peer.contact == contact
					&& e.getKey().startsWith(CLIENT_PREFIX)) {
				count++;
			}
		}
		return count;
	}

	@Nullable
	private MeshPeer peer(int contact) {
		for (MeshPeer p : peerSnapshot) {
			if (p.contact == contact) return p;
		}
		return null;
	}

	private int[] contactsRecognising(UUID seen) {
		if (!MeshPrivateLink.looksRandom(seen)) return NO_CONTACTS;
		int[] known = advertsSeen.get(seen);
		if (known != null) return known;
		long epoch = MeshPrivateLink.currentEpoch();
		List<Integer> found = new java.util.ArrayList<>();
		for (MeshPeer p : peerSnapshot) {
			if (MeshPrivateLink.recognises(p.advertKey, seen, epoch)) {
				found.add(p.contact);
			}
		}
		int[] contacts = found.isEmpty() ? NO_CONTACTS : new int[found.size()];
		for (int i = 0; i < contacts.length; i++) contacts[i] = found.get(i);
		advertsSeen.put(seen, contacts);
		return contacts;
	}

	private void onContactAdvert(ScanResult result) {
		android.bluetooth.le.ScanRecord record = result.getScanRecord();
		if (record == null) return;
		List<ParcelUuid> uuids = record.getServiceUuids();
		if (uuids == null) return;
		BluetoothDevice device = result.getDevice();
		String address = device.getAddress();
		if (connectedClients.containsKey(address)) return;
		for (ParcelUuid uuid : uuids) {
			UUID advert = uuid.getUuid();
			for (int contact : contactsRecognising(advert)) {
				if (failedDials.containsKey(advert + ":" + contact)) continue;
				MeshPeer peer = peer(contact);
				Long pause = redialAfter.get(contact);
				if (peer == null || linkedContact.containsValue(contact)
						|| (pause != null && clock() < pause)
						|| dialsUnderWay(contact) >= MAX_DIALS_PER_CONTACT) {
					continue;
				}
				dialContact(device, address, advert, peer);
				return;
			}
		}
	}

	private void dialContact(BluetoothDevice device, String address,
			UUID advert, MeshPeer peer) {
		long now = clock();
		Long last = lastConnectAttempt.get(address);
		if (last != null && now - last < CONNECT_COOLDOWN_MS) return;
		if (connectedClients.size() >= MAX_CLIENTS) {
			List<String> keys = new java.util.ArrayList<>();
			for (String a : connectedClients.keySet()) keys.add(clientKey(a));
			String victim = policy.idleLinkToEvict(keys, now);
			if (victim == null) return;
			evictClient(victim.substring(CLIENT_PREFIX.length()));
		}
		lastConnectAttempt.put(address, now);
		String key = clientKey(address);
		Pending pending = new Pending(peer, advert);
		pendingLinks.put(key, pending);
		BluetoothGatt gatt;
		try {
			gatt = device.connectGatt(appContext, false, clientCallback,
					BluetoothDevice.TRANSPORT_LE);
		} catch (RuntimeException e) {
			gatt = null;
		}
		if (gatt == null) {
			pendingLinks.remove(key, pending);
			return;
		}
		if (connectedClients.putIfAbsent(address, gatt) != null) {
			closeQuietly(gatt);
			return;
		}
		policy.onLinkUp(key, now);
		expectHandshake(key);
	}

	private void sendHello(String key) {
		if (linkedContact.containsKey(key)) return;
		Pending p = pendingLinks.get(key);
		MeshPeer peer = p == null ? null : p.peer;
		UUID advert = p == null ? null : p.advert;
		if (p == null || peer == null || advert == null) {
			dropLink(key);
			return;
		}
		if (p.dialerNonce != null) return;
		byte[] nonce = new byte[MeshPrivateLink.NONCE_BYTES];
		advertisingRandom.nextBytes(nonce);
		p.dialerNonce = nonce;
		sendTo(key, MeshPrivateLink.hello(peer.linkKey, advert, nonce));
	}

	private void onHandshake(String key, byte[] frame) {
		if (linkedContact.containsKey(key)) return;
		boolean inbound = key.startsWith(SERVER_PREFIX);
		if (frame[1] == MeshPrivateLink.TYPE_HELLO) {
			if (inbound) onHello(key, frame);
		} else if (frame[1] == MeshPrivateLink.TYPE_REPLY) {
			if (!inbound) onReply(key, frame);
		} else if (inbound) {
			onConfirm(key, frame);
		}
	}

	private List<UUID> advertsAnswered() {
		List<UUID> out = new java.util.ArrayList<>(3);
		UUID current = ownAdvert;
		UUID starting = startingAdvert;
		UUID previous = previousOwnAdvert;
		if (current != null) out.add(current);
		if (starting != null) out.add(starting);
		if (previous != null && clock() < previousAdvertUntil) {
			out.add(previous);
		}
		return out;
	}

	private void onHello(String key, byte[] frame) {
		byte[] dialerNonce = MeshPrivateLink.helloNonce(frame);
		if (dialerNonce == null || pendingLinks.containsKey(key)) return;
		int seen = hellosSeen.merge(key, 1, Integer::sum);
		if (seen > MAX_HELLOS_PER_LINK) {
			dropLink(key);
			return;
		}
		List<UUID> adverts = advertsAnswered();
		for (MeshPeer p : peerSnapshot) {
			boolean matches = false;
			for (UUID advert : adverts) {
				matches |= MeshPrivateLink.helloMatches(frame, p.linkKey,
						advert);
			}
			if (!matches) continue;
			byte[] answererNonce = new byte[MeshPrivateLink.NONCE_BYTES];
			advertisingRandom.nextBytes(answererNonce);
			Pending pending = new Pending(p, null);
			pending.dialerNonce = dialerNonce;
			pending.answererNonce = answererNonce;
			if (pendingLinks.putIfAbsent(key, pending) != null) return;
			sendTo(key, MeshPrivateLink.reply(p.linkKey, dialerNonce,
					answererNonce));
			return;
		}
	}

	private void onReply(String key, byte[] frame) {
		Pending p = pendingLinks.get(key);
		MeshPeer peer = p == null ? null : p.peer;
		byte[] dialerNonce = p == null ? null : p.dialerNonce;
		if (p == null || peer == null || dialerNonce == null
				|| p.answererNonce != null) {
			return;
		}
		byte[] answererNonce = MeshPrivateLink.replyNonce(frame, peer.linkKey,
				dialerNonce);
		if (answererNonce == null) return;
		p.answererNonce = answererNonce;
		sendTo(key, MeshPrivateLink.confirm(peer.linkKey, dialerNonce,
				answererNonce));
		onAuthenticated(key, peer, p);
	}

	private void onConfirm(String key, byte[] frame) {
		Pending p = pendingLinks.get(key);
		MeshPeer peer = p == null ? null : p.peer;
		byte[] dialerNonce = p == null ? null : p.dialerNonce;
		byte[] answererNonce = p == null ? null : p.answererNonce;
		if (p == null || peer == null || dialerNonce == null
				|| answererNonce == null) {
			return;
		}
		if (!MeshPrivateLink.confirmMatches(frame, peer.linkKey, dialerNonce,
				answererNonce)) {
			return;
		}
		onAuthenticated(key, peer, p);
	}

	private void onAuthenticated(String key, MeshPeer peer, Pending proved) {
		long now = clock();
		String drop = null;
		synchronized (linkLock) {
			if (!isUp(key) || !pendingLinks.remove(key, proved)) return;
			String other = null;
			for (Map.Entry<String, Integer> e : linkedContact.entrySet()) {
				if (e.getValue() == peer.contact) other = e.getKey();
			}
			linkedContact.put(key, peer.contact);
			linkedSince.put(key, now);
			if (other != null) {
				Long since = linkedSince.get(other);
				boolean together = since != null
						&& now - since < DUPLICATE_WINDOW_MS;
				boolean ownDial = key.startsWith(CLIENT_PREFIX);
				boolean otherOwnDial = other.startsWith(CLIENT_PREFIX);
				if (!together || ownDial == otherOwnDial) {
					drop = other;
				} else {
					drop = ownDial == peer.keepsOwnDial ? other : key;
				}
			}
		}
		hellosSeen.remove(key);
		handshakeDue.remove(key);
		if (drop != null) dropLink(drop);
		if (key.equals(drop)) return;
		if (key.startsWith(SERVER_PREFIX) && !keepsProvedCentral(key, now)) {
			return;
		}
		firePeerConnected();
	}

	private boolean keepsProvedCentral(String key, long now) {
		List<String> proved = new java.util.ArrayList<>();
		for (String k : linkedContact.keySet()) {
			if (k.startsWith(SERVER_PREFIX)) proved.add(k);
		}
		if (proved.size() <= MAX_CENTRALS) return true;
		proved.remove(key);
		String victim = policy.idleLinkToEvict(proved, now);
		if (victim == null) {
			dropLink(key);
			return false;
		}
		dropLink(victim);
		return true;
	}

	private boolean admitUnprovedCentral(BluetoothDevice device) {
		if (connectedCentrals.containsKey(device.getAddress())) return true;
		List<String> unproved = new java.util.ArrayList<>();
		for (String address : connectedCentrals.keySet()) {
			String key = serverKey(address);
			if (!linkedContact.containsKey(key)) unproved.add(key);
		}
		if (unproved.size() >= MAX_UNPROVED_CENTRALS) {
			dropLink(unproved.get(0));
		}
		return true;
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
						if (contactsOnly() && connectedClients.containsKey(
								device.getAddress())) {
							return;
						}
						if (!admitCentral(device)) return;
						boolean known = connectedCentrals.put(
								device.getAddress(), device) != null;
						if (contactsOnly() && known) return;
						policy.onLinkUp(serverKey(device.getAddress()),
								clock());
						if (contactsOnly()) {
							holdCentral(device);
							expectHandshake(serverKey(device.getAddress()));
						} else {
							firePeerConnected();
						}
					} else if (newState
							== BluetoothProfile.STATE_DISCONNECTED) {
						connectedCentrals.remove(device.getAddress());
						reassemblers.remove(serverKey(device.getAddress()));
						mtuByDevice.remove(device.getAddress());
						forgetLink(serverKey(device.getAddress()));
						policy.onLinkDown(serverKey(device.getAddress()));
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
					if (contactsOnly() && !connectedCentrals.containsKey(
							device.getAddress())) {
						closeStray(device);
					} else if (FRAME_UUID.equals(characteristic.getUuid())) {
						onBytes(serverKey(device.getAddress()), value);
					}
					BluetoothGattServer server = gattServer;
					if (responseNeeded && server != null) {
						server.sendResponse(device, requestId,
								BluetoothGatt.GATT_SUCCESS, offset, null);
					}
				}
			};

	private void closeStray(BluetoothDevice device) {
		String address = device.getAddress();
		if (connectedClients.containsKey(address)) return;
		if (strays.put(address, Boolean.TRUE) != null) return;
		BluetoothGattServer server = gattServer;
		if (server == null) return;
		try {
			server.connect(device, false);
			server.cancelConnection(device);
		} catch (RuntimeException e) {
		}
	}

	private void holdCentral(BluetoothDevice device) {
		BluetoothGattServer server = gattServer;
		if (server == null) return;
		try {
			server.connect(device, false);
		} catch (RuntimeException e) {
		}
	}

	private boolean admitCentral(BluetoothDevice device) {
		if (contactsOnly()) return admitUnprovedCentral(device);
		String address = device.getAddress();
		if (connectedCentrals.containsKey(address)
				|| connectedCentrals.size() < MAX_CENTRALS) {
			return true;
		}
		BluetoothGattServer server = gattServer;
		List<String> keys = new java.util.ArrayList<>();
		for (String a : connectedCentrals.keySet()) keys.add(serverKey(a));
		String victim = policy.idleLinkToEvict(keys, clock());
		if (victim == null) {
			if (server != null) {
				try {
					server.cancelConnection(device);
				} catch (RuntimeException e) {
				}
			}
			return false;
		}
		String victimAddress = victim.substring(SERVER_PREFIX.length());
		BluetoothDevice old = connectedCentrals.remove(victimAddress);
		reassemblers.remove(victim);
		mtuByDevice.remove(victimAddress);
		forgetLink(victim);
		policy.onLinkDown(victim);
		if (old != null && server != null) {
			try {
				server.cancelConnection(old);
			} catch (RuntimeException e) {
			}
		}
		return true;
	}

	private void evictClient(String address) {
		BluetoothGatt g = connectedClients.remove(address);
		if (g != null) closeQuietly(g);
		clearClientNonce(address);
		reassemblers.remove(clientKey(address));
		mtuByDevice.remove(address);
		lastConnectAttempt.remove(address);
		forgetLink(clientKey(address));
		policy.onLinkDown(clientKey(address));
	}

	private void startScanning(BluetoothAdapter adapter) {
		BluetoothLeScanner s = adapter.getBluetoothLeScanner();
		if (s == null) return;
		scanner = s;
		long epoch = MeshDiscovery.currentEpoch();
		scanEpoch = epoch;
		List<ScanFilter> filters = new java.util.ArrayList<>();
		if (contactsOnly()) {
			filters.add(new ScanFilter.Builder().setServiceUuid(
					new ParcelUuid(MeshPrivateLink.RANDOM_UUID_BITS),
					new ParcelUuid(MeshPrivateLink.RANDOM_UUID_MASK))
					.build());
		} else {
			for (long e = epoch - 3; e <= epoch + 3; e++) {
				filters.add(new ScanFilter.Builder()
						.setServiceUuid(new ParcelUuid(
								MeshDiscovery.discoveryUuid(e,
										GATT_SERVICE_UUID)))
						.build());
			}
		}
		ScanSettings settings = new ScanSettings.Builder()
				.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
				.build();
		s.startScan(filters, settings, scanCallback);
	}

	private final ScanCallback scanCallback = new ScanCallback() {
		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			if (contactsOnly()) {
				scanMatcher.offer(() -> onContactAdvert(result), 64);
				return;
			}
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
			String nonceHex = StringUtils.toHexString(peerNonce);
			long now = clock();
			if (activeNonces.contains(nonceHex)
					|| policy.isAnnounced(nonceHex, now)) {
				return;
			}
			Long last = lastConnectAttempt.get(address);
			if (last != null && now - last < CONNECT_COOLDOWN_MS) return;
			if (connectedClients.size() >= MAX_CLIENTS) {
				List<String> keys = new java.util.ArrayList<>();
				for (String a : connectedClients.keySet()) {
					keys.add(clientKey(a));
				}
				String victim = policy.idleLinkToEvict(keys, now);
				if (victim == null) return;
				evictClient(victim.substring(CLIENT_PREFIX.length()));
			}
			lastConnectAttempt.put(address, now);
			BluetoothGatt gatt = device.connectGatt(appContext, false,
					clientCallback, BluetoothDevice.TRANSPORT_LE);
			if (gatt == null) return;
			if (connectedClients.putIfAbsent(address, gatt) != null) {
				closeQuietly(gatt);
			} else {
				clientNonce.put(address, nonceHex);
				activeNonces.add(nonceHex);
				policy.onLinkUp(clientKey(address), now);
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
						lastConnectAttempt.put(address, clock());
						gatt.requestMtu(TARGET_MTU);
					} else if (newState
							== BluetoothProfile.STATE_DISCONNECTED) {
						reassemblers.remove(clientKey(address));
						mtuByDevice.remove(address);
						if (contactsOnly()) {
							if (connectedClients.get(address) == gatt) {
								evictClient(address);
							} else {
								closeQuietly(gatt);
							}
						} else if (running && radioUp
								&& connectedClients.get(address) == gatt) {
							try {
								gatt.connect();
							} catch (RuntimeException e) {
							}
						} else {
							closeQuietly(gatt);
						}
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
					if (contactsOnly()) {
						sendHello(clientKey(gatt.getDevice().getAddress()));
						return;
					}
					announceNonce();
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

	private void startAdvertising(BluetoothAdapter adapter, byte[] nonce) {
		sessionNonce = nonce;
		BluetoothLeAdvertiser a = adapter.getBluetoothLeAdvertiser();
		if (a == null) return;
		advertiser = a;
		AdvertiseData data;
		AdvertiseData scanResponse;
		if (contactsOnly()) {
			byte[] key = ownAdvertKey;
			if (key == null) return;
			UUID advert = MeshPrivateLink.advert(key, advertisingRandom,
					MeshPrivateLink.currentEpoch());
			startingAdvert = advert;
			data = new AdvertiseData.Builder()
					.setIncludeDeviceName(false)
					.addServiceUuid(new ParcelUuid(advert))
					.build();
			scanResponse = null;
		} else {
			data = new AdvertiseData.Builder()
					.setIncludeDeviceName(false)
					.addServiceUuid(new ParcelUuid(
							MeshDiscovery.discoveryUuid(
									MeshDiscovery.currentEpoch(),
									GATT_SERVICE_UUID)))
					.build();
			scanResponse = new AdvertiseData.Builder()
					.setIncludeDeviceName(false)
					.addManufacturerData(MANUFACTURER_ID, nonce)
					.build();
		}
		AdvertisingSetCallback cb = new AdvertisingSetCallback() {
			@Override
			public void onAdvertisingSetStarted(
					@Nullable android.bluetooth.le.AdvertisingSet set,
					int txPower, int status) {
				if (status != ADVERTISE_SUCCESS) {
					onAdvertisingFailed(this);
					return;
				}
				UUID started = startingAdvert;
				if (started == null || advertiseCallback != this) return;
				previousOwnAdvert = ownAdvert;
				previousAdvertUntil = clock() + PREVIOUS_ADVERT_MS;
				ownAdvert = started;
				startingAdvert = null;
			}
		};
		advertiseCallback = cb;
		a.startAdvertisingSet(
				BleAdvertising.parameters(txPowerLevel, advertisingRandom),
				data, scanResponse, null, null, cb);
	}

	private void onBytes(String key, @Nullable byte[] value) {
		if (value == null || value.length == 0) return;
		MeshFrameReassembler r = reassemblers.computeIfAbsent(key,
				k -> new MeshFrameReassembler(MAX_FRAME_BYTES));
		r.append(value);
		byte[] frame;
		while ((frame = r.poll()) != null) {
			if (MeshLinkPolicy.isControl(frame)) {
				if (contactsOnly()) {
					if (MeshPrivateLink.isHandshake(frame)) {
						onHandshake(key, frame);
					} else if (MeshPrivateLink.isReset(frame)
							&& key.startsWith(CLIENT_PREFIX)
							&& linkedContact.containsKey(key)) {
						dropLink(key);
					}
					continue;
				}
				byte[] announced = MeshLinkPolicy.decodeAnnounce(frame);
				if (announced != null) {
					policy.onAnnounce(key, announced, clock());
				}
				continue;
			}
			if (contactsOnly() && !linkedContact.containsKey(key)) {
				if (key.startsWith(SERVER_PREFIX) && resetsSent.add(key)) {
					sendTo(key, MeshPrivateLink.reset());
				}
				continue;
			}
			byte[] f = frame;
			receiveExecutor.offer(() -> forwarder.onReceive(f, LINK_ID, key),
					f.length);
		}
	}

	public void onOpenedFrom(String peerKey) {
		policy.onUseful(peerKey, clock());
	}

	private static final String SERVER_PREFIX = "s:";
	private static final String CLIENT_PREFIX = "c:";

	private static String serverKey(String address) {
		return SERVER_PREFIX + address;
	}

	private static String clientKey(String address) {
		return CLIENT_PREFIX + address;
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
			if (!dispatch(() -> gatt.writeCharacteristic(ch))) break;
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
			if (!dispatch(() ->
					server.notifyCharacteristicChanged(central, ch, false))) {
				break;
			}
		}
	}

	private boolean dispatch(java.util.concurrent.Callable<Boolean> op) {
		for (int attempt = 0; attempt < GATT_BUSY_RETRIES; attempt++) {
			opComplete.drainPermits();
			boolean queued;
			try {
				queued = Boolean.TRUE.equals(op.call());
			} catch (Exception e) {
				return false;
			}
			if (queued) {
				awaitOp();
				return true;
			}
			try {
				Thread.sleep(GATT_BUSY_BACKOFF_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
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

	private void reap() {
		if (!radioUp || !running) return;
		refreshPeers();
		for (String key : linkedContact.keySet()) {
			if (!isUp(key)) forgetLink(key);
		}
		long now = clock();
		try {
			for (Map.Entry<String, BluetoothGatt> e :
					connectedClients.entrySet()) {
				String addr = e.getKey();
				BluetoothGatt g = e.getValue();
				if (bluetoothManager.getConnectionState(g.getDevice(),
						BluetoothProfile.GATT)
						== BluetoothProfile.STATE_CONNECTED) {
					continue;
				}
				Long since = lastConnectAttempt.get(addr);
				if (since != null && now - since < CLIENT_STALE_MS) continue;
				if (connectedClients.remove(addr, g)) {
					closeQuietly(g);
					clearClientNonce(addr);
					reassemblers.remove(clientKey(addr));
					mtuByDevice.remove(addr);
					lastConnectAttempt.remove(addr);
					forgetLink(clientKey(addr));
					policy.onLinkDown(clientKey(addr));
				}
			}
			for (BluetoothDevice dev :
					new java.util.ArrayList<>(connectedCentrals.values())) {
				if (bluetoothManager.getConnectionState(dev,
						BluetoothProfile.GATT)
						!= BluetoothProfile.STATE_CONNECTED) {
					String addr = dev.getAddress();
					connectedCentrals.remove(addr);
					reassemblers.remove(serverKey(addr));
					mtuByDevice.remove(addr);
					forgetLink(serverKey(addr));
					policy.onLinkDown(serverKey(addr));
				}
			}
			policy.expire(now);
		} catch (Exception ignored) {
		}
	}

	private static void closeQuietly(BluetoothGatt gatt) {
		try {
			gatt.disconnect();
			gatt.close();
		} catch (RuntimeException ignored) {
		}
	}
}
