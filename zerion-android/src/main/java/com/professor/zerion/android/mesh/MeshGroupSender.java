package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.MeshBundleStore;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMeshSink;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
public class MeshGroupSender implements GroupTrMeshSink {

	private static final long TTL_SECONDS = 7L * 24 * 3600;
	private static final long RETRY_INTERVAL_MS = 30_000;
	private static final long OUTBOX_TTL_MS = 24L * 3600 * 1000;
	private static final int MAX_ATTEMPTS = 240;
	private static final int MAX_OUTBOX_ENTRIES = 512;

	private final PluginManager pluginManager;
	private final MeshController meshController;
	private final MeshManager meshManager;
	private final MeshBundleStore bundleStore;
	private final MessagingManager messagingManager;
	private final CryptoComponent crypto;
	private final Executor ioExecutor;

	private final Map<String, Pending> outbox = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler;
	private volatile boolean wasRunning = false;
	private volatile boolean shareNeeded = true;

	@Inject
	MeshGroupSender(PluginManager pluginManager, MeshController meshController,
			MeshManager meshManager, MeshBundleStore bundleStore,
			GroupTrManager groupTrManager, MessagingManager messagingManager,
			CryptoComponent crypto, @IoExecutor Executor ioExecutor) {
		this.pluginManager = pluginManager;
		this.meshController = meshController;
		this.meshManager = meshManager;
		this.bundleStore = bundleStore;
		this.messagingManager = messagingManager;
		this.crypto = crypto;
		this.ioExecutor = ioExecutor;
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "MeshGroupBackground");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(this::retryPass, RETRY_INTERVAL_MS,
				RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
		groupTrManager.setMeshSink(this);
	}

	@Override
	public boolean isOfflineMode() {
		return pluginManager.isOfflineMode();
	}

	@Override
	public void floodRecord(int contactId, byte[] record, long timestamp) {
		ioExecutor.execute(() -> {
			if (!trySeal(contactId, record, timestamp)) {
				evictIfFull();
				outbox.put(key(contactId, record),
						new Pending(contactId, record, timestamp, now()));
			}
		});
	}

	private void evictIfFull() {
		while (outbox.size() >= MAX_OUTBOX_ENTRIES) {
			String oldestKey = null;
			long oldest = Long.MAX_VALUE;
			for (Map.Entry<String, Pending> e : outbox.entrySet()) {
				if (e.getValue().firstSeenMs < oldest) {
					oldest = e.getValue().firstSeenMs;
					oldestKey = e.getKey();
				}
			}
			if (oldestKey == null || outbox.remove(oldestKey) == null) return;
		}
	}

	private boolean trySeal(int contactId, byte[] record, long timestamp) {
		if (!meshController.isRunning()) return false;
		AsyncPrekeyBundle bundle;
		try {
			bundle = bundleStore.getContactBundle(contactId, crypto);
		} catch (Exception e) {
			return false;
		}
		if (bundle == null) return false;
		byte[] inner = frame(timestamp, record);
		if (inner.length > MeshPadding.MAX_DATA_BYTES) {
			return true;
		}
		try {
			byte[] padded = MeshPadding.pad(inner);
			meshManager.sendOffline(bundle,
					MeshMessageRouter.MESH_GROUP_RECORD, padded, TTL_SECONDS,
					true);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	private void retryPass() {
		try {
			boolean offline = pluginManager.isOfflineMode();
			if (!offline) {
				if (shareNeeded) {
					try {
						messagingManager.shareUndeliveredMeshGroupRecords();
						shareNeeded = false;
					} catch (Exception e) {
					}
				}
			} else {
				shareNeeded = true;
			}
			boolean running = meshManager.isRunning() && offline;
			if (running && !wasRunning) reload();
			wasRunning = running;
			long nowMs = now();
			for (Map.Entry<String, Pending> e : outbox.entrySet()) {
				Pending p = e.getValue();
				if (nowMs - p.firstSeenMs > OUTBOX_TTL_MS
						|| p.attempts >= MAX_ATTEMPTS) {
					outbox.remove(e.getKey());
				}
			}
			if (!running) return;
			for (Map.Entry<String, Pending> e : outbox.entrySet()) {
				Pending p = e.getValue();
				p.attempts++;
				if (trySeal(p.contactId, p.record, p.timestamp)) {
					outbox.remove(e.getKey());
				}
			}
		} catch (Exception e) {
		}
	}

	private void reload() {
		try {
			for (MessagingManager.UndeliveredMeshGroupRecord u :
					messagingManager.getUndeliveredMeshGroupRecords()) {
				evictIfFull();
				outbox.putIfAbsent(key(u.contactId.getInt(), u.record),
						new Pending(u.contactId.getInt(), u.record,
								u.timestamp, now()));
			}
		} catch (Exception e) {
		}
	}

	private static String key(int contactId, byte[] record) {
		try {
			byte[] h = java.security.MessageDigest.getInstance("SHA-256")
					.digest(record);
			StringBuilder sb = new StringBuilder(contactId + ":");
			for (int i = 0; i < 8; i++) {
				sb.append(Integer.toHexString((h[i] & 0xFF) | 0x100)
						.substring(1));
			}
			return sb.toString();
		} catch (java.security.NoSuchAlgorithmException e) {
			return contactId + ":" + record.length + ":"
					+ java.util.Arrays.hashCode(record);
		}
	}

	private static byte[] frame(long timestamp, byte[] record) {
		byte[] out = new byte[8 + record.length];
		for (int i = 0; i < 8; i++) {
			out[i] = (byte) (timestamp >>> (8 * (7 - i)));
		}
		System.arraycopy(record, 0, out, 8, record.length);
		return out;
	}

	private static long now() {
		return System.currentTimeMillis();
	}

	private static final class Pending {
		private final int contactId;
		private final byte[] record;
		private final long timestamp;
		private final long firstSeenMs;
		private int attempts;

		Pending(int contactId, byte[] record, long timestamp,
				long firstSeenMs) {
			this.contactId = contactId;
			this.record = record;
			this.timestamp = timestamp;
			this.firstSeenMs = firstSeenMs;
		}
	}
}
