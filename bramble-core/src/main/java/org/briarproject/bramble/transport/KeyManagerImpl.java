package org.briarproject.bramble.transport;

import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.contact.event.PendingContactRemovedEvent;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.TransportCrypto;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.PqRatchetState;
import org.briarproject.bramble.crypto.pcs.PcsStateManager;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventExecutor;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.Service;
import org.briarproject.bramble.api.lifecycle.ServiceException;
import org.briarproject.bramble.api.plugin.PluginConfig;
import org.briarproject.bramble.api.plugin.PluginFactory;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.transport.KeyManager;
import org.briarproject.bramble.api.transport.KeySetId;
import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.logging.Level.INFO;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MODE3_ENABLED;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_TRANSPORT_LATENCY;

@ThreadSafe
@NotNullByDefault
class KeyManagerImpl implements KeyManager, Service, EventListener {

	private static final Logger LOG =
			Logger.getLogger(KeyManagerImpl.class.getName());

	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final PluginConfig pluginConfig;
	private final TransportCrypto transportCrypto;
	private final PcsStateManager pcsStateManager;

	private final ConcurrentHashMap<TransportId, TransportKeyManager> managers;
	private final AtomicBoolean used = new AtomicBoolean(false);

	@Inject
	KeyManagerImpl(DatabaseComponent db,
			@DatabaseExecutor Executor dbExecutor,
			PluginConfig pluginConfig,
			TransportCrypto transportCrypto,
			TransportKeyManagerFactory transportKeyManagerFactory,
			PcsStateManager pcsStateManager) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.pluginConfig = pluginConfig;
		this.transportCrypto = transportCrypto;
		this.pcsStateManager = pcsStateManager;
		managers = new ConcurrentHashMap<>();
		for (PluginFactory<?> f : pluginConfig.getSimplexFactories()) {
			TransportKeyManager m = transportKeyManagerFactory.
					createTransportKeyManager(f.getId(), f.getMaxLatency());
			managers.put(f.getId(), m);
		}
		for (PluginFactory<?> f : pluginConfig.getDuplexFactories()) {
			TransportKeyManager m = transportKeyManagerFactory.
					createTransportKeyManager(f.getId(), f.getMaxLatency());
			managers.put(f.getId(), m);
		}
	}

	@Override
	public void startService() throws ServiceException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		try {
			db.transaction(false, txn -> {
				for (PluginFactory<?> f : pluginConfig.getSimplexFactories()) {
					addTransport(txn, f);
				}
				for (PluginFactory<?> f : pluginConfig.getDuplexFactories()) {
					addTransport(txn, f);
				}
			});
		} catch (DbException e) {
			throw new ServiceException(e);
		}
	}

	private void addTransport(Transaction txn, PluginFactory<?> f)
			throws DbException {
		long maxLatency = f.getMaxLatency();
		if (maxLatency > MAX_TRANSPORT_LATENCY) {
			throw new IllegalStateException();
		}
		db.addTransport(txn, f.getId(), maxLatency);
		managers.get(f.getId()).start(txn);
	}

	@Override
	public void stopService() {
	}

	@Override
	public KeySetId addRotationKeys(Transaction txn, ContactId c,
			TransportId t, SecretKey rootKey, long timestamp, boolean alice,
			boolean active) throws DbException {
		return withManager(t, m ->
				m.addRotationKeys(txn, c, rootKey, timestamp, alice, active));
	}

	@Override
	public Map<TransportId, KeySetId> addRotationKeys(Transaction txn,
			ContactId c, SecretKey rootKey, long timestamp, boolean alice,
			boolean active) throws DbException {
		Map<TransportId, KeySetId> ids = new HashMap<>();
		for (Entry<TransportId, TransportKeyManager> e : managers.entrySet()) {
			TransportId t = e.getKey();
			TransportKeyManager m = e.getValue();
			ids.put(t, m.addRotationKeys(txn, c, rootKey, timestamp,
					alice, active));
		}
		return ids;
	}

	@Override
	public Map<TransportId, KeySetId> addContact(Transaction txn, ContactId c,
			PublicKey theirPublicKey, KeyPair ourKeyPair)
			throws DbException, GeneralSecurityException {
		SecretKey staticMasterKey = transportCrypto
				.deriveStaticMasterKey(theirPublicKey, ourKeyPair);
		SecretKey rootKey =
				transportCrypto.deriveHandshakeRootKey(staticMasterKey, false);
		boolean alice = transportCrypto.isAlice(theirPublicKey, ourKeyPair);
		Map<TransportId, KeySetId> ids = new HashMap<>();
		for (Entry<TransportId, TransportKeyManager> e : managers.entrySet()) {
			TransportId t = e.getKey();
			TransportKeyManager m = e.getValue();
			ids.put(t, m.addHandshakeKeys(txn, c, rootKey, alice));
		}
		return ids;
	}

	@Override
	public Map<TransportId, KeySetId> addPendingContact(Transaction txn,
			PendingContactId p, PublicKey theirPublicKey, KeyPair ourKeyPair)
			throws DbException, GeneralSecurityException {
		SecretKey staticMasterKey = transportCrypto
				.deriveStaticMasterKey(theirPublicKey, ourKeyPair);
		SecretKey rootKey =
				transportCrypto.deriveHandshakeRootKey(staticMasterKey, true);
		boolean alice = transportCrypto.isAlice(theirPublicKey, ourKeyPair);
		Map<TransportId, KeySetId> ids = new HashMap<>();
		for (Entry<TransportId, TransportKeyManager> e : managers.entrySet()) {
			TransportId t = e.getKey();
			TransportKeyManager m = e.getValue();
			ids.put(t, m.addHandshakeKeys(txn, p, rootKey, alice));
		}
		return ids;
	}

	@Override
	public Map<TransportId, KeySetId> addHybridPendingContact(Transaction txn,
			PendingContactId p, SecretKey rendezvousKey, boolean alice)
			throws DbException {
		SecretKey rootKey =
				transportCrypto.deriveHandshakeRootKey(rendezvousKey, true);
		Map<TransportId, KeySetId> ids = new HashMap<>();
		for (Entry<TransportId, TransportKeyManager> e : managers.entrySet()) {
			TransportId t = e.getKey();
			TransportKeyManager m = e.getValue();
			ids.put(t, m.addHandshakeKeys(txn, p, rootKey, alice));
		}
		return ids;
	}

	@Override
	public void activateKeys(Transaction txn, Map<TransportId, KeySetId> keys)
			throws DbException {
		for (Entry<TransportId, KeySetId> e : keys.entrySet()) {
			withManager(e.getKey(), m -> {
				m.activateKeys(txn, e.getValue());
				return null;
			});
		}
	}

	@Override
	public boolean canSendOutgoingStreams(ContactId c, TransportId t) {
		TransportKeyManager m = managers.get(t);
		return m != null && m.canSendOutgoingStreams(c);
	}

	@Override
	public boolean canSendOutgoingStreams(PendingContactId p, TransportId t) {
		TransportKeyManager m = managers.get(t);
		return m != null && m.canSendOutgoingStreams(p);
	}

	@Override
	public StreamContext getStreamContext(ContactId c, TransportId t)
			throws DbException {
		return withManager(t, m ->
				db.transactionWithNullableResult(false, txn -> {
					Contact contact = db.getContact(txn, c);
					boolean classical = contact.isClassical();
					StreamContext baseCtx = m.getStreamContext(txn, c, classical);
					if (baseCtx == null) return null;
					return enrichWithPcsState(txn, baseCtx, c, contact.isMode3Capable());
				}));
	}

	@Override
	public StreamContext getStreamContext(PendingContactId p, TransportId t)
			throws DbException {
		return withManager(t, m ->
				db.transactionWithNullableResult(false, txn -> {
					org.briarproject.bramble.api.contact.PendingContact pending =
							db.getPendingContact(txn, p);
					boolean classical = !pending.isPostQuantum();
					return m.getStreamContext(txn, p, classical);
				}));
	}

	@Override
	public StreamContext getStreamContext(TransportId t, byte[] tag)
			throws DbException {
		return withManager(t, m ->
				db.transactionWithNullableResult(false, txn -> {
					StreamContext tempCtx = m.getStreamContextOnly(txn, tag, false);
					if (tempCtx == null) return null;

					boolean classical;
					boolean mode3Capable = false;
					if (tempCtx.getContactId() != null) {
						org.briarproject.bramble.api.contact.Contact contact =
								db.getContact(txn, tempCtx.getContactId());
						classical = contact.isClassical();
						mode3Capable = contact.isMode3Capable();
					} else if (tempCtx.getPendingContactId() != null) {
						org.briarproject.bramble.api.contact.PendingContact pending =
								db.getPendingContact(txn, tempCtx.getPendingContactId());
						classical = !pending.isPostQuantum();
					} else {
						classical = false;
					}
					StreamContext baseCtx = m.getStreamContext(txn, tag, classical);
					if (baseCtx == null) return null;
					if (baseCtx.getContactId() != null) {
						return enrichWithPcsReceiveState(txn, baseCtx,
								baseCtx.getContactId(), mode3Capable);
					}
					return baseCtx;
				}));
	}

	@Override
	public StreamContext getStreamContextOnly(TransportId t, byte[] tag)
			throws DbException {
		return withManager(t, m ->
				db.transactionWithNullableResult(false, txn -> {
					StreamContext tempCtx = m.getStreamContextOnly(txn, tag, false);
					if (tempCtx == null) return null;

					boolean classical;
					if (tempCtx.getContactId() != null) {
						org.briarproject.bramble.api.contact.Contact contact =
								db.getContact(txn, tempCtx.getContactId());
						classical = contact.isClassical();
					} else if (tempCtx.getPendingContactId() != null) {
						org.briarproject.bramble.api.contact.PendingContact pending =
								db.getPendingContact(txn, tempCtx.getPendingContactId());
						classical = !pending.isPostQuantum();
					} else {
						classical = false;
					}
					return m.getStreamContextOnly(txn, tag, classical);
				}));
	}

	@Override
	public void markTagAsRecognised(TransportId t, byte[] tag)
			throws DbException {
		withManager(t, m -> {
			db.transaction(false, txn -> m.markTagAsRecognised(txn, tag));
			return null;
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRemovedEvent) {
			removeContact(((ContactRemovedEvent) e).getContactId());
		} else if (e instanceof PendingContactRemovedEvent) {
			PendingContactRemovedEvent p = (PendingContactRemovedEvent) e;
			removePendingContact(p.getId());
		}
	}

	@EventExecutor
	private void removeContact(ContactId c) {
		dbExecutor.execute(() -> {
			for (TransportKeyManager m : managers.values()) m.removeContact(c);
		});
	}

	@EventExecutor
	private void removePendingContact(PendingContactId p) {
		dbExecutor.execute(() -> {
			for (TransportKeyManager m : managers.values())
				m.removePendingContact(p);
		});
	}

	@Nullable
	private <T> T withManager(TransportId t, ManagerTask<T> task)
			throws DbException {
		TransportKeyManager m = managers.get(t);
		if (m == null) {
			if (LOG.isLoggable(INFO)) LOG.info("No key manager for " + t);
			return null;
		}
		return task.run(m);
	}

	private interface ManagerTask<T> {
		@Nullable
		T run(TransportKeyManager m) throws DbException;
	}

	private StreamContext enrichWithPcsState(Transaction txn,
			StreamContext baseCtx, ContactId contactId, boolean mode3Capable)
			throws DbException {
		if (!MODE3_ENABLED || !mode3Capable) {
			return baseCtx;
		}
		if (!pcsStateManager.hasState(txn, contactId)) {
			return baseCtx;
		}
		PcsSessionState pcsState = pcsStateManager.loadSendState(txn, contactId);
		if (pcsState == null) {
			return baseCtx;
		}
		PqRatchetState pqState = null;
		if (pcsState.isMode2()) {
			pcsState = pcsState.enableMode3();
			pqState = pcsStateManager.loadPqState(txn, contactId);
		}
		return new StreamContext(
				baseCtx.getContactId(),
				baseCtx.getPendingContactId(),
				baseCtx.getTransportId(),
				baseCtx.getTagKey(),
				baseCtx.getHeaderKey(),
				baseCtx.getStreamNumber(),
				baseCtx.isHandshakeMode(),
				baseCtx.isClassical(),
				true,
				pcsState,
				pqState);
	}

	private StreamContext enrichWithPcsReceiveState(Transaction txn,
			StreamContext baseCtx, ContactId contactId, boolean mode3Capable)
			throws DbException {
		if (!MODE3_ENABLED || !mode3Capable) {
			return baseCtx;
		}
		if (!pcsStateManager.hasState(txn, contactId)) {
			return baseCtx;
		}
		PcsSessionState pcsState = pcsStateManager.loadReceiveState(txn, contactId);
		if (pcsState == null) {
			return baseCtx;
		}
		PqRatchetState pqState = null;
		if (pcsState.isMode2()) {
			pcsState = pcsState.enableMode3();
			pqState = pcsStateManager.loadPqState(txn, contactId);
		}
		return new StreamContext(
				baseCtx.getContactId(),
				baseCtx.getPendingContactId(),
				baseCtx.getTransportId(),
				baseCtx.getTagKey(),
				baseCtx.getHeaderKey(),
				baseCtx.getStreamNumber(),
				baseCtx.isHandshakeMode(),
				baseCtx.isClassical(),
				true,
				pcsState,
				pqState);
	}
}
