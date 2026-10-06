package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager.ContactHook;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.plugin.OnionClientAuthManager;
import org.zerionproject.core.api.plugin.OnionTargets;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.properties.event.RemoteTransportPropertiesUpdatedEvent;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore.ServiceRecord;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Provider;

import static org.zerionproject.core.api.plugin.OnionClientAuthManager.State.AUTH_CONFIRMED;
import static org.zerionproject.core.api.plugin.OnionClientAuthManager.State.AUTH_NEGOTIATING;
import static org.zerionproject.core.api.plugin.OnionClientAuthManager.State.AUTH_REQUIRED;
import static org.zerionproject.core.api.plugin.OnionClientAuthManager.State.LEGACY;
import static org.zerionproject.core.api.plugin.OnionClientAuthManager.State.REVOKED;
import static org.zerionproject.core.api.sync.Group.Visibility.SHARED;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthRecords.CLIENT_ID;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthRecords.MAJOR_VERSION;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthValidator.MSG_KEY_KEY_VERSION;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthValidator.MSG_KEY_LOCAL;
import static org.zerionproject.core.plugin.tor.auth.OnionAuthValidator.MSG_KEY_TYPE;

@ThreadSafe
@NotNullByDefault
@javax.inject.Singleton
public class OnionClientAuthManagerImpl implements OnionClientAuthManager,
		OpenDatabaseHook, ContactHook, IncomingMessageHook, EventListener,
		org.zerionproject.core.api.versioning.ClientVersioningManager
				.ClientVersioningHook {

	static final long PROBE_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
	static final long PROBE_PAUSE_MS = TimeUnit.MINUTES.toMillis(30);
	static final long NEGOTIATION_TIMEOUT_MS = TimeUnit.DAYS.toMillis(7);
	static final long ROTATION_PERIOD_MS = TimeUnit.DAYS.toMillis(30);
	static final long RETIRE_DEADLINE_MS = TimeUnit.DAYS.toMillis(30);
	static final long TICK_MS = TimeUnit.MINUTES.toMillis(15);
	static final int MAX_AUTHORIZED_CLIENTS = 200;
	static final int REMOTE_PORT = 80;

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final ContactGroupFactory contactGroupFactory;
	private final MetadataParser metadataParser;
	private final OnionAuthStore store;
	private final CryptoComponent crypto;
	private final Provider<TransportPropertyManager> transportPropertyManager;
	private final EventBus eventBus;
	private final Clock clock;
	private final Executor ioExecutor;
	private final TaskScheduler scheduler;

	private final Object torLock = new Object();
	private final Object serviceLock = new Object();
	@Nullable
	private OnionServiceControl control = null;
	@Nullable
	private String authorizedTarget = null;
	@Nullable
	private ProbeDialer probeDialer = null;

	private final Map<ContactId, Cached> cache = new ConcurrentHashMap<>();

	private static final class Cached {

		final State state;
		@Nullable
		final String peerOnion;
		final boolean probing;

		Cached(State state, @Nullable String peerOnion, boolean probing) {
			this.state = state;
			this.peerOnion = peerOnion;
			this.probing = probing;
		}
	}

	@Inject
	OnionClientAuthManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			ContactGroupFactory contactGroupFactory,
			MetadataParser metadataParser, OnionAuthStore store,
			CryptoComponent crypto,
			Provider<TransportPropertyManager> transportPropertyManager,
			EventBus eventBus, Clock clock, @IoExecutor Executor ioExecutor,
			TaskScheduler scheduler) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.contactGroupFactory = contactGroupFactory;
		this.metadataParser = metadataParser;
		this.store = store;
		this.crypto = crypto;
		this.transportPropertyManager = transportPropertyManager;
		this.eventBus = eventBus;
		this.clock = clock;
		this.ioExecutor = ioExecutor;
		this.scheduler = scheduler;
	}

	public interface ProbeDialer {
		void dial(ContactId c);
	}

	public void attachTor(OnionServiceControl control, int authorizedPort) {
		attachTor(control, authorizedPort, null);
	}

	public void attachTor(OnionServiceControl control, int authorizedPort,
			@Nullable ProbeDialer dialer) {
		attachTor(control, OnionTargets.loopback(authorizedPort), dialer);
	}

	public void attachTor(OnionServiceControl control,
			String authorizedTarget, @Nullable ProbeDialer dialer) {
		synchronized (torLock) {
			this.control = control;
			this.authorizedTarget = authorizedTarget;
			this.probeDialer = dialer;
		}
		refeedTor();
	}

	public void detachTor() {
		synchronized (torLock) {
			this.control = null;
			this.authorizedTarget = null;
			this.probeDialer = null;
		}
	}

	public void dialSucceeded(ContactId c, String onion) {
		Cached cached = cache.get(c);
		if (cached != null && cached.probing && onion.equals(cached.peerOnion)) {
			ioExecutor.execute(() -> probeSucceeded(c));
		}
	}

	@Override
	public void inboundViaAuthorizedService(ContactId c) {
		Cached cached = cache.get(c);
		if (cached != null && cached.probing) {
			ioExecutor.execute(() -> probeSucceeded(c));
		}
	}

	private void requestProbeDial(ContactId c) {
		ProbeDialer dialer;
		synchronized (torLock) {
			dialer = probeDialer;
		}
		if (dialer != null) ioExecutor.execute(() -> dialer.dial(c));
	}

	@Nullable
	private OnionServiceControl control() {
		synchronized (torLock) {
			return control;
		}
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		removeGroupsOfRemovedContacts(txn);
		for (Contact c : db.getContacts(txn)) ensureGroup(txn, c);
		for (OnionAuthRecord r : store.loadAll(txn)) {
			cache(r);
			if (r.state == AUTH_NEGOTIATING && r.peerPublicKey == null) {
				sendOffer(txn, r);
			} else if (r.commitSent && r.state == AUTH_CONFIRMED) {
				resendCommit(txn, r);
			}
		}
		scheduler.scheduleWithFixedDelay(this::tick, ioExecutor, TICK_MS,
				TICK_MS, TimeUnit.MILLISECONDS);
	}

	private void removeGroupsOfRemovedContacts(Transaction txn)
			throws DbException {
		for (Group g : db.getGroups(txn, CLIENT_ID, MAJOR_VERSION)) {
			ContactId c;
			try {
				c = clientHelper.getContactId(txn, g.getId());
			} catch (DbException e) {
				c = null;
			}
			boolean exists = false;
			if (c != null) {
				try {
					db.getContact(txn, c);
					exists = true;
				} catch (NoSuchContactException e) {
					exists = false;
				}
			}
			if (!exists) db.removeGroup(txn, g);
		}
	}

	private void ensureGroup(Transaction txn, Contact c) throws DbException {
		Group g = getContactGroup(c);
		if (!db.containsGroup(txn, g.getId())) {
			db.addGroup(txn, g);
			db.setGroupVisibility(txn, c.getId(), g.getId(), SHARED);
		}
		clientHelper.setContactId(txn, g.getId(), c.getId());
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Group.Visibility v) throws DbException {
		Group g = getContactGroup(c);
		if (db.containsGroup(txn, g.getId())) {
			db.setGroupVisibility(txn, c.getId(), g.getId(), SHARED);
		}
	}

	private Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(CLIENT_ID,
				MAJOR_VERSION, c);
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		ensureGroup(txn, c);
		OnionAuthRecord r = store.load(txn, c.getId());
		if (r.state != LEGACY) store.clear(txn, c.getId());
		cache.remove(c.getId());
	}

	@Override
	public void removingContact(Transaction txn, Contact c)
			throws DbException {
		Group g = getContactGroup(c);
		if (db.containsGroup(txn, g.getId())) db.removeGroup(txn, g);
		OnionAuthRecord r = store.load(txn, c.getId());
		if (r.state == LEGACY) {
			cache.remove(c.getId());
			return;
		}
		String peerOnion = r.peerOnion;
		boolean authorized = r.authorizesPeer();
		store.clear(txn, c.getId());
		OnionAuthRecord tombstone = new OnionAuthRecord(c.getId());
		tombstone.state = REVOKED;
		store.save(txn, tombstone);
		cache(tombstone);
		if (authorized) {
			ServiceRecord svc = store.loadService(txn);
			svc.revocationPending = true;
			store.saveService(txn, svc);
		}
		RevocationWork w = new RevocationWork(c.getId(), peerOnion, authorized);
		txn.attach(() -> ioExecutor.execute(() -> revoke(w)));
	}

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		try {
			BdfDictionary d = metadataParser.parse(meta);
			if (d.getBoolean(MSG_KEY_LOCAL, false)) return ACCEPT_DO_NOT_SHARE;
			ContactId c = clientHelper.getContactId(txn, m.getGroupId());
			OnionAuthRecords.Record r = OnionAuthRecords.parse(
					clientHelper.toList(m, false));
			handleRecord(txn, c, r);
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
		return ACCEPT_DO_NOT_SHARE;
	}

	private void handleRecord(Transaction txn, ContactId c,
			OnionAuthRecords.Record msg) throws DbException {
		OnionAuthRecord r = store.load(txn, c);
		if (r.state == REVOKED) return;
		switch (msg.type) {
			case OnionAuthRecords.TYPE_OFFER:
				onOffer(txn, r, msg);
				break;
			case OnionAuthRecords.TYPE_READY:
				onReady(txn, r, msg);
				break;
			case OnionAuthRecords.TYPE_PROBE_SUCCESS:
				onProbeSuccess(txn, r, msg);
				break;
			case OnionAuthRecords.TYPE_COMMIT:
				onCommit(txn, r, msg);
				break;
			case OnionAuthRecords.TYPE_ROTATE:
				onRotate(txn, r, msg);
				break;
			case OnionAuthRecords.TYPE_ROTATE_ACK:
				onRotateAck(txn, r, msg);
				break;
			default:
				break;
		}
	}

	private void onOffer(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		if (r.state == AUTH_REQUIRED) {
			resendCommit(txn, r);
			return;
		}
		byte[] pub = msg.publicKey;
		if (pub == null) return;
		boolean newKey = r.peerPublicKey == null
				|| !Arrays.equals(r.peerPublicKey, pub);
		if (r.state == LEGACY) startNegotiation(txn, r);
		if (r.peerPublicKey != null && !newKey && msg.onion != null
				&& msg.onion.equals(r.peerOnion) && msg.keyVersion == r.peerGen) {
			return;
		}
		r.peerPublicKey = pub;
		if (msg.onion != null) {
			r.peerOnion = msg.onion;
			r.peerGen = msg.keyVersion;
		}
		store.save(txn, r);
		cache(r);
		ContactId id = r.contactId;
		txn.attach(() -> ioExecutor.execute(() -> authorize(id)));
	}

	private void startNegotiation(Transaction txn, OnionAuthRecord r)
			throws DbException {
		KeyPair kp = crypto.generateAgreementKeyPair();
		r.state = AUTH_NEGOTIATING;
		r.dialPrivateKey = kp.getPrivate().getEncoded();
		r.dialPublicKey = kp.getPublic().getEncoded();
		r.negotiationStartedMs = clock.currentTimeMillis();
		ServiceRecord svc = store.loadService(txn);
		r.localGen = svc.gen;
		store.save(txn, r);
		cache(r);
	}

	private void onReady(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		if (r.state == AUTH_REQUIRED) {
			resendCommit(txn, r);
			return;
		}
		if (r.state == LEGACY || r.peerOnion == null) return;
		if (msg.keyVersion != r.peerGen) return;
		r.readyReceived = true;
		startProbe(txn, r);
		store.save(txn, r);
		cache(r);
	}

	private void startProbe(Transaction txn, OnionAuthRecord r) {
		if (r.probeSucceeded) return;
		r.probing = true;
		r.probeUntilMs = clock.currentTimeMillis() + PROBE_WINDOW_MS;
		ContactId c = r.contactId;
		txn.attach(() -> requestProbeDial(c));
	}

	private void onProbeSuccess(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		if (r.state == AUTH_REQUIRED) {
			resendCommit(txn, r);
			return;
		}
		if (r.state == LEGACY) return;
		if (msg.keyVersion != r.peerGen) return;
		r.peerProbeSucceeded = true;
		if (r.state == AUTH_NEGOTIATING) r.state = AUTH_CONFIRMED;
		maybeCommit(txn, r);
		store.save(txn, r);
		cache(r);
	}

	private void maybeCommit(Transaction txn, OnionAuthRecord r)
			throws DbException {
		if (r.commitSent || !r.readyReceived || !r.probeSucceeded
				|| !r.peerProbeSucceeded || r.peerOnion == null
				|| r.dialPublicKey == null) {
			return;
		}
		ServiceRecord svc = store.loadService(txn);
		send(txn, r.contactId, OnionAuthRecords.commit(svc.gen, r.peerOnion,
				OnionAuthCommitCheck.fingerprint(r.dialPublicKey)),
				OnionAuthRecords.TYPE_COMMIT, svc.gen);
		r.commitSent = true;
		if (r.peerCommitReceived) r.state = AUTH_REQUIRED;
	}

	private void resendCommit(Transaction txn, OnionAuthRecord r)
			throws DbException {
		if (r.peerOnion == null || r.dialPublicKey == null) return;
		ServiceRecord svc = store.loadService(txn);
		send(txn, r.contactId, OnionAuthRecords.commit(svc.gen, r.peerOnion,
				OnionAuthCommitCheck.fingerprint(r.dialPublicKey)),
				OnionAuthRecords.TYPE_COMMIT, svc.gen);
	}

	private void onCommit(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		ServiceRecord svc = store.loadService(txn);
		OnionAuthCommitCheck.Verdict v = OnionAuthCommitCheck.check(r, msg,
				svc.gen, svc.onion);
		if (r.state == AUTH_REQUIRED) {
			resendCommit(txn, r);
			return;
		}
		if (v != OnionAuthCommitCheck.Verdict.ACCEPT) return;
		r.peerCommitReceived = true;
		if (r.state == AUTH_NEGOTIATING) r.state = AUTH_CONFIRMED;
		maybeCommit(txn, r);
		if (r.commitSent) r.state = AUTH_REQUIRED;
		if (r.state == AUTH_REQUIRED) r.probing = false;
		store.save(txn, r);
		cache(r);
	}

	private void onRotate(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		if (r.state == LEGACY) return;
		if (msg.keyVersion <= r.peerGen) return;
		if (msg.onion != null) {
			r.peerOnion = msg.onion;
		}
		if (msg.publicKey != null) {
			r.peerPublicKey = msg.publicKey;
		}
		r.peerGen = msg.keyVersion;
		store.save(txn, r);
		cache(r);
		send(txn, r.contactId, OnionAuthRecords.rotateAck(msg.keyVersion),
				OnionAuthRecords.TYPE_ROTATE_ACK, msg.keyVersion);
		ContactId id = r.contactId;
		txn.attach(() -> ioExecutor.execute(() -> authorize(id)));
	}

	private void onRotateAck(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		if (r.state == LEGACY) return;
		ServiceRecord svc = store.loadService(txn);
		if (msg.keyVersion != svc.gen) return;
		r.rotateAcked = true;
		store.save(txn, r);
		if (svc.oldOnion != null && allAcked(txn)) {
			txn.attach(() -> ioExecutor.execute(this::retireOld));
		}
	}

	private boolean allAcked(Transaction txn) throws DbException {
		for (OnionAuthRecord r : store.loadAll(txn)) {
			if (r.authorizesPeer() && !r.rotateAcked) return false;
		}
		return true;
	}

	@Override
	public State getState(ContactId c) throws DbException {
		return db.transactionWithResult(true,
				txn -> store.load(txn, c).state);
	}

	@Override
	@Nullable
	public String getDialOnion(ContactId c) {
		Cached cached = cache.get(c);
		if (cached == null) return null;
		if (cached.state == AUTH_REQUIRED) return cached.peerOnion;
		if ((cached.state == AUTH_NEGOTIATING
				|| cached.state == AUTH_CONFIRMED) && cached.probing) {
			return cached.peerOnion;
		}
		return null;
	}

	@Override
	public boolean acceptsInbound(ContactId c, boolean viaAuthorizedService) {
		Cached cached = cache.get(c);
		if (cached == null) return true;
		if (cached.state == REVOKED) return false;
		if (cached.state == AUTH_REQUIRED) return viaAuthorizedService;
		return true;
	}

	@Override
	public void rotateAuthorizedService() throws DbException {
		ioExecutor.execute(() -> rotate(false, null));
	}

	@Override
	public void resetNegotiation(ContactId c) throws DbException {
		db.transaction(false, txn -> abortNegotiation(txn, c));
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof RemoteTransportPropertiesUpdatedEvent) {
			if (TorConstants.ID.equals(((RemoteTransportPropertiesUpdatedEvent) e)
					.getTransportId())) {
				ioExecutor.execute(this::evaluateOffers);
			}
		}
	}

	private void evaluateOffers() {
		try {
			Map<ContactId, org.zerionproject.core.api.properties
					.TransportProperties> remote = transportPropertyManager
					.get().getRemoteProperties(TorConstants.ID);
			db.transaction(false, txn -> {
				for (Map.Entry<ContactId, org.zerionproject.core.api.properties
						.TransportProperties> e : remote.entrySet()) {
					String v = e.getValue().get(
							TorConstants.PROP_ONION_AUTH_SUPPORTED);
					if (v == null || v.isEmpty()) continue;
					OnionAuthRecord r = store.load(txn, e.getKey());
					if (r.state != LEGACY) continue;
					startNegotiation(txn, r);
					sendOffer(txn, r);
				}
			});
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private void sendOffer(Transaction txn, OnionAuthRecord r)
			throws DbException {
		if (r.dialPublicKey == null) return;
		ServiceRecord svc = store.loadService(txn);
		if (r.localGen != svc.gen) {
			r.localGen = svc.gen;
			store.save(txn, r);
			cache(r);
		}
		send(txn, r.contactId, OnionAuthRecords.offer(svc.gen, svc.onion,
				r.dialPublicKey), OnionAuthRecords.TYPE_OFFER, svc.gen);
	}

	private void probeSucceeded(ContactId c) {
		try {
			probeSucceededUnguarded(c);
		} catch (RuntimeException ignored) {
		}
	}

	private void probeSucceededUnguarded(ContactId c) {
		try {
			db.transaction(false, txn -> {
				OnionAuthRecord r = store.load(txn, c);
				if (!r.probing || r.state == REVOKED) return;
				r.probing = false;
				r.probeSucceeded = true;
				if (r.state == AUTH_NEGOTIATING) r.state = AUTH_CONFIRMED;
				ServiceRecord svc = store.loadService(txn);
				send(txn, c, OnionAuthRecords.probeSuccess(svc.gen),
						OnionAuthRecords.TYPE_PROBE_SUCCESS, svc.gen);
				maybeCommit(txn, r);
				store.save(txn, r);
				cache(r);
			});
		} catch (DbException ignored) {
		}
	}

	private void authorize(ContactId c) {
		OnionServiceControl ctl = control();
		if (ctl == null) return;
		synchronized (serviceLock) {
			try {
				authorizeLocked(ctl, c);
			} catch (RuntimeException ignored) {
			}
		}
	}

	private void authorizeLocked(OnionServiceControl ctl, ContactId c) {
		try {
			boolean serviceChanged = republishService(ctl);
			credentialAndAnswer(ctl, c, serviceChanged);
		} catch (DbException | IOException ignored) {
		}
	}

	private void credentialAndAnswer(OnionServiceControl ctl, ContactId c,
			boolean serviceChanged) throws DbException {
		db.transaction(false, txn -> {
			OnionAuthRecord r = store.load(txn, c);
			if (r.state == LEGACY || r.state == REVOKED) return;
			boolean credentialInstalled = false;
			if (r.peerOnion != null && r.dialPrivateKey != null) {
				try {
					ctl.addClientKey(r.peerOnion, r.dialPrivateKey);
					credentialInstalled = true;
				} catch (IOException e) {
					credentialInstalled = false;
				}
			}
			ServiceRecord svc = store.loadService(txn);
			if (r.state != AUTH_REQUIRED) {
				if (serviceChanged || svc.onion != null) sendOffer(txn, r);
				if (credentialInstalled && svc.onion != null) {
					send(txn, c, OnionAuthRecords.ready(r.peerGen),
							OnionAuthRecords.TYPE_READY, r.peerGen);
				}
			}
		});
	}

	private boolean republishService(OnionServiceControl ctl)
			throws DbException, IOException {
		String target;
		synchronized (torLock) {
			target = authorizedTarget;
		}
		if (target == null) return false;
		List<byte[]> keys = db.transactionWithResult(true, this::authorizedKeys);
		ServiceRecord svc = db.transactionWithResult(true, store::loadService);
		if (keys.isEmpty()) {
			if (svc.onion != null) {
				try {
					ctl.remove(svc.onion);
				} catch (IOException ignored) {
				}
				svc.onion = null;
				db.transaction(false, txn -> store.saveService(txn, svc));
			}
			return false;
		}
		if (keys.size() > MAX_AUTHORIZED_CLIENTS) {
			throw new OnionServiceControl.CapacityException();
		}
		if (svc.onion != null) {
			try {
				ctl.remove(svc.onion);
			} catch (IOException ignored) {
			}
		}
		OnionServiceControl.Published p = ctl.publish(svc.privateKey,
				target, REMOTE_PORT, keys);
		svc.onion = p.onion;
		svc.privateKey = p.privateKey;
		if (svc.gen == 0) svc.gen = 1;
		if (svc.lastRotationMs == 0) svc.lastRotationMs = clock.currentTimeMillis();
		db.transaction(false, txn -> store.saveService(txn, svc));
		return true;
	}

	private List<byte[]> authorizedKeys(Transaction txn) throws DbException {
		List<byte[]> keys = new ArrayList<>();
		for (OnionAuthRecord r : store.loadAll(txn)) {
			if (r.authorizesPeer() && r.peerPublicKey != null) {
				keys.add(r.peerPublicKey);
			}
		}
		return keys;
	}

	@Override
	public void refeedCredentials() {
		ioExecutor.execute(() -> {
			OnionServiceControl ctl = control();
			if (ctl == null) return;
			synchronized (serviceLock) {
				try {
					for (OnionAuthRecord r : db.transactionWithResult(true,
							store::loadAll)) {
						if (r.state == LEGACY || r.state == REVOKED) continue;
						if (r.peerOnion == null || r.dialPrivateKey == null) {
							continue;
						}
						try {
							ctl.addClientKey(r.peerOnion, r.dialPrivateKey);
						} catch (IOException ignored) {
						}
					}
				} catch (DbException | RuntimeException ignored) {
				}
			}
		});
	}

	private void refeedTor() {
		ioExecutor.execute(() -> {
			OnionServiceControl ctl = control();
			if (ctl == null) return;
			synchronized (serviceLock) {
				try {
					refeedLocked(ctl);
				} catch (RuntimeException ignored) {
				}
			}
			evaluateOffers();
			for (Map.Entry<ContactId, Cached> e : cache.entrySet()) {
				if (e.getValue().probing) requestProbeDial(e.getKey());
			}
		});
	}

	private void refeedLocked(OnionServiceControl ctl) {
			try {
				ServiceRecord pending = db.transactionWithResult(true,
						store::loadService);
				if (pending.revocationPending) rotateLocked(true, null);
				else republishService(ctl);
				ServiceRecord svc = db.transactionWithResult(true,
						store::loadService);
				if (svc.oldOnion != null && svc.oldPrivateKey != null) {
					String target;
					synchronized (torLock) {
						target = authorizedTarget;
					}
					List<byte[]> keys = db.transactionWithResult(true,
							this::authorizedKeys);
					if (!keys.isEmpty() && target != null) {
						ctl.publish(svc.oldPrivateKey, target, REMOTE_PORT,
								keys);
					}
				}
				for (OnionAuthRecord r : db.transactionWithResult(true,
						store::loadAll)) {
					if (r.state == LEGACY || r.state == REVOKED) continue;
					if (r.state == AUTH_REQUIRED) {
						if (r.peerOnion != null && r.dialPrivateKey != null) {
							try {
								ctl.addClientKey(r.peerOnion, r.dialPrivateKey);
							} catch (IOException ignored) {
							}
						}
					} else if (r.peerPublicKey != null) {
						credentialAndAnswer(ctl, r.contactId, false);
					}
				}
			} catch (DbException | IOException ignored) {
			}
	}

	private void revoke(RevocationWork w) {
		OnionServiceControl ctl = control();
		synchronized (serviceLock) {
			try {
				revokeLocked(ctl, w);
			} catch (RuntimeException ignored) {
			}
		}
	}

	private void revokeLocked(@Nullable OnionServiceControl ctl,
			RevocationWork w) {
		if (w.peerOnion != null && ctl != null) {
			try {
				ctl.removeClientKey(w.peerOnion);
			} catch (IOException ignored) {
			}
		}
		if (!w.wasAuthorized) return;
		try {
			db.transaction(false, txn -> {
				ServiceRecord svc = store.loadService(txn);
				svc.revocationPending = true;
				store.saveService(txn, svc);
			});
		} catch (DbException ignored) {
			return;
		}
		rotateLocked(true, w.contactId);
	}

	private void rotate(boolean revocation, @Nullable ContactId revoked) {
		synchronized (serviceLock) {
			try {
				rotateLocked(revocation, revoked);
			} catch (RuntimeException ignored) {
			}
		}
	}

	private void rotateLocked(boolean revocation, @Nullable ContactId revoked) {
		OnionServiceControl ctl = control();
		if (ctl == null) return;
		String target;
		synchronized (torLock) {
			target = authorizedTarget;
		}
		if (target == null) return;
		try {
			List<byte[]> keys = db.transactionWithResult(true, this::authorizedKeys);
			ServiceRecord svc = db.transactionWithResult(true, store::loadService);
			if (svc.onion == null && keys.isEmpty()) return;
			if (revocation) {
				if (svc.onion != null) {
					try {
						ctl.remove(svc.onion);
					} catch (IOException ignored) {
					}
				}
				if (svc.oldOnion != null) {
					try {
						ctl.remove(svc.oldOnion);
					} catch (IOException ignored) {
					}
				}
				svc.oldOnion = null;
				svc.oldPrivateKey = null;
				svc.oldSinceMs = 0;
			} else if (svc.onion != null) {
				svc.oldOnion = svc.onion;
				svc.oldPrivateKey = svc.privateKey;
				svc.oldSinceMs = clock.currentTimeMillis();
			}
			svc.gen++;
			svc.lastRotationMs = clock.currentTimeMillis();
			if (keys.isEmpty()) {
				svc.onion = null;
				svc.privateKey = null;
				svc.revocationPending = false;
				db.transaction(false, txn -> store.saveService(txn, svc));
				return;
			}
			OnionServiceControl.Published p = ctl.publish(null, target,
					REMOTE_PORT, keys);
			svc.onion = p.onion;
			svc.privateKey = p.privateKey;
			svc.revocationPending = false;
			db.transaction(false, txn -> {
				store.saveService(txn, svc);
				for (OnionAuthRecord r : store.loadAll(txn)) {
					if (!r.authorizesPeer()) continue;
					if (revoked != null && r.contactId.equals(revoked)) continue;
					r.rotateAcked = false;
					r.localGen = svc.gen;
					store.save(txn, r);
					send(txn, r.contactId, OnionAuthRecords.rotate(svc.gen,
							p.onion, null), OnionAuthRecords.TYPE_ROTATE, svc.gen);
				}
			});
		} catch (DbException | IOException ignored) {
		}
	}

	private void retireOld() {
		OnionServiceControl ctl = control();
		synchronized (serviceLock) {
			try {
				retireOldLocked(ctl);
			} catch (RuntimeException ignored) {
			}
		}
	}

	private void retireOldLocked(@Nullable OnionServiceControl ctl) {
		try {
			ServiceRecord svc = db.transactionWithResult(true, store::loadService);
			if (svc.oldOnion == null) return;
			if (ctl != null) {
				try {
					ctl.remove(svc.oldOnion);
				} catch (IOException ignored) {
				}
			}
			svc.oldOnion = null;
			svc.oldPrivateKey = null;
			svc.oldSinceMs = 0;
			db.transaction(false, txn -> store.saveService(txn, svc));
		} catch (DbException ignored) {
		}
	}

	private void abortNegotiation(Transaction txn, ContactId c)
			throws DbException {
		OnionAuthRecord r = store.load(txn, c);
		if (r.state != AUTH_NEGOTIATING && r.state != AUTH_CONFIRMED) return;
		String peerOnion = r.peerOnion;
		store.clear(txn, c);
		cache.remove(c);
		RevocationWork w = new RevocationWork(c, peerOnion, false);
		txn.attach(() -> ioExecutor.execute(() -> {
			revoke(w);
			OnionServiceControl ctl = control();
			if (ctl == null) return;
			synchronized (serviceLock) {
				try {
					republishService(ctl);
				} catch (DbException | IOException ignored) {
				}
			}
		}));
	}

	void tickForTest() {
		tick();
	}

	private void tick() {
		try {
			tickUnguarded();
		} catch (RuntimeException ignored) {
		}
	}

	private void tickUnguarded() {
		long now = clock.currentTimeMillis();
		try {
			db.transaction(false, txn -> {
				for (OnionAuthRecord r : store.loadAll(txn)) {
					if ((r.state == AUTH_NEGOTIATING
							|| r.state == AUTH_CONFIRMED)
							&& now - r.negotiationStartedMs
							> NEGOTIATION_TIMEOUT_MS) {
						abortNegotiation(txn, r.contactId);
						continue;
					}
					if (r.probing && now > r.probeUntilMs) {
						r.probing = false;
						r.probeUntilMs = now + PROBE_PAUSE_MS;
						store.save(txn, r);
						cache(r);
					} else if (!r.probing && !r.probeSucceeded
							&& r.readyReceived && r.state != AUTH_REQUIRED
							&& r.state != LEGACY && r.state != REVOKED
							&& now > r.probeUntilMs) {
						startProbe(txn, r);
						store.save(txn, r);
						cache(r);
					}
				}
				ServiceRecord svc = store.loadService(txn);
				if (svc.oldOnion != null
						&& now - svc.oldSinceMs > RETIRE_DEADLINE_MS) {
					txn.attach(() -> ioExecutor.execute(this::retireOld));
				}
				if (svc.onion != null
						&& now - svc.lastRotationMs > ROTATION_PERIOD_MS) {
					ioExecutor.execute(() -> rotate(false, null));
				}
			});
		} catch (DbException ignored) {
		}
	}

	private void cache(OnionAuthRecord r) {
		cache.put(r.contactId, new Cached(r.state, r.peerOnion, r.probing));
	}

	private void send(Transaction txn, ContactId c, BdfList body, int type,
			long keyVersion) throws DbException {
		try {
			Contact contact = db.getContact(txn, c);
			Group g = getContactGroup(contact);
			Message m = clientHelper.createMessage(g.getId(),
					clock.currentTimeMillis(), body);
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_TYPE, type);
			meta.put(MSG_KEY_KEY_VERSION, keyVersion);
			meta.put(MSG_KEY_LOCAL, true);
			clientHelper.addLocalMessage(txn, m, meta, true, false);
		} catch (FormatException e) {
			throw new DbException();
		}
	}

	private static final class RevocationWork {

		final ContactId contactId;
		@Nullable
		final String peerOnion;
		final boolean wasAuthorized;

		RevocationWork(ContactId contactId, @Nullable String peerOnion,
				boolean wasAuthorized) {
			this.contactId = contactId;
			this.peerOnion = peerOnion;
			this.wasAuthorized = wasAuthorized;
		}
	}

}
