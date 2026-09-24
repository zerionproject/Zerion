package org.zerionproject.core.transport;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.contact.event.PendingContactRemovedEvent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventExecutor;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.lifecycle.ServiceException;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.PluginFactory;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.StreamContext;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_TRANSPORT_LATENCY;

@ThreadSafe
@NotNullByDefault
class KeyManagerImpl implements KeyManager, Service, EventListener {
	private final DatabaseComponent db;
	private final Executor dbExecutor;
	private final PluginConfig pluginConfig;
	private final TransportCrypto transportCrypto;

	private final ConcurrentHashMap<TransportId, TransportKeyManager> managers;
	private final AtomicBoolean used = new AtomicBoolean(false);

	@Inject
	KeyManagerImpl(DatabaseComponent db,
			@DatabaseExecutor Executor dbExecutor,
			PluginConfig pluginConfig,
			TransportCrypto transportCrypto,
			TransportKeyManagerFactory transportKeyManagerFactory) {
		this.db = db;
		this.dbExecutor = dbExecutor;
		this.pluginConfig = pluginConfig;
		this.transportCrypto = transportCrypto;
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
		managers.clear();
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

	/**
	 * An established contact never gets a rotation-key stream context: every
	 * contact session runs over the ZWF ratchet, and the classical sync
	 * connections that used these contexts have no caller. Handing one out
	 * would let a future caller move contact traffic to classical keys
	 * without a ratchet, so the request is refused rather than served.
	 */
	@Override
	@Nullable
	public StreamContext getStreamContext(ContactId c, TransportId t) {
		return null;
	}

	@Override
	public StreamContext getStreamContext(PendingContactId p, TransportId t)
			throws DbException {
		return withManager(t, m ->
				db.transactionWithNullableResult(false, txn -> {
					org.zerionproject.core.api.contact.PendingContact pending =
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
					if (tempCtx.getContactId() != null) {
						return null;
					} else if (tempCtx.getPendingContactId() != null) {
						org.zerionproject.core.api.contact.PendingContact pending =
								db.getPendingContact(txn, tempCtx.getPendingContactId());
						classical = !pending.isPostQuantum();
					} else {
						classical = false;
					}
					return m.getStreamContext(txn, tag, classical);
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
						org.zerionproject.core.api.contact.Contact contact =
								db.getContact(txn, tempCtx.getContactId());
						classical = contact.isClassical();
					} else if (tempCtx.getPendingContactId() != null) {
						org.zerionproject.core.api.contact.PendingContact pending =
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
			return null;
		}
		return task.run(m);
	}

	private interface ManagerTask<T> {
		@Nullable
		T run(TransportKeyManager m) throws DbException;
	}

}
