package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.MeshBundleStore;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.PrekeyBundleReceivedEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.app.api.messaging.MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION;

@Singleton
@NotNullByDefault
public class MeshBundleManager implements EventListener {

	private static final long RESEND_INTERVAL_MS = 60 * 60 * 1000;

	private final MeshBundleStore store;
	private final CryptoComponent crypto;
	private final ContactManager contactManager;
	private final MeshManager meshManager;
	private final MessagingManager messagingManager;
	private final DatabaseComponent db;
	private final Executor ioExecutor;
	private final Map<Integer, Long> lastSent = new ConcurrentHashMap<>();

	@Inject
	MeshBundleManager(EventBus eventBus, MeshBundleStore store,
			CryptoComponent crypto, ContactManager contactManager,
			MeshManager meshManager, MessagingManager messagingManager,
			DatabaseComponent db, @IoExecutor Executor ioExecutor) {
		this.store = store;
		this.crypto = crypto;
		this.contactManager = contactManager;
		this.meshManager = meshManager;
		this.messagingManager = messagingManager;
		this.db = db;
		this.ioExecutor = ioExecutor;
		eventBus.addListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof PrekeyBundleReceivedEvent) {
			PrekeyBundleReceivedEvent p = (PrekeyBundleReceivedEvent) e;
			ContactId c = p.getContactId();
			byte[] bundle = p.getBundle();
			ioExecutor.execute(() -> storeBundle(c, bundle));
		} else if (e instanceof ContactConnectedEvent) {
			ContactId c = ((ContactConnectedEvent) e).getContactId();
			ioExecutor.execute(() -> maybeSendOurBundle(c));
		}
	}

	private void storeBundle(ContactId contactId, byte[] bundleBytes) {
		try {
			AsyncPrekeyBundle bundle = AsyncPrekeyBundle.decode(bundleBytes);
			Contact c = contactManager.getContact(contactId);
			byte[] identitySigPub = MeshBundleStore.identityOf(c);
			if (identitySigPub == null) return;
			if (!bundle.verifyFromContact(crypto, identitySigPub)) {
				return;
			}
			store.putContactBundle(contactId.getInt(), bundleBytes);
		} catch (Exception e) {
		}
	}

	private void maybeSendOurBundle(ContactId contactId) {
		Long last = lastSent.get(contactId.getInt());
		long now = System.currentTimeMillis();
		if (last != null && now - last < RESEND_INTERVAL_MS) return;
		try {
			boolean signed = !contactAcceptsUnsignedBundle(contactId);
			AsyncPrekeyBundle bundle =
					meshManager.publishBundle(contactId, signed);
			messagingManager.sendPrekeyBundle(contactId, bundle.encode());
			lastSent.put(contactId.getInt(), now);
		} catch (Exception e) {
		}
	}

	private boolean contactAcceptsUnsignedBundle(ContactId contactId)
			throws DbException {
		int minor = db.transactionWithResult(true, txn ->
				messagingManager.getContactClientMinorVersion(txn, contactId));
		return minor >= MESH_SENDER_AUTH_V2_MIN_VERSION;
	}
}
