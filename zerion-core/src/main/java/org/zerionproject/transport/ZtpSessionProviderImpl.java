package org.zerionproject.transport;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactAddedEvent;
import org.zerionproject.core.api.contact.event.ContactConnectionKeysEvent;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;

@ThreadSafe
@NotNullByDefault
@Singleton
public class ZtpSessionProviderImpl implements ZtpSessionProvider, Service,
		EventListener, RootKeyStore.Listener {

	private final ContactManager contactManager;
	private final PcsStateManager pcsStateManager;
	private final ZwfSessionFactory sessionFactory;
	private final ZwfStreamCounter counter;
	private final DatabaseComponent db;
	private final EventBus eventBus;
	private final Executor dbExecutor;

	private final ZwfTagRecogniser recogniser;
	private final javax.inject.Provider<org.zerionproject.core.plugin.tor
			.B4OnionRotation> onionRotation;
	@Nullable
	private final RootKeyStore rootKeyStore;

	static final long INBOUND_SEARCH_GAP = 1L << 14;
	static final long INBOUND_SEARCH_INTERVAL_MS = 10_000L;
	private final java.util.concurrent.atomic.AtomicLong nextSearchAtMs =
			new java.util.concurrent.atomic.AtomicLong(0);
	final java.util.Set<Integer> peerDials =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

	@Inject
	public ZtpSessionProviderImpl(CryptoComponent crypto,
			ContactManager contactManager,
			PcsStateManager pcsStateManager, ZwfSessionFactory sessionFactory,
			ZwfStreamCounter counter, DatabaseComponent db, EventBus eventBus,
			@DatabaseExecutor Executor dbExecutor,
			javax.inject.Provider<org.zerionproject.core.plugin.tor
					.B4OnionRotation> onionRotation,
			RootKeyStore rootKeyStore) {
		this(new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE), contactManager,
				pcsStateManager, sessionFactory, counter, db, eventBus,
				dbExecutor, onionRotation, rootKeyStore);
	}

	ZtpSessionProviderImpl(ZwfTagRecogniser recogniser,
			ContactManager contactManager,
			PcsStateManager pcsStateManager, ZwfSessionFactory sessionFactory,
			ZwfStreamCounter counter, DatabaseComponent db, EventBus eventBus,
			Executor dbExecutor,
			javax.inject.Provider<org.zerionproject.core.plugin.tor
					.B4OnionRotation> onionRotation) {
		this(recogniser, contactManager, pcsStateManager, sessionFactory,
				counter, db, eventBus, dbExecutor, onionRotation, null);
	}

	ZtpSessionProviderImpl(ZwfTagRecogniser recogniser,
			ContactManager contactManager,
			PcsStateManager pcsStateManager, ZwfSessionFactory sessionFactory,
			ZwfStreamCounter counter, DatabaseComponent db, EventBus eventBus,
			Executor dbExecutor,
			javax.inject.Provider<org.zerionproject.core.plugin.tor
					.B4OnionRotation> onionRotation,
			@Nullable RootKeyStore rootKeyStore) {
		this.rootKeyStore = rootKeyStore;
		this.onionRotation = onionRotation;
		this.contactManager = contactManager;
		this.pcsStateManager = pcsStateManager;
		this.sessionFactory = sessionFactory;
		this.counter = counter;
		this.db = db;
		this.eventBus = eventBus;
		this.dbExecutor = dbExecutor;
		this.recogniser = recogniser;
	}

	@Override
	public void startService() throws ServiceException {
		eventBus.addListener(this);
		if (rootKeyStore != null) rootKeyStore.addListener(this);
		try {
			Collection<Contact> contacts = contactManager.getContacts();
			for (Contact c : contacts) {
				pcsStateManager.stripDeadState(c.getId());
				registerContact(c.getId());
			}
		} catch (DbException e) {
			throw new ServiceException(e);
		}
	}

	@Override
	public void stopService() {
		eventBus.removeListener(this);
		if (rootKeyStore != null) rootKeyStore.removeListener(this);
	}

	@Override
	public void rootKeysChanged(ContactId c) {
		dbExecutor.execute(() -> registerContact(c));
	}

	@Override
	public int recogniseIncoming(byte[] tag) {
		ZwfTagRecogniser.Match m = recogniser.recognise(tag);
		if (m != null) return m.contactId;
		long now = clock.getAsLong();
		long next = nextSearchAtMs.get();
		if (now < next) return -1;
		if (!nextSearchAtMs.compareAndSet(next,
				now + INBOUND_SEARCH_INTERVAL_MS)) {
			return -1;
		}
		m = recogniser.recogniseBeyondWindowNext(tag, INBOUND_SEARCH_GAP,
				peerDials);
		return m == null ? -1 : m.contactId;
	}

	@Override
	public void sessionEstablished(int contactId) {
		ContactId cid = new ContactId(contactId);
		dbExecutor.execute(() -> {
			try {
				onionRotation.get().onPeerSyncSessionEstablished(cid);
			} catch (DbException | RuntimeException ignored) {
			}
		});
	}

	@Override
	@Nullable
	public StoredContactSession getStoredSession(int contactId) {
		ContactId cid = new ContactId(contactId);
		long generation = counter.generation(contactId);
		ContactRootKeys keys = loadRootKeys(cid);
		if (keys == null) return null;
		Boolean alice = computeAlice(cid);
		if (alice == null) return null;
		return new StoredContactSession(keys, alice, generation);
	}

	@Nullable
	private ContactRootKeys loadRootKeys(ContactId cid) {
		if (rootKeyStore != null) return rootKeyStore.load(cid);
		PcsSessionState send = pcsStateManager.loadSendState(cid);
		if (send == null) return null;
		SecretKey rootKey = send.getRootKey();
		return rootKey == null ? null : ContactRootKeys.atPairing(rootKey);
	}

	@Override
	public void sessionClosed(int contactId) {
		recogniser.advanceTo(contactId,
				counter.currentRecvHighWater(contactId));
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactAddedEvent) {
			ContactId cid = ((ContactAddedEvent) e).getContactId();
			dbExecutor.execute(() -> registerContact(cid));
		} else if (e instanceof ContactRemovedEvent) {
			ContactId cid = ((ContactRemovedEvent) e).getContactId();
			recogniser.remove(cid.getInt());
			peerDials.remove(cid.getInt());
			dbExecutor.execute(this::rotateOnionAfterContactRemoval);
		} else if (e instanceof ContactConnectionKeysEvent) {
			ContactId cid = ((ContactConnectionKeysEvent) e).getContactId();
			if (!((ContactConnectionKeysEvent) e).isOutOfSync()) {
				dbExecutor.execute(() -> registerContact(cid));
			}
		}
	}

	private void rotateOnionAfterContactRemoval() {
		try {
			onionRotation.get().revokeAfterContactRemoval();
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private void registerContact(ContactId cid) {
		ContactRootKeys keys = loadRootKeys(cid);
		if (keys == null) {
			recogniser.remove(cid.getInt());
			peerDials.remove(cid.getInt());
			return;
		}
		Boolean alice = computeAlice(cid);
		if (alice == null) {
			return;
		}
		if (alice) peerDials.remove(cid.getInt());
		else peerDials.add(cid.getInt());
		Map<Long, SecretKey> tagKeys = new LinkedHashMap<>();
		long[] epochs = {keys.getEpoch(), keys.getPendingEpoch()};
		for (long e : epochs) {
			SecretKey root = keys.getKey(e);
			if (root != null) {
				tagKeys.put(e, sessionFactory.deriveRecvTagKey(root, e, alice));
			}
		}
		keys.getCurrent().clear();
		SecretKey pending = keys.getPending();
		if (pending != null) pending.clear();
		long recvHighWater = counter.currentRecvHighWater(cid.getInt());
		recogniser.register(cid.getInt(), tagKeys, recvHighWater);
	}

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
