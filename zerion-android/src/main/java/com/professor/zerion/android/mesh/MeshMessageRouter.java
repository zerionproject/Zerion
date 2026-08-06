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
import java.util.concurrent.Executor;

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

	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final MeshSeenStore seenStore;
	private final Provider<MeshTextSender> textSender;
	private final MeshPresenceTracker presenceTracker;
	private final Executor ioExecutor;
	private final java.util.Map<Integer, byte[]> hybridPubCache =
			new java.util.concurrent.ConcurrentHashMap<>();

	@Inject
	MeshMessageRouter(ContactManager contactManager,
			MessagingManager messagingManager, MeshSeenStore seenStore,
			Provider<MeshTextSender> textSender,
			MeshPresenceTracker presenceTracker,
			@IoExecutor Executor ioExecutor) {
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
		this.seenStore = seenStore;
		this.textSender = textSender;
		this.presenceTracker = presenceTracker;
		this.ioExecutor = ioExecutor;
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
		return false;
	}

	private void handleText(ContactId contactId, byte[] body) {
		if (body.length < MeshTextSender.HEADER_BYTES) return;
		byte[] messageId = Arrays.copyOfRange(body, 0,
				MeshTextSender.MESSAGE_ID_BYTES);
		long composeMs = readLong(body, MeshTextSender.MESSAGE_ID_BYTES);
		String text = new String(body, MeshTextSender.HEADER_BYTES,
				body.length - MeshTextSender.HEADER_BYTES, UTF_8);
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
				messagingManager.receiveMeshMessage(contactId, text, ts);
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
