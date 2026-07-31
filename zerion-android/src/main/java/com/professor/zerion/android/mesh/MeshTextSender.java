package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.MeshBundleStore;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.MessagingManager.UndeliveredMeshMessage;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.nio.charset.StandardCharsets.UTF_8;

@Singleton
@NotNullByDefault
public class MeshTextSender {

	private static final long TTL_SECONDS = 7L * 24 * 3600;
	private static final int MAX_JITTER_MS = 1500;
	private static final long RETRY_INTERVAL_MS = 30_000;
	private static final long COVER_MIN_MS = 120_000;
	private static final int COVER_SPREAD_MS = 180_000;
	static final int MESSAGE_ID_BYTES = 32;
	static final int TIMESTAMP_BYTES = 8;
	static final int HEADER_BYTES = MESSAGE_ID_BYTES + TIMESTAMP_BYTES;

	private final java.security.SecureRandom jitter =
			new java.security.SecureRandom();

	private static final long PRESENCE_TTL_SECONDS = 180;
	private static final int PRESENCE_BUCKET_MIN = 4;
	private static final long PRESENCE_INTERVAL_MS = 60_000;
	private static final long PRESENCE_INITIAL_DELAY_MS = 4_000;
	private static final long PEER_CONNECT_BEACON_DELAY_MS = 1_500;

	private final PluginManager pluginManager;
	private final MeshController meshController;
	private final MeshManager meshManager;
	private final MeshBundleStore bundleStore;
	private final MessagingManager messagingManager;
	private final MeshOutbox outbox;
	private final ContactManager contactManager;
	private final CryptoComponent crypto;
	private final Executor ioExecutor;

	private volatile boolean wasRunning = false;
	private final ScheduledExecutorService scheduler;

	@Inject
	MeshTextSender(PluginManager pluginManager, MeshController meshController,
			MeshManager meshManager, MeshBundleStore bundleStore,
			MessagingManager messagingManager, MeshOutbox outbox,
			ContactManager contactManager, CryptoComponent crypto,
			@IoExecutor Executor ioExecutor) {
		this.pluginManager = pluginManager;
		this.meshController = meshController;
		this.meshManager = meshManager;
		this.bundleStore = bundleStore;
		this.messagingManager = messagingManager;
		this.outbox = outbox;
		this.contactManager = contactManager;
		this.crypto = crypto;
		this.ioExecutor = ioExecutor;
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "MeshBackground");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(this::retryPass, RETRY_INTERVAL_MS,
				RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
		scheduler.scheduleWithFixedDelay(this::broadcastPresence,
				PRESENCE_INITIAL_DELAY_MS, PRESENCE_INTERVAL_MS,
				TimeUnit.MILLISECONDS);
		scheduleCover();
		meshManager.setPeerConnectedListener(this::onPeerConnected);
	}

	private void onPeerConnected() {
		try {
			scheduler.schedule(this::broadcastPresence,
					PEER_CONNECT_BEACON_DELAY_MS, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
		}
	}

	private void broadcastPresence() {
		try {
			if (!meshManager.isRunning() || !pluginManager.isOfflineMode()
					|| meshManager.getPeerCount() == 0) {
				return;
			}
			int sent = 0;
			for (Contact c : contactManager.getContacts()) {
				ContactId id = c.getId();
				try {
					AsyncPrekeyBundle bundle =
							bundleStore.getContactBundle(id.getInt(), crypto);
					if (bundle == null) continue;
					meshManager.sendOffline(bundle,
							MeshMessageRouter.MESH_PRESENCE,
							MeshPadding.pad(new byte[0]), PRESENCE_TTL_SECONDS,
							false);
					sent++;
				} catch (Exception e) {
				}
			}
			int bucket = PRESENCE_BUCKET_MIN;
			while (bucket < sent) bucket <<= 1;
			for (int i = sent; sent > 0 && i < bucket; i++) {
				try {
					meshManager.sendCover(false, PRESENCE_TTL_SECONDS);
				} catch (Exception e) {
				}
			}
		} catch (Exception e) {
		}
	}

	private void scheduleCover() {
		long delay = COVER_MIN_MS + jitter.nextInt(COVER_SPREAD_MS);
		try {
			scheduler.schedule(this::coverTick, delay, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException e) {
		}
	}

	private void coverTick() {
		try {
			if (meshManager.isRunning() && meshManager.getPeerCount() > 0) {
				ioExecutor.execute(() -> {
					try {
						meshManager.sendCover();
					} catch (Exception e) {
					}
				});
			}
		} catch (Exception e) {
		} finally {
			scheduleCover();
		}
	}

	@Nullable
	public AsyncPrekeyBundle offlineTarget(ContactId contactId) {
		if (!pluginManager.isOfflineMode()) {
			return null;
		}
		if (!meshController.isRunning()) {
			return null;
		}
		try {
			AsyncPrekeyBundle b =
					bundleStore.getContactBundle(contactId.getInt(), crypto);
			return b;
		} catch (DbException e) {
			return null;
		}
	}

	public boolean isOfflineMode() {
		return pluginManager.isOfflineMode();
	}

	public void sendOfflineText(ContactId contactId, MessageId messageId,
			String text, long composeTimeMs) {
		outbox.add(contactId, messageId, text, composeTimeMs);
		ioExecutor.execute(() -> {
			try {
				AsyncPrekeyBundle bundle =
						bundleStore.getContactBundle(contactId.getInt(), crypto);
				if (bundle != null) {
					sealAndFlood(bundle, contactId, messageId, text,
							composeTimeMs, true, true);
				}
			} catch (DbException e) {
			}
		});
	}

	public void sendAck(ContactId contactId, byte[] messageId) {
		ioExecutor.execute(() -> {
			try {
				Thread.sleep(jitterMs());
				AsyncPrekeyBundle bundle =
						bundleStore.getContactBundle(contactId.getInt(), crypto);
				if (bundle == null) return;
				byte[] padded = MeshPadding.pad(messageId);
				meshManager.sendOffline(bundle, MeshMessageRouter.MESH_ACK,
						padded, TTL_SECONDS, false);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
			}
		});
	}

	public void onDelivered(ContactId contactId, MessageId messageId) {
		if (!outbox.remove(contactId, messageId)) return;
		try {
			messagingManager.setMeshMessageState(contactId, messageId,
					MessagingManager.MESH_STATE_DELIVERED);
		} catch (DbException e) {
		}
	}

	private void sealAndFlood(AsyncPrekeyBundle bundle, ContactId contactId,
			MessageId messageId, String text, long composeTimeMs,
			boolean withJitter, boolean preferOneTime) {
		try {
			if (withJitter) Thread.sleep(jitterMs());
			byte[] textBytes = text.getBytes(UTF_8);
			if (HEADER_BYTES + textBytes.length > MeshPadding.MAX_DATA_BYTES) {
				outbox.drop(messageId);
				return;
			}
			byte[] inner = buildInner(messageId.getBytes(), composeTimeMs,
					textBytes);
			byte[] padded = MeshPadding.pad(inner);
			meshManager.sendOffline(bundle, MeshMessageRouter.MESH_TEXT, padded,
					TTL_SECONDS, preferOneTime);
			if (meshManager.getPeerCount() > 0) {
				try {
					messagingManager.setMeshMessageState(contactId, messageId,
							MessagingManager.MESH_STATE_SENT);
				} catch (DbException e) {
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
		}
	}

	private void retryPass() {
		boolean running = meshManager.isRunning()
				&& pluginManager.isOfflineMode();
		if (running && !wasRunning) reload();
		wasRunning = running;
		long nowMs = now();
		for (MeshOutbox.Entry e : outbox.snapshot()) {
			if (nowMs - e.firstSeenMs > MeshOutbox.TTL_MS
					|| e.attempts >= MeshOutbox.MAX_ATTEMPTS) {
				outbox.drop(e.messageId);
			}
		}
		if (!running || meshManager.getPeerCount() == 0) return;
		for (MeshOutbox.Entry e : outbox.snapshot()) {
			e.attempts++;
			ioExecutor.execute(() -> {
				try {
					AsyncPrekeyBundle bundle = bundleStore.getContactBundle(
							e.contactId.getInt(), crypto);
					if (bundle == null) return;
					sealAndFlood(bundle, e.contactId, e.messageId, e.text,
							e.firstSeenMs, true, false);
				} catch (DbException ex) {
				}
			});
		}
	}

	private void reload() {
		try {
			for (UndeliveredMeshMessage u :
					messagingManager.getUndeliveredMeshMessages()) {
				outbox.add(u.contactId, u.messageId, u.text, u.timestamp);
			}
		} catch (DbException e) {
		}
	}

	private static byte[] buildInner(byte[] messageId, long composeTimeMs,
			byte[] text) {
		byte[] out = new byte[HEADER_BYTES + text.length];
		System.arraycopy(messageId, 0, out, 0, MESSAGE_ID_BYTES);
		for (int i = 0; i < TIMESTAMP_BYTES; i++) {
			out[MESSAGE_ID_BYTES + i] =
					(byte) (composeTimeMs >>> (8 * (TIMESTAMP_BYTES - 1 - i)));
		}
		System.arraycopy(text, 0, out, HEADER_BYTES, text.length);
		return out;
	}

	private long jitterMs() {
		return jitter.nextInt(MAX_JITTER_MS);
	}

	private static long now() {
		return System.currentTimeMillis();
	}
}
