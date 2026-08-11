package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.MeshBundleStore;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
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
public class MeshAttachmentSender {

	public static final int MAX_MESH_PHOTO_BYTES = 45_000;
	static final int ATTACH_ID_BYTES = 16;
	static final int MANIFEST_HEADER_BYTES = ATTACH_ID_BYTES + 8 + 4 + 2 + 1;
	static final int CHUNK_HEADER_BYTES = ATTACH_ID_BYTES + 2;
	static final int MAX_CONTENT_TYPE_BYTES = 80;
	private static final int CHUNK_DATA_MAX =
			MeshPadding.MAX_DATA_BYTES - CHUNK_HEADER_BYTES;
	private static final long TTL_SECONDS = 7L * 24 * 3600;
	private static final long RETRY_INTERVAL_MS = 20_000;
	private static final int MAX_ATTEMPTS = 15;
	private static final int MAX_PENDING = 16;

	private final MeshManager meshManager;
	private final MeshBundleStore bundleStore;
	private final MessagingManager messagingManager;
	private final CryptoComponent crypto;
	private final Executor ioExecutor;
	private final SecureRandom random = new SecureRandom();
	private final Map<String, Pending> pending = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler;

	private static final class Pending {
		final ContactId contactId;
		final MessageId pmId;
		final byte[] attachId;
		final String contentType;
		final byte[] bytes;
		final long composeMs;
		final int chunkCount;
		int attempts;

		Pending(ContactId contactId, MessageId pmId, byte[] attachId,
				String contentType, byte[] bytes, long composeMs,
				int chunkCount) {
			this.contactId = contactId;
			this.pmId = pmId;
			this.attachId = attachId;
			this.contentType = contentType;
			this.bytes = bytes;
			this.composeMs = composeMs;
			this.chunkCount = chunkCount;
		}
	}

	@Inject
	MeshAttachmentSender(MeshManager meshManager, MeshBundleStore bundleStore,
			MessagingManager messagingManager, CryptoComponent crypto,
			@IoExecutor Executor ioExecutor) {
		this.meshManager = meshManager;
		this.bundleStore = bundleStore;
		this.messagingManager = messagingManager;
		this.crypto = crypto;
		this.ioExecutor = ioExecutor;
		scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "MeshAttach");
			t.setDaemon(true);
			return t;
		});
		scheduler.scheduleWithFixedDelay(this::retryPass, RETRY_INTERVAL_MS,
				RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	public static boolean tooLarge(int byteCount) {
		return byteCount > MAX_MESH_PHOTO_BYTES;
	}

	public void sendOfflinePhoto(ContactId contactId, MessageId messageId,
			String contentType, byte[] imageBytes, long composeMs) {
		if (imageBytes.length == 0 || imageBytes.length > MAX_MESH_PHOTO_BYTES) {
			return;
		}
		if (pending.size() >= MAX_PENDING) return;
		ioExecutor.execute(() -> {
			byte[] attachId = new byte[ATTACH_ID_BYTES];
			random.nextBytes(attachId);
			int chunkCount =
					(imageBytes.length + CHUNK_DATA_MAX - 1) / CHUNK_DATA_MAX;
			Pending p = new Pending(contactId, messageId, attachId, contentType,
					imageBytes, composeMs, chunkCount);
			pending.put(hex(attachId), p);
			flood(p);
		});
	}

	private void flood(Pending p) {
		try {
			AsyncPrekeyBundle bundle =
					bundleStore.getContactBundle(p.contactId.getInt(), crypto);
			if (bundle == null) return;
			meshManager.sendOffline(bundle,
					MeshMessageRouter.MESH_ATTACH_MANIFEST,
					MeshPadding.pad(buildManifest(p)), TTL_SECONDS, false);
			for (int i = 0; i < p.chunkCount; i++) {
				int off = i * CHUNK_DATA_MAX;
				int len = Math.min(CHUNK_DATA_MAX, p.bytes.length - off);
				byte[] chunk = new byte[CHUNK_HEADER_BYTES + len];
				System.arraycopy(p.attachId, 0, chunk, 0, ATTACH_ID_BYTES);
				chunk[ATTACH_ID_BYTES] = (byte) (i >>> 8);
				chunk[ATTACH_ID_BYTES + 1] = (byte) i;
				System.arraycopy(p.bytes, off, chunk, CHUNK_HEADER_BYTES, len);
				meshManager.sendOffline(bundle,
						MeshMessageRouter.MESH_ATTACH_CHUNK,
						MeshPadding.pad(chunk), TTL_SECONDS, false);
			}
		} catch (Exception e) {
		}
	}

	private byte[] buildManifest(Pending p) {
		byte[] ct = p.contentType.getBytes(StandardCharsets.UTF_8);
		int ctLen = Math.min(ct.length, MAX_CONTENT_TYPE_BYTES);
		byte[] out = new byte[MANIFEST_HEADER_BYTES + ctLen];
		int o = 0;
		System.arraycopy(p.attachId, 0, out, o, ATTACH_ID_BYTES);
		o += ATTACH_ID_BYTES;
		for (int i = 0; i < 8; i++) {
			out[o + i] = (byte) (p.composeMs >>> (8 * (7 - i)));
		}
		o += 8;
		int size = p.bytes.length;
		out[o] = (byte) (size >>> 24);
		out[o + 1] = (byte) (size >>> 16);
		out[o + 2] = (byte) (size >>> 8);
		out[o + 3] = (byte) size;
		o += 4;
		out[o] = (byte) (p.chunkCount >>> 8);
		out[o + 1] = (byte) p.chunkCount;
		o += 2;
		out[o] = (byte) ctLen;
		o += 1;
		System.arraycopy(ct, 0, out, o, ctLen);
		return out;
	}

	public void sendAck(ContactId contactId, byte[] attachId) {
		ioExecutor.execute(() -> {
			try {
				AsyncPrekeyBundle bundle = bundleStore
						.getContactBundle(contactId.getInt(), crypto);
				if (bundle == null) return;
				meshManager.sendOffline(bundle,
						MeshMessageRouter.MESH_ATTACH_ACK,
						MeshPadding.pad(attachId.clone()), TTL_SECONDS, false);
			} catch (Exception e) {
			}
		});
	}

	void onDelivered(byte[] attachId) {
		Pending p = pending.remove(hex(attachId));
		if (p == null) return;
		ioExecutor.execute(() -> {
			try {
				messagingManager.setMeshMessageState(p.contactId, p.pmId,
						MessagingManager.MESH_STATE_DELIVERED);
			} catch (DbException e) {
			}
		});
	}

	private void retryPass() {
		if (meshManager.getPeerCount() == 0) return;
		for (Pending p : pending.values()) {
			if (++p.attempts > MAX_ATTEMPTS) {
				pending.remove(hex(p.attachId));
				continue;
			}
			ioExecutor.execute(() -> flood(p));
		}
	}

	private static String hex(byte[] b) {
		return StringUtils.toHexString(b);
	}
}
