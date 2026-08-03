package org.zerionproject.transport;

import org.zerionproject.core.crypto.pcs.PcsPersistenceException;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.lifecycle.ServiceException;
import org.zerionproject.core.crypto.pcs.PcsStateManager;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.wire.ZwfStreamCounter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

/**
 * Bridges the transport to the contact/identity database: it recognises an
 * incoming stream tag to a contact, loads the stored inputs to resume a
 * contact's session, and persists the post-quantum ratchet state after a
 * connection ends.
 *
 * <p>A single tag recogniser is seeded with every established contact at startup
 * and kept current as contacts are added and removed, so an anonymous incoming
 * connection can be attributed to a contact by its first stream tag. A contact's
 * role is recomputed deterministically from the two author ids, so it never has
 * to be stored separately.
 */
@ThreadSafe
@NotNullByDefault
@Singleton
public class ZtpSessionProviderImpl
		implements ZtpSessionProvider, Service, EventListener {

	private final ContactManager contactManager;
	private final PcsStateManager pcsStateManager;
	private final ZwfSessionFactory sessionFactory;
	private final ZwfStreamCounter counter;
	private final DatabaseComponent db;
	private final EventBus eventBus;
	private final Executor dbExecutor;

	private final ZwfTagRecogniser recogniser;

	@Inject
	public ZtpSessionProviderImpl(CryptoComponent crypto,
			ContactManager contactManager,
			PcsStateManager pcsStateManager, ZwfSessionFactory sessionFactory,
			ZwfStreamCounter counter, DatabaseComponent db, EventBus eventBus,
			@DatabaseExecutor Executor dbExecutor) {
		this.contactManager = contactManager;
		this.pcsStateManager = pcsStateManager;
		this.sessionFactory = sessionFactory;
		this.counter = counter;
		this.db = db;
		this.eventBus = eventBus;
		this.dbExecutor = dbExecutor;
		this.recogniser = new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
	}

	@Override
	public void startService() throws ServiceException {
		eventBus.addListener(this);
		try {
			Collection<Contact> contacts = contactManager.getContacts();
			for (Contact c : contacts) {
				registerContact(c.getId());
			}
		} catch (DbException e) {
			throw new ServiceException(e);
		}
	}

	@Override
	public void stopService() {
		eventBus.removeListener(this);
	}

	@Override
	public int recogniseIncoming(byte[] tag) {
		ZwfTagRecogniser.Match m = recogniser.recognise(tag);
		return m == null ? -1 : m.contactId;
	}

	@Override
	@Nullable
	public StoredContactSession getStoredSession(int contactId) {
		ContactId cid = new ContactId(contactId);
		PcsSessionState send = pcsStateManager.loadSendState(cid);
		if (send == null) return null;
		SecretKey rootKey = send.getRootKey();
		if (rootKey == null) return null;
		Mode3FullState m3f = pcsStateManager.loadSharedMode3FullState(cid);
		if (m3f == null) return null;
		Boolean alice = computeAlice(cid);
		if (alice == null) return null;
		return new StoredContactSession(rootKey, alice, m3f);
	}

	@Override
	public void saveMode3FullState(int contactId, Mode3FullState state) {
		recogniser.advanceTo(contactId,
				counter.currentRecvHighWater(contactId));
		ContactId cid = new ContactId(contactId);
		PcsSessionState send = pcsStateManager.loadSendState(cid);
		if (send == null) return;
		try {
			pcsStateManager.saveSendState(cid, send.withMode3FullState(state));
		} catch (PcsPersistenceException ignored) {
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactAddedEvent) {
			ContactId cid = ((ContactAddedEvent) e).getContactId();
			dbExecutor.execute(() -> registerContact(cid));
		} else if (e instanceof ContactRemovedEvent) {
			ContactId cid = ((ContactRemovedEvent) e).getContactId();
			recogniser.remove(cid.getInt());
		}
	}

	/** Seeds the recogniser for one contact that has an established session. */
	private void registerContact(ContactId cid) {
		PcsSessionState send = pcsStateManager.loadSendState(cid);
		if (send == null) {
			return;
		}
		SecretKey rootKey = send.getRootKey();
		if (rootKey == null) {
			return;
		}
		Boolean alice = computeAlice(cid);
		if (alice == null) {
			return;
		}
		SecretKey recvTagKey = sessionFactory.deriveRecvTagKey(rootKey, alice);
		long recvHighWater = counter.currentRecvHighWater(cid.getInt());
		recogniser.register(cid.getInt(), recvTagKey, recvHighWater);
	}

	/**
	 * Determines our role for a contact deterministically from the two author
	 * ids: alice is the endpoint whose author id sorts first. Both endpoints
	 * compare the same pair of ids, so they always agree and always pick opposite
	 * roles. Only the role's opposition matters: every per-direction key is
	 * derived as {@code deriveKey(alice ? A : B, rootKey)}, so one endpoint's
	 * send-side material equals the other's receive-side material whenever the
	 * two roles differ, regardless of which endpoint is alice.
	 */
	@Nullable
	private Boolean computeAlice(ContactId cid) {
		try {
			return db.transactionWithNullableResult(true, txn -> {
				Contact contact = contactManager.getContact(txn, cid);
				byte[] ourId = contact.getLocalAuthorId().getBytes();
				byte[] theirId = contact.getAuthor().getId().getBytes();
				return Bytes.compare(ourId, theirId) < 0;
			});
		} catch (DbException e) {
			return null;
		}
	}
}
