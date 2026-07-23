package org.zerionproject.core.rendezvous;

import org.zerionproject.core.PoliteExecutor;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.connection.ConnectionManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.event.PendingContactAddedEvent;
import org.zerionproject.core.api.contact.event.PendingContactRemovedEvent;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventExecutor;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.Service;
import org.zerionproject.core.api.lifecycle.ServiceException;
import org.zerionproject.core.api.plugin.ConnectionHandler;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.plugin.event.TransportActiveEvent;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.zerionproject.core.api.rendezvous.RendezvousEndpoint;
import org.zerionproject.core.api.rendezvous.RendezvousPoller;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionClosedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionOpenedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousPollEvent;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.system.Wakeful;
import org.zerionproject.core.api.transport.KeyManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_BYTES;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_RENDEZVOUS_X25519_BYTES;
import static org.zerionproject.core.api.contact.PendingContactState.ADDING_CONTACT;
import static org.zerionproject.core.api.contact.PendingContactState.FAILED;
import static org.zerionproject.core.api.contact.PendingContactState.OFFLINE;
import static org.zerionproject.core.api.contact.PendingContactState.WAITING_FOR_CONNECTION;
import static org.zerionproject.core.rendezvous.RendezvousConstants.FAST_POLLING_DURATION_MS;
import static org.zerionproject.core.rendezvous.RendezvousConstants.FAST_POLLING_INTERVAL_MS;
import static org.zerionproject.core.rendezvous.RendezvousConstants.POLLING_INTERVAL_MS;
import static org.zerionproject.core.rendezvous.RendezvousConstants.RENDEZVOUS_TIMEOUT_MS;
import static org.zerionproject.core.util.IoUtils.tryToClose;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;
import static org.briarproject.nullsafety.NullSafety.requireNull;

@NotNullByDefault
class RendezvousPollerImpl implements RendezvousPoller, Service, EventListener {

	private final TaskScheduler scheduler;
	private final DatabaseComponent db;
	private final IdentityManager identityManager;
	private final TransportCrypto transportCrypto;
	private final RendezvousCrypto rendezvousCrypto;
	private final CryptoComponent crypto;
	private final KeyManager keyManager;
	private final PluginManager pluginManager;
	private final ConnectionManager connectionManager;
	private final EventBus eventBus;
	private final Clock clock;

	private final AtomicBoolean used = new AtomicBoolean(false);
	private final Map<PendingContactId, Long> lastPollTimes =
			new ConcurrentHashMap<>();
	private final Executor worker;
	private final Map<TransportId, PluginState> pluginStates = new HashMap<>();
	private final Map<PendingContactId, CryptoState> cryptoStates =
			new HashMap<>();
	@Nullable
	private KeyPair handshakeKeyPair = null;
	@Nullable
	private KeyPair hybridHandshakeKeyPair = null;
	@Nullable
	private byte[] ourHybridCommitment = null;
	@Nullable
	private Cancellable pollTask = null;

	@Inject
	RendezvousPollerImpl(@IoExecutor Executor ioExecutor,
			TaskScheduler scheduler,
			DatabaseComponent db,
			IdentityManager identityManager,
			TransportCrypto transportCrypto,
			RendezvousCrypto rendezvousCrypto,
			CryptoComponent crypto,
			KeyManager keyManager,
			PluginManager pluginManager,
			ConnectionManager connectionManager,
			EventBus eventBus,
			Clock clock) {
		this.scheduler = scheduler;
		this.db = db;
		this.identityManager = identityManager;
		this.transportCrypto = transportCrypto;
		this.rendezvousCrypto = rendezvousCrypto;
		this.crypto = crypto;
		this.keyManager = keyManager;
		this.pluginManager = pluginManager;
		this.connectionManager = connectionManager;
		this.eventBus = eventBus;
		this.clock = clock;
		worker = new PoliteExecutor("RendezvousPoller", ioExecutor, 1);
	}

	@Override
	public long getLastPollTime(PendingContactId p) {
		Long time = lastPollTimes.get(p);
		return time == null ? 0 : time;
	}

	@Override
	public void startService() throws ServiceException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		try {
			db.transaction(true, txn -> {
				Collection<PendingContact> pending = db.getPendingContacts(txn);
				txn.attach(() -> addPendingContactsAsync(pending));
			});
		} catch (DbException e) {
			throw new ServiceException(e);
		}
	}

	@EventExecutor
	private void addPendingContactsAsync(Collection<PendingContact> pending) {
		worker.execute(() -> {
			for (PendingContact p : pending) addPendingContact(p);
		});
	}
	private void addPendingContact(PendingContact p) {
		long now = clock.currentTimeMillis();

		long base = Math.max(p.getTimestamp(), now);
		long expiry = base + RENDEZVOUS_TIMEOUT_MS;
		try {
			SecretKey rendezvousKey;
			boolean alice;

			if (p.isPostQuantum()) {
				if (hybridHandshakeKeyPair == null) {
					hybridHandshakeKeyPair = db.transactionWithResult(true,
							identityManager::getHybridHandshakeKeys);
					if (hybridHandshakeKeyPair != null) {
						ourHybridCommitment = crypto.hash(HYBRID_COMMITMENT_LABEL,
								hybridHandshakeKeyPair.getPublic().getEncoded());
					}
				}
				if (hybridHandshakeKeyPair == null || ourHybridCommitment == null) {
					broadcastState(p.getId(), FAILED);
					return;
				}
				byte[] theirBlob = p.getPublicKey().getEncoded();
				KeyParser parser = crypto.getAgreementKeyParser();
				PublicKey theirX25519 = parser.parsePublicKey(
						java.util.Arrays.copyOfRange(theirBlob,
								HYBRID_COMMITMENT_BYTES,
								HYBRID_COMMITMENT_BYTES
										+ HYBRID_RENDEZVOUS_X25519_BYTES));
				byte[] ourHybridPub =
						hybridHandshakeKeyPair.getPublic().getEncoded();
				byte[] ourHybridPriv =
						hybridHandshakeKeyPair.getPrivate().getEncoded();
				KeyPair ourX25519 = new KeyPair(
						parser.parsePublicKey(java.util.Arrays.copyOfRange(
								ourHybridPub, 0,
								HYBRID_RENDEZVOUS_X25519_BYTES)),
						parser.parsePrivateKey(java.util.Arrays.copyOfRange(
								ourHybridPriv, 0,
								HYBRID_RENDEZVOUS_X25519_BYTES)));
				SecretKey staticMasterKey = transportCrypto
						.deriveStaticMasterKey(theirX25519, ourX25519);
				rendezvousKey = rendezvousCrypto
						.deriveRendezvousKey(staticMasterKey);
				alice = transportCrypto.isAlice(theirX25519, ourX25519);
				final SecretKey finalRendezvousKey = rendezvousKey;
				final boolean finalAlice = alice;
				db.transaction(false, txn ->
						keyManager.addHybridPendingContact(txn, p.getId(),
								finalRendezvousKey, finalAlice));
			} else {
				if (handshakeKeyPair == null) {
					handshakeKeyPair = db.transactionWithResult(true,
							identityManager::getHandshakeKeys);
				}
				SecretKey staticMasterKey = transportCrypto
						.deriveStaticMasterKey(p.getPublicKey(), handshakeKeyPair);
				rendezvousKey = rendezvousCrypto
						.deriveRendezvousKey(staticMasterKey);
				alice = transportCrypto
						.isAlice(p.getPublicKey(), handshakeKeyPair);
			}
			boolean classical = !p.isPostQuantum();
			CryptoState cs = new CryptoState(rendezvousKey, alice, expiry, classical, now);
			requireNull(cryptoStates.put(p.getId(), cs));
			for (PluginState ps : pluginStates.values()) {
				RendezvousEndpoint endpoint =
						createEndpoint(ps.plugin, p.getId(), cs);
				if (endpoint != null) {
					requireNull(ps.endpoints.put(p.getId(), endpoint));
					cs.numEndpoints++;
				}
			}
			if (cs.numEndpoints == 0) broadcastState(p.getId(), OFFLINE);
			else broadcastState(p.getId(), WAITING_FOR_CONNECTION);
			if (cryptoStates.size() == 1) {
				requireNull(pollTask);

				pollTask = scheduler.scheduleWithFixedDelay(this::poll, worker,
						FAST_POLLING_INTERVAL_MS, FAST_POLLING_INTERVAL_MS,
						MILLISECONDS);
			}
		} catch (DbException | GeneralSecurityException e) {
			broadcastState(p.getId(), FAILED);
		}
	}

	private void broadcastState(PendingContactId p, PendingContactState state) {
		eventBus.broadcast(new PendingContactStateChangedEvent(p, state));
	}

	@Nullable
	private RendezvousEndpoint createEndpoint(DuplexPlugin plugin,
			PendingContactId p, CryptoState cs) {
		TransportId t = plugin.getId();
		KeyMaterialSource k =
				rendezvousCrypto.createKeyMaterialSource(cs.rendezvousKey, t);
		Handler h = new Handler(p, t, true, cs.classical);
		return plugin.createRendezvousEndpoint(k, cs.alice, h);
	}

	@Wakeful
	private void poll() {
		removeExpiredPendingContacts();
		for (PluginState ps : pluginStates.values()) poll(ps);
	}
	private void removeExpiredPendingContacts() {
		long now = clock.currentTimeMillis();
		List<PendingContactId> expired = new ArrayList<>();
		for (Entry<PendingContactId, CryptoState> e : cryptoStates.entrySet()) {
			if (e.getValue().expiry <= now) expired.add(e.getKey());
		}
		for (PendingContactId p : expired) {
			removePendingContact(p);
			broadcastState(p, FAILED);
		}
	}
	private void removePendingContact(PendingContactId p) {
		if (cryptoStates.remove(p) == null) return;
		lastPollTimes.remove(p);
		for (PluginState ps : pluginStates.values()) {
			RendezvousEndpoint endpoint = ps.endpoints.remove(p);
			if (endpoint != null) tryToClose(endpoint);
		}
		if (cryptoStates.isEmpty()) {
			requireNonNull(pollTask).cancel();
			pollTask = null;
		}
	}
	@Wakeful
	private void poll(PluginState ps) {
		if (ps.endpoints.isEmpty()) return;
		TransportId t = ps.plugin.getId();
		long now = clock.currentTimeMillis();
		List<Pair<TransportProperties, ConnectionHandler>> properties =
				new ArrayList<>();
		List<PendingContactId> polled = new ArrayList<>();
		for (Entry<PendingContactId, RendezvousEndpoint> e :
				ps.endpoints.entrySet()) {
			PendingContactId pid = e.getKey();
			CryptoState cs = cryptoStates.get(pid);
			if (cs == null) continue;

			boolean fastMode =
					(now - cs.createdAt) < FAST_POLLING_DURATION_MS;
			if (!fastMode) {
				Long lastPoll = lastPollTimes.get(pid);
				if (lastPoll != null &&
						(now - lastPoll) < POLLING_INTERVAL_MS) {
					continue;
				}
			}
			TransportProperties props =
					e.getValue().getRemoteTransportProperties();
			Handler h = new Handler(pid, t, false, cs.classical);
			properties.add(new Pair<>(props, h));
			polled.add(pid);
		}
		if (polled.isEmpty()) return;
		for (PendingContactId p : polled) lastPollTimes.put(p, now);
		eventBus.broadcast(new RendezvousPollEvent(t, polled));
		ps.plugin.poll(properties);
	}

	@Override
	public void stopService() {
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof PendingContactAddedEvent) {
			PendingContactAddedEvent p = (PendingContactAddedEvent) e;
			addPendingContactAsync(p.getPendingContact());
		} else if (e instanceof PendingContactRemovedEvent) {
			PendingContactRemovedEvent p = (PendingContactRemovedEvent) e;
			removePendingContactAsync(p.getId());
		} else if (e instanceof TransportActiveEvent) {
			TransportActiveEvent t = (TransportActiveEvent) e;
			addTransportAsync(t.getTransportId());
		} else if (e instanceof TransportInactiveEvent) {
			TransportInactiveEvent t = (TransportInactiveEvent) e;
			removeTransportAsync(t.getTransportId());
		} else if (e instanceof RendezvousConnectionOpenedEvent) {
			RendezvousConnectionOpenedEvent r =
					(RendezvousConnectionOpenedEvent) e;
			connectionOpenedAsync(r.getPendingContactId());
		} else if (e instanceof RendezvousConnectionClosedEvent) {
			RendezvousConnectionClosedEvent r =
					(RendezvousConnectionClosedEvent) e;
			if (!r.isSuccess()) connectionFailedAsync(r.getPendingContactId());
		}
	}

	@EventExecutor
	private void addPendingContactAsync(PendingContact p) {
		worker.execute(() -> {
			addPendingContact(p);
			poll(p.getId());
		});
	}
	private void poll(PendingContactId p) {
		CryptoState cs = cryptoStates.get(p);
		boolean classical = cs != null && cs.classical;
		for (PluginState ps : pluginStates.values()) {
			RendezvousEndpoint endpoint = ps.endpoints.get(p);
			if (endpoint != null) {
				TransportId t = ps.plugin.getId();
				TransportProperties props =
						endpoint.getRemoteTransportProperties();
				Handler h = new Handler(p, t, false, classical);
				lastPollTimes.put(p, clock.currentTimeMillis());
				eventBus.broadcast(
						new RendezvousPollEvent(t, singletonList(p)));
				ps.plugin.poll(singletonList(new Pair<>(props, h)));
			}
		}
	}

	@EventExecutor
	private void removePendingContactAsync(PendingContactId p) {
		worker.execute(() -> removePendingContact(p));
	}

	@EventExecutor
	private void addTransportAsync(TransportId t) {
		Plugin p = pluginManager.getPlugin(t);
		if (p instanceof DuplexPlugin) {
			DuplexPlugin d = (DuplexPlugin) p;
			if (d.supportsRendezvous())
				worker.execute(() -> addTransport(d));
		}
	}
	private void addTransport(DuplexPlugin plugin) {
		TransportId t = plugin.getId();
		Map<PendingContactId, RendezvousEndpoint> endpoints = new HashMap<>();
		for (Entry<PendingContactId, CryptoState> e : cryptoStates.entrySet()) {
			PendingContactId p = e.getKey();
			CryptoState cs = e.getValue();
			RendezvousEndpoint endpoint = createEndpoint(plugin, p, cs);
			if (endpoint != null) {
				endpoints.put(p, endpoint);
				if (++cs.numEndpoints == 1)
					broadcastState(p, WAITING_FOR_CONNECTION);
			}
		}
		requireNull(pluginStates.put(t, new PluginState(plugin, endpoints)));
	}

	@EventExecutor
	private void removeTransportAsync(TransportId t) {
		worker.execute(() -> removeTransport(t));
	}
	private void removeTransport(TransportId t) {
		PluginState ps = pluginStates.remove(t);
		if (ps != null) {
			for (Entry<PendingContactId, RendezvousEndpoint> e :
					ps.endpoints.entrySet()) {
				tryToClose(e.getValue());
				CryptoState cs = cryptoStates.get(e.getKey());
				if (--cs.numEndpoints == 0) broadcastState(e.getKey(), OFFLINE);
			}
		}
	}

	@EventExecutor
	private void connectionOpenedAsync(PendingContactId p) {
		worker.execute(() -> connectionOpened(p));
	}
	private void connectionOpened(PendingContactId p) {
		if (cryptoStates.containsKey(p)) broadcastState(p, ADDING_CONTACT);
	}

	@EventExecutor
	private void connectionFailedAsync(PendingContactId p) {
		worker.execute(() -> connectionFailed(p));
	}
	private void connectionFailed(PendingContactId p) {
		if (cryptoStates.containsKey(p)) {
			broadcastState(p, WAITING_FOR_CONNECTION);

			poll(p);
		}
	}

	private static class PluginState {

		private final DuplexPlugin plugin;
		private final Map<PendingContactId, RendezvousEndpoint> endpoints;

		private PluginState(DuplexPlugin plugin,
				Map<PendingContactId, RendezvousEndpoint> endpoints) {
			this.plugin = plugin;
			this.endpoints = endpoints;
		}
	}

	private static class CryptoState {

		private final SecretKey rendezvousKey;
		private final boolean alice;
		private final long expiry;
		private final long createdAt;

		private final boolean classical;

		private int numEndpoints = 0;

		private CryptoState(SecretKey rendezvousKey, boolean alice,
				long expiry, boolean classical, long createdAt) {
			this.rendezvousKey = rendezvousKey;
			this.alice = alice;
			this.expiry = expiry;
			this.classical = classical;
			this.createdAt = createdAt;
		}
	}

	private class Handler implements ConnectionHandler {

		private final PendingContactId pendingContactId;
		private final TransportId transportId;
		private final boolean incoming;
		private final boolean classical;

		private Handler(PendingContactId pendingContactId,
				TransportId transportId, boolean incoming, boolean classical) {
			this.pendingContactId = pendingContactId;
			this.transportId = transportId;
			this.incoming = incoming;
			this.classical = classical;
		}

		@Override
		public void handleConnection(DuplexTransportConnection c) {
			if (incoming) {
				connectionManager.manageIncomingConnection(pendingContactId,
						transportId, c, classical);
			} else {
				connectionManager.manageOutgoingConnection(pendingContactId,
						transportId, c, classical);
			}
		}

		@Override
		public void handleReader(TransportConnectionReader r) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void handleWriter(TransportConnectionWriter w) {
			throw new UnsupportedOperationException();
		}
	}
}
