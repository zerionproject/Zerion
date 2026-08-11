package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
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

/**
 * Distributes async prekey bundles between contacts over the encrypted channel
 * (Tor/I2P), so a contact can seal offline mesh messages to us and we to them,
 * with no identity broadcast. When a contact connects online we send them our
 * current bundle (throttled); when one of theirs arrives we verify its
 * signatures and that it belongs to that contact, then store it.
 */
@Singleton
@NotNullByDefault
public class MeshBundleManager implements EventListener {

	private static final long RESEND_INTERVAL_MS = 60 * 60 * 1000;

	private final MeshBundleStore store;
	private final CryptoComponent crypto;
	private final ContactManager contactManager;
	private final MeshManager meshManager;
	private final MessagingManager messagingManager;
	private final Executor ioExecutor;
	private final Map<Integer, Long> lastSent = new ConcurrentHashMap<>();

	@Inject
	MeshBundleManager(EventBus eventBus, MeshBundleStore store,
			CryptoComponent crypto, ContactManager contactManager,
			MeshManager meshManager, MessagingManager messagingManager,
			@IoExecutor Executor ioExecutor) {
		this.store = store;
		this.crypto = crypto;
		this.contactManager = contactManager;
		this.meshManager = meshManager;
		this.messagingManager = messagingManager;
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
			if (!bundle.verify(crypto)) {
				return;
			}
			Contact c = contactManager.getContact(contactId);
			byte[] ed = c.getAuthor().getPublicKey().getEncoded();
			byte[] mlDsa = c.getMlDsaSigPublicKey();
			if (mlDsa == null) return;
			byte[] identitySigPub =
					new HybridSignaturePublicKey(ed, mlDsa).getEncoded();
			if (!MeshBundleStore.matchesIdentity(bundle, identitySigPub)) {
				return; // bundle claims another identity; reject
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
			AsyncPrekeyBundle bundle = meshManager.publishBundle();
			messagingManager.sendPrekeyBundle(contactId, bundle.encode());
			lastSent.put(contactId.getInt(), now);
		} catch (Exception e) {
		}
	}
}
