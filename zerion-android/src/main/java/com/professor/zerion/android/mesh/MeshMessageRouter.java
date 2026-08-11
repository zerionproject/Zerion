package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.crypto.async.MeshSeenStore;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.messaging.MessagingManager;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executor;

import org.zerionproject.core.util.StringUtils;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import static java.nio.charset.StandardCharsets.UTF_8;

@Singleton
@NotNullByDefault
public class MeshMessageRouter implements MeshManager.OpenedHandler {

	public static final int MESH_TEXT = 1;
	public static final int MESH_ACK = 2;
	public static final int MESH_PRESENCE = 4;
	public static final int MESH_GROUP_RECORD = 5;
	public static final int MESH_ATTACH_MANIFEST = 6;
	public static final int MESH_ATTACH_CHUNK = 7;
	public static final int MESH_ATTACH_ACK = 8;

	private static final int MAX_REASSEMBLY = 8;
	private static final long REASSEMBLY_TTL_MS = 5 * 60_000;
	private static final int MAX_CHUNK_DATA_BYTES =
			MeshPadding.MAX_DATA_BYTES - MeshAttachmentSender.CHUNK_HEADER_BYTES;

	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final MeshSeenStore seenStore;
	private final Provider<MeshTextSender> textSender;
	private final MeshPresenceTracker presenceTracker;
	private final Provider<MeshAttachmentSender> attachmentSender;
	private final Executor ioExecutor;
	private final java.util.Map<Integer, byte[]> hybridPubCache =
			new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String, Reassembly> reassemblies =
			new java.util.concurrent.ConcurrentHashMap<>();

	@Inject
	MeshMessageRouter(ContactManager contactManager,
			MessagingManager messagingManager, MeshSeenStore seenStore,
			Provider<MeshTextSender> textSender,
			MeshPresenceTracker presenceTracker,
			Provider<MeshAttachmentSender> attachmentSender,
			@IoExecutor Executor ioExecutor) {
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
		this.seenStore = seenStore;
		this.textSender = textSender;
		this.presenceTracker = presenceTracker;
		this.attachmentSender = attachmentSender;
		this.ioExecutor = ioExecutor;
	}

	private static final class Reassembly {
		final ContactId contactId;
		final String contentType;
		final long composeMs;
		final int totalSize;
		final byte[][] chunks;
		final long firstSeenMs;
		int received;
		int accumulated;

		Reassembly(ContactId contactId, String contentType, long composeMs,
				int totalSize, int chunkCount, long firstSeenMs) {
			this.contactId = contactId;
			this.contentType = contentType;
			this.composeMs = composeMs;
			this.totalSize = totalSize;
			this.chunks = new byte[chunkCount][];
			this.firstSeenMs = firstSeenMs;
		}
	}

	@Override
	public boolean onOfflineMessage(byte[] senderIdentitySigPub,
			int messageType, byte[] payload, long sendTimestamp) {
		ContactId contactId;
		try {
			contactId = resolveContact(senderIdentitySigPub);
		} catch (DbException e) {
			return false;
		}
		if (contactId == null) return false;
		presenceTracker.markPresent(contactId);
		byte[] body = MeshPadding.unpad(payload);
		if (messageType == MESH_TEXT) {
			handleText(contactId, body);
			return true;
		}
		if (messageType == MESH_ACK) {
			handleAck(contactId, body);
			return true;
		}
		if (messageType == MESH_GROUP_RECORD) {
			handleGroupPost(contactId, body);
			return true;
		}
		if (messageType == MESH_ATTACH_MANIFEST) {
			handleManifest(contactId, body);
			return true;
		}
		if (messageType == MESH_ATTACH_CHUNK) {
			handleChunk(contactId, body);
			return true;
		}
		if (messageType == MESH_ATTACH_ACK) {
			handleAttachAck(contactId, body);
			return true;
		}
		return false;
	}

	private void handleManifest(ContactId contactId, byte[] body) {
		if (body.length < MeshAttachmentSender.MANIFEST_HEADER_BYTES) return;
		int o = MeshAttachmentSender.ATTACH_ID_BYTES;
		byte[] attachId = Arrays.copyOfRange(body, 0, o);
		long composeMs = readLong(body, o);
		o += 8;
		int totalSize = ((body[o] & 0xFF) << 24) | ((body[o + 1] & 0xFF) << 16)
				| ((body[o + 2] & 0xFF) << 8) | (body[o + 3] & 0xFF);
		o += 4;
		int chunkCount = ((body[o] & 0xFF) << 8) | (body[o + 1] & 0xFF);
		o += 2;
		int ctLen = body[o] & 0xFF;
		o += 1;
		if (chunkCount <= 0 || chunkCount > 4096 || ctLen <= 0
				|| ctLen > MeshAttachmentSender.MAX_CONTENT_TYPE_BYTES
				|| totalSize <= 0
				|| totalSize > MeshAttachmentSender.MAX_MESH_PHOTO_BYTES
				|| body.length < o + ctLen) {
			return;
		}
		String contentType = new String(body, o, ctLen, UTF_8);
		String key = StringUtils.toHexString(attachId);
		long now = System.currentTimeMillis();
		evictStaleReassemblies(now);
		if (!reassemblies.containsKey(key)
				&& reassemblies.size() >= MAX_REASSEMBLY) {
			return;
		}
		reassemblies.computeIfAbsent(key, k -> new Reassembly(contactId,
				contentType, composeMs, totalSize, chunkCount, now));
	}

	private void handleChunk(ContactId contactId, byte[] body) {
		if (body.length < MeshAttachmentSender.CHUNK_HEADER_BYTES) return;
		int o = MeshAttachmentSender.ATTACH_ID_BYTES;
		byte[] attachId = Arrays.copyOfRange(body, 0, o);
		int index = ((body[o] & 0xFF) << 8) | (body[o + 1] & 0xFF);
		o += 2;
		int dataLen = body.length - o;
		if (dataLen > MAX_CHUNK_DATA_BYTES) return;
		String key = StringUtils.toHexString(attachId);
		Reassembly r = reassemblies.get(key);
		if (r == null) return;
		synchronized (r) {
			if (index < 0 || index >= r.chunks.length) return;
			if (r.chunks[index] != null) return;
			if (r.accumulated + dataLen > r.totalSize) {
				reassemblies.remove(key);
				return;
			}
			r.chunks[index] = Arrays.copyOfRange(body, o, body.length);
			r.accumulated += dataLen;
			r.received++;
			if (r.received < r.chunks.length) return;
		}
		reassemblies.remove(key);
		byte[] assembled = join(r.chunks, r.totalSize);
		if (assembled == null) return;
		ioExecutor.execute(() -> {
			boolean seen;
			try {
				seen = seenStore.checkAndMark(attachId);
			} catch (DbException e) {
				return;
			}
			if (seen) {
				attachmentSender.get().sendAck(contactId, attachId);
				return;
			}
			long now = System.currentTimeMillis();
			long ts = (r.composeMs > 0 && r.composeMs <= now + 60_000)
					? r.composeMs : now;
			try {
				messagingManager.receiveMeshAttachment(contactId,
						r.contentType, assembled, ts);
			} catch (DbException e) {
				try {
					seenStore.unmark(attachId);
				} catch (DbException ignored) {
				}
				return;
			}
			attachmentSender.get().sendAck(contactId, attachId);
		});
	}

	private void handleAttachAck(ContactId contactId, byte[] body) {
		if (body.length != MeshAttachmentSender.ATTACH_ID_BYTES) return;
		attachmentSender.get().onDelivered(body);
	}

	@Nullable
	private static byte[] join(byte[][] chunks, int totalSize) {
		byte[] out = new byte[totalSize];
		int pos = 0;
		for (byte[] c : chunks) {
			if (c == null) return null;
			if (pos + c.length > totalSize) return null;
			System.arraycopy(c, 0, out, pos, c.length);
			pos += c.length;
		}
		return pos == totalSize ? out : null;
	}

	private void evictStaleReassemblies(long now) {
		for (Map.Entry<String, Reassembly> e : reassemblies.entrySet()) {
			if (now - e.getValue().firstSeenMs > REASSEMBLY_TTL_MS) {
				reassemblies.remove(e.getKey());
			}
		}
	}

	private void handleText(ContactId contactId, byte[] body) {
		if (body.length < MeshTextSender.HEADER_BYTES + 1) return;
		byte[] messageId = Arrays.copyOfRange(body, 0,
				MeshTextSender.MESSAGE_ID_BYTES);
		long composeMs = readLong(body, MeshTextSender.MESSAGE_ID_BYTES);
		int parentLen = body[MeshTextSender.HEADER_BYTES] & 0xFF;
		if (parentLen != 0 && parentLen != MeshTextSender.MESSAGE_ID_BYTES) {
			return;
		}
		int textOff = MeshTextSender.HEADER_BYTES + 1 + parentLen;
		if (body.length < textOff) return;
		byte[] parentId = parentLen == 0 ? null : Arrays.copyOfRange(body,
				MeshTextSender.HEADER_BYTES + 1, textOff);
		String text = new String(body, textOff, body.length - textOff, UTF_8);
		long now = System.currentTimeMillis();
		long ts = (composeMs > 0 && composeMs <= now + 60_000) ? composeMs : now;
		ioExecutor.execute(() -> {
			boolean seen;
			try {
				seen = seenStore.checkAndMark(messageId);
			} catch (DbException e) {
				return;
			}
			if (seen) {
				textSender.get().sendAck(contactId, messageId);
				return;
			}
			try {
				messagingManager.receiveMeshMessage(contactId, text, ts,
						messageId, parentId);
			} catch (DbException e) {
				try {
					seenStore.unmark(messageId);
				} catch (DbException ignored) {
				}
				return;
			}
			textSender.get().sendAck(contactId, messageId);
		});
	}

	private static long readLong(byte[] b, int off) {
		long v = 0;
		for (int i = 0; i < 8; i++) {
			v = (v << 8) | (b[off + i] & 0xFFL);
		}
		return v;
	}

	private void handleAck(ContactId contactId, byte[] body) {
		if (body.length != MeshTextSender.MESSAGE_ID_BYTES) return;
		MessageId messageId = new MessageId(body);
		ioExecutor.execute(() ->
				textSender.get().onDelivered(contactId, messageId));
	}

	private void handleGroupPost(ContactId contactId, byte[] body) {
		if (body.length <= MeshTextSender.TIMESTAMP_BYTES) return;
		long composeMs = readLong(body, 0);
		if (composeMs <= 0) return;
		byte[] record = Arrays.copyOfRange(body,
				MeshTextSender.TIMESTAMP_BYTES, body.length);
		byte[] dedupId = digest(record);
		if (dedupId == null) return;
		ioExecutor.execute(() -> {
			boolean seen;
			try {
				seen = seenStore.checkAndMark(dedupId);
			} catch (DbException e) {
				return;
			}
			if (seen) return;
			try {
				messagingManager.receiveMeshGroupRecord(contactId, record,
						composeMs);
			} catch (DbException e) {
				try {
					seenStore.unmark(dedupId);
				} catch (DbException ignored) {
				}
			}
		});
	}

	@Nullable
	private static byte[] digest(byte[] data) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256")
					.digest(data);
		} catch (java.security.NoSuchAlgorithmException e) {
			return null;
		}
	}

	@Nullable
	private ContactId resolveContact(byte[] senderIdentitySigPub)
			throws DbException {
		for (Contact contact : contactManager.getContacts()) {
			int id = contact.getId().getInt();
			byte[] hybrid = hybridPubCache.get(id);
			if (hybrid == null) {
				byte[] mlDsa = contact.getMlDsaSigPublicKey();
				if (mlDsa == null) continue;
				byte[] ed25519 =
						contact.getAuthor().getPublicKey().getEncoded();
				hybrid = new HybridSignaturePublicKey(ed25519, mlDsa)
						.getEncoded();
				hybridPubCache.put(id, hybrid);
			}
			if (Arrays.equals(hybrid, senderIdentitySigPub)) {
				return contact.getId();
			}
		}
		return null;
	}
}
