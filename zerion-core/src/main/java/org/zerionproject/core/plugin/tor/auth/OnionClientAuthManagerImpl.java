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
import org.zerionproject.transport.ZtpPoller;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.plugin.OnionClientAuthManager;
import org.zerionproject.core.api.plugin.OnionTargets;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.ConnectionOpenedEvent;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.properties.event.RemoteTransportPropertiesUpdatedEvent;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.event.MessageAddedEvent;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore.ServiceRecord;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import static org.zerionproject.core.api.plugin.B4Constants.OLD_ADDRESS_SILENCE_LIMIT_MS;
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
	static final long MIN_ROTATION_PERIOD_MS = TimeUnit.DAYS.toMillis(28);
	static final long MAX_ROTATION_PERIOD_MS = TimeUnit.DAYS.toMillis(35);
	static final long TICK_MS = TimeUnit.MINUTES.toMillis(15);
	static final long REPAIR_DELAY_MS = TimeUnit.HOURS.toMillis(1);
	static final long MAX_REPAIR_DELAY_MS = TimeUnit.DAYS.toMillis(1);
	static final int MAX_REPAIRS = 10;
	static final long SILENCE_MS = TimeUnit.DAYS.toMillis(1);
	static final long MAX_OPEN_DIAL_DELAY_MS = TimeUnit.DAYS.toMillis(7);
	static final long REKEY_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);
	static final long SEEN_WRITE_MS = TimeUnit.HOURS.toMillis(1);
	static final int MAX_AUTHORIZED_CLIENTS = 200;
	static final long HOLD_NOTICE_MS = TimeUnit.DAYS.toMillis(1);
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
	private final OnionAuthDebugHooks hooks;

	private final Object torLock = new Object();
	private final Object serviceLock = new Object();
	@Nullable
	private OnionServiceControl control = null;
	@Nullable
	private String authorizedTarget = null;
	@Nullable
	private ProbeDialer probeDialer = null;
	@Nullable
	private volatile String publishedOnion = null;
	private volatile Set<String> publishedKeys = new HashSet<>();
	@Nullable
	private volatile String publishedOldOnion = null;
	private volatile Set<String> publishedOldKeys = new HashSet<>();

	private final Map<ContactId, Cached> cache = new ConcurrentHashMap<>();
	private final Map<ContactId, Long> seenWritten = new ConcurrentHashMap<>();
	private final Map<ContactId, Long> contactWritten =
			new ConcurrentHashMap<>();
	private final java.security.SecureRandom rotationRandom =
			new java.security.SecureRandom();

	long drawRotationPeriodMs() {
		long span = MAX_ROTATION_PERIOD_MS - MIN_ROTATION_PERIOD_MS;
		return MIN_ROTATION_PERIOD_MS
				+ (long) (rotationRandom.nextDouble() * (span + 1));
	}

	static boolean isRotationPeriod(long periodMs) {
		return periodMs >= MIN_ROTATION_PERIOD_MS
				&& periodMs <= MAX_ROTATION_PERIOD_MS;
	}

	static boolean periodicRotationDue(ServiceRecord svc, long now) {
		return svc.onion != null && svc.oldOnion == null
				&& isRotationPeriod(svc.rotationPeriodMs)
				&& now - svc.lastRotationMs > svc.rotationPeriodMs;
	}

	private static final class Cached {

		final State state;
		@Nullable
		final String peerOnion;
		final boolean probing;
		final boolean committed;
		@Nullable
		final String fallbackOnion;

		Cached(State state, @Nullable String peerOnion, boolean probing,
				boolean committed, @Nullable String fallbackOnion) {
			this.fallbackOnion = fallbackOnion;
			this.state = state;
			this.peerOnion = peerOnion;
			this.probing = probing;
			this.committed = committed;
		}
	}

	OnionClientAuthManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			ContactGroupFactory contactGroupFactory,
			MetadataParser metadataParser, OnionAuthStore store,
			CryptoComponent crypto,
			Provider<TransportPropertyManager> transportPropertyManager,
			EventBus eventBus, Clock clock, @IoExecutor Executor ioExecutor,
			TaskScheduler scheduler) {
		this(db, clientHelper, contactGroupFactory, metadataParser, store,
				crypto, transportPropertyManager, eventBus, clock, ioExecutor,
				scheduler, Optional.empty());
	}

	@Inject
	OnionClientAuthManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			ContactGroupFactory contactGroupFactory,
			MetadataParser metadataParser, OnionAuthStore store,
			CryptoComponent crypto,
			Provider<TransportPropertyManager> transportPropertyManager,
			EventBus eventBus, Clock clock, @IoExecutor Executor ioExecutor,
			TaskScheduler scheduler, Optional<OnionAuthDebugHooks> hooks) {
		this.hooks = hooks.orElse(OnionAuthDebugHooks.NONE);
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

		default void repair(ContactId c) {
		}

		default void repairOpen(ContactId c) {
		}
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
		if (cached == null) return;
		boolean round = onion.equals(cached.peerOnion);
		if (!round && !onion.equals(cached.fallbackOnion)) return;
		if (cached.probing && round) {
			ioExecutor.execute(() -> probeSucceeded(c));
		}
		if (cached.state == AUTH_REQUIRED || cached.committed) seen(c);
	}

	@Override
	public void inboundViaAuthorizedService(ContactId c) {
		Cached cached = cache.get(c);
		if (cached == null) return;
		if (cached.probing) ioExecutor.execute(() -> probeSucceeded(c));
		if (cached.state == AUTH_REQUIRED || cached.committed) seen(c);
	}

	private void seen(ContactId c) {
		long now = clock.currentTimeMillis();
		Long written = seenWritten.get(c);
		if (written != null && now - written < SEEN_WRITE_MS) return;
		seenWritten.put(c, now);
		ioExecutor.execute(() -> recordSeen(c, now));
	}

	private void recordSeen(ContactId c, long now) {
		try {
			db.transaction(false, txn -> {
				OnionAuthRecord r = store.load(txn, c);
				if (!r.holdsCommit()) return;
				r.lastSeenMs = Math.max(r.lastSeenMs, now);
				r.silenceRepairs = 0;
				r.nextSilenceRepairMs = 0;
				if (!r.rekeying) {
					r.openDials = 0;
					r.nextOpenDialMs = 0;
				}
				store.save(txn, r);
			});
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private void contacted(ContactId c) {
		long now = clock.currentTimeMillis();
		Long written = contactWritten.get(c);
		if (written != null && now - written < SEEN_WRITE_MS) return;
		contactWritten.put(c, now);
		ioExecutor.execute(() -> recordContact(c, now));
	}

	private void recordContact(ContactId c, long now) {
		try {
			db.transaction(false, txn -> {
				OnionAuthRecord r = store.load(txn, c);
				if (!r.holdsCommit() || r.lastContactMs >= now) return;
				r.lastContactMs = now;
				store.save(txn, r);
			});
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private void requestProbeDial(ContactId c) {
		ProbeDialer dialer;
		synchronized (torLock) {
			dialer = probeDialer;
		}
		if (dialer != null) ioExecutor.execute(() -> dialer.dial(c));
	}

	private void requestRepairDial(ContactId c) {
		ProbeDialer dialer;
		synchronized (torLock) {
			dialer = probeDialer;
		}
		if (dialer != null) ioExecutor.execute(() -> dialer.repair(c));
	}

	private void requestOpenDial(ContactId c) {
		ProbeDialer dialer;
		synchronized (torLock) {
			dialer = probeDialer;
		}
		if (dialer != null) ioExecutor.execute(() -> dialer.repairOpen(c));
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
		store.deleteClearedRecords(txn);
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
		String committedOnion = r.committedPeerOnion;
		boolean authorized = r.authorizesPeer();
		if (committedOnion != null && !committedOnion.equals(peerOnion)) {
			RevocationWork committed = new RevocationWork(c.getId(),
					committedOnion, false);
			txn.attach(() -> ioExecutor.execute(() -> revoke(committed)));
		}
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
		byte[] pub = msg.publicKey;
		if (r.rekeying && pub != null && r.committedPeerKey != null
				&& Arrays.equals(pub, r.committedPeerKey)) {
			returnToCommittedRound(txn, r, false);
			if (r.lockedIn()) answerWithCommit(txn, r);
			return;
		}
		boolean committedRound = r.lockedIn()
				|| (r.awaitsPeerCommit() && !r.rekeying);
		if (committedRound && pub != null && r.peerPublicKey != null
				&& !Arrays.equals(r.peerPublicKey, pub)
				&& (r.rekeyedMs == 0 || clock.currentTimeMillis()
				- r.rekeyedMs >= REKEY_INTERVAL_MS)) {
			startRekey(txn, r, msg);
			return;
		}
		if (r.lockedIn()) {
			answerWithCommit(txn, r);
			return;
		}
		if (pub == null) return;
		boolean newKey = r.peerPublicKey == null
				|| !Arrays.equals(r.peerPublicKey, pub);
		boolean replacesKey = newKey && r.peerPublicKey != null;
		long now = clock.currentTimeMillis();
		if (replacesKey && r.rekeyedMs != 0
				&& now - r.rekeyedMs < REKEY_INTERVAL_MS) {
			return;
		}
		if (r.state == LEGACY) {
			if (hooks.skipsNewNegotiations(txn)) return;
			startNegotiation(txn, r);
		}
		if (r.peerPublicKey != null && !newKey && msg.onion != null
				&& msg.onion.equals(r.peerOnion) && msg.keyVersion == r.peerGen) {
			return;
		}
		if (replacesKey) r.rekeyedMs = now;
		r.peerPublicKey = pub;
		if (msg.onion != null) {
			r.peerOnion = msg.onion;
			r.peerGen = msg.keyVersion;
		}
		if (r.readyReceived && r.peerOnion != null && !r.probing) {
			startProbe(txn, r);
		}
		store.save(txn, r);
		cache(r);
		ContactId id = r.contactId;
		txn.attach(() -> ioExecutor.execute(() -> authorize(id)));
	}

	private void startRekey(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		long now = clock.currentTimeMillis();
		r.committedLocked = r.lockedIn();
		r.committedPeerKey = r.peerPublicKey;
		r.committedPeerOnion = r.peerOnion;
		r.committedPeerGen = r.peerGen;
		r.rekeying = true;
		r.rekeyedMs = now;
		r.peerPublicKey = msg.publicKey;
		if (msg.onion != null) {
			r.peerOnion = msg.onion;
			r.peerGen = msg.keyVersion;
		}
		r.readyReceived = false;
		r.probeSucceeded = false;
		r.peerProbeSucceeded = false;
		r.commitSent = false;
		r.peerCommitReceived = false;
		r.probing = false;
		r.probeUntilMs = 0;
		r.localGen = store.loadService(txn).gen;
		scheduleRepairs(r);
		r.openDials = 0;
		r.nextOpenDialMs = now + REPAIR_DELAY_MS;
		store.save(txn, r);
		cache(r);
		ContactId id = r.contactId;
		seenWritten.remove(id);
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
		if (r.lockedIn()) {
			answerWithCommit(txn, r);
			return;
		}
		if (r.state == LEGACY) return;
		boolean addressKnown = r.peerOnion != null;
		if (addressKnown && msg.keyVersion != r.peerGen
				&& msg.keyVersion != store.loadService(txn).gen) {
			return;
		}
		r.readyReceived = true;
		if (addressKnown) startProbe(txn, r);
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
		if (r.lockedIn()) {
			answerWithCommit(txn, r);
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
		if (r.peerCommitReceived) lockIn(txn, r);
		else scheduleRepairs(r);
	}

	private void lockIn(Transaction txn, OnionAuthRecord r) {
		long now = clock.currentTimeMillis();
		if (r.rekeying) {
			ContactId id = r.contactId;
			txn.attach(() -> ioExecutor.execute(() -> authorize(id)));
		}
		r.committedPeerKey = null;
		r.committedPeerOnion = null;
		r.committedPeerGen = 0;
		r.committedLocked = false;
		r.state = AUTH_REQUIRED;
		r.rekeying = false;
		r.probing = false;
		r.commitResent = false;
		r.nextRepairMs = now + REPAIR_DELAY_MS;
		r.lastSeenMs = now;
		r.openDials = 0;
		r.nextOpenDialMs = 0;
		r.silenceRepairs = 0;
		r.nextSilenceRepairMs = 0;
	}

	private void scheduleRepairs(OnionAuthRecord r) {
		r.repairs = 0;
		r.nextRepairMs = clock.currentTimeMillis() + REPAIR_DELAY_MS;
	}

	private void returnToCommittedRound(Transaction txn, OnionAuthRecord r,
			boolean peerCommitted) throws DbException {
		boolean locked = r.committedLocked || peerCommitted;
		r.peerPublicKey = r.committedPeerKey;
		r.peerOnion = r.committedPeerOnion;
		r.peerGen = r.committedPeerGen;
		r.readyReceived = true;
		r.probeSucceeded = true;
		r.peerProbeSucceeded = true;
		r.commitSent = true;
		r.probing = false;
		if (locked) {
			r.peerCommitReceived = true;
			lockIn(txn, r);
		} else {
			r.peerCommitReceived = false;
			r.rekeying = false;
			r.committedPeerKey = null;
			r.committedPeerOnion = null;
			r.committedPeerGen = 0;
			r.nextRepairMs = clock.currentTimeMillis() + REPAIR_DELAY_MS;
			ContactId id = r.contactId;
			txn.attach(() -> ioExecutor.execute(() -> authorize(id)));
		}
		store.save(txn, r);
		cache(r);
	}

	private static boolean matchesCommittedRound(OnionAuthRecord r,
			OnionAuthRecords.Record msg, ServiceRecord svc) {
		if (r.committedPeerKey == null) return false;
		OnionAuthRecord committed = new OnionAuthRecord(r.contactId);
		committed.state = AUTH_REQUIRED;
		committed.localGen = r.localGen;
		committed.peerGen = r.committedPeerGen;
		committed.dialPrivateKey = r.dialPrivateKey;
		committed.dialPublicKey = r.dialPublicKey;
		committed.peerPublicKey = r.committedPeerKey;
		committed.readyReceived = true;
		committed.probeSucceeded = true;
		committed.peerProbeSucceeded = true;
		return OnionAuthCommitCheck.check(committed, msg, svc.gen, svc.onion)
				== OnionAuthCommitCheck.Verdict.ACCEPT;
	}

	private void rekeyRepair(Transaction txn, OnionAuthRecord r, long now)
			throws DbException {
		sendOffer(txn, r);
		if (r.peerOnion != null) {
			ServiceRecord svc = store.loadService(txn);
			send(txn, r.contactId, OnionAuthRecords.ready(svc.gen),
					OnionAuthRecords.TYPE_READY, svc.gen);
		}
		r.repairs++;
		r.nextRepairMs = now + Math.min(REPAIR_DELAY_MS << r.repairs,
				MAX_REPAIR_DELAY_MS);
		store.save(txn, r);
	}

	private boolean openDialDue(OnionAuthRecord r, long now) {
		if (!r.holdsCommit() || now < r.nextOpenDialMs) return false;
		if (r.rekeying) return r.lastSeenMs <= r.rekeyedMs;
		return now - r.lastSeenMs >= SILENCE_MS + REPAIR_DELAY_MS;
	}

	private boolean silenceRepairDue(OnionAuthRecord r, long now) {
		return r.lockedIn() && now >= r.nextSilenceRepairMs
				&& now - r.lastSeenMs >= SILENCE_MS;
	}

	private void silenceRepair(Transaction txn, OnionAuthRecord r, long now)
			throws DbException {
		r.silenceRepairs++;
		r.nextSilenceRepairMs = now + Math.min(
				REPAIR_DELAY_MS << Math.min(r.silenceRepairs, 16),
				MAX_OPEN_DIAL_DELAY_MS);
		store.save(txn, r);
		ContactId c = r.contactId;
		txn.attach(() -> requestRepairDial(c));
	}

	private void openDial(Transaction txn, OnionAuthRecord r, long now)
			throws DbException {
		if (!r.rekeying && r.openDials == 0) sendOffer(txn, r);
		r.openDials++;
		r.nextOpenDialMs = now + Math.min(
				REPAIR_DELAY_MS << Math.min(r.openDials, 16),
				MAX_OPEN_DIAL_DELAY_MS);
		store.save(txn, r);
		ContactId c = r.contactId;
		txn.attach(() -> requestOpenDial(c));
	}

	private void resendCommit(Transaction txn, OnionAuthRecord r)
			throws DbException {
		if (r.peerOnion == null || r.dialPublicKey == null) return;
		ServiceRecord svc = store.loadService(txn);
		send(txn, r.contactId, OnionAuthRecords.commit(svc.gen, r.peerOnion,
				OnionAuthCommitCheck.fingerprint(r.dialPublicKey)),
				OnionAuthRecords.TYPE_COMMIT, svc.gen);
	}

	private void answerWithCommit(Transaction txn, OnionAuthRecord r)
			throws DbException {
		long now = clock.currentTimeMillis();
		if (r.commitResent && now < r.nextRepairMs) return;
		resendCommit(txn, r);
		r.commitResent = true;
		r.nextRepairMs = now + REPAIR_DELAY_MS;
		store.save(txn, r);
	}

	private void repair(Transaction txn, OnionAuthRecord r, long now)
			throws DbException {
		ServiceRecord svc = store.loadService(txn);
		send(txn, r.contactId, OnionAuthRecords.probeSuccess(svc.gen),
				OnionAuthRecords.TYPE_PROBE_SUCCESS, svc.gen);
		resendCommit(txn, r);
		r.repairs++;
		r.nextRepairMs = now + Math.min(REPAIR_DELAY_MS << r.repairs,
				MAX_REPAIR_DELAY_MS);
		store.save(txn, r);
	}

	private void onCommit(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		if (r.lockedIn()) {
			if (!r.commitResent) answerWithCommit(txn, r);
			return;
		}
		ServiceRecord svc = store.loadService(txn);
		if (r.rekeying && matchesCommittedRound(r, msg, svc)) {
			r.rotateAcked = true;
			returnToCommittedRound(txn, r, true);
			retireOldOnceAcknowledged(txn, svc);
			return;
		}
		OnionAuthCommitCheck.Verdict v = OnionAuthCommitCheck.check(r, msg,
				svc.gen, svc.onion);
		if (v == OnionAuthCommitCheck.Verdict.NOT_READY && r.readyReceived
				&& r.probeSucceeded && !r.peerProbeSucceeded) {
			r.peerProbeSucceeded = true;
			v = OnionAuthCommitCheck.check(r, msg, svc.gen, svc.onion);
		}
		if (v != OnionAuthCommitCheck.Verdict.ACCEPT) return;
		r.peerCommitReceived = true;
		r.rotateAcked = true;
		if (r.state == AUTH_NEGOTIATING) r.state = AUTH_CONFIRMED;
		maybeCommit(txn, r);
		if (r.commitSent && !r.lockedIn()) lockIn(txn, r);
		store.save(txn, r);
		cache(r);
		retireOldOnceAcknowledged(txn, svc);
	}

	private void retireOldOnceAcknowledged(Transaction txn, ServiceRecord svc)
			throws DbException {
		if (svc.oldOnion != null && allAcked(txn)) {
			txn.attach(() -> ioExecutor.execute(this::retireOld));
		}
	}

	private void onRotate(Transaction txn, OnionAuthRecord r,
			OnionAuthRecords.Record msg) throws DbException {
		if (r.state == LEGACY) return;
		if (msg.keyVersion <= r.peerGen) return;
		if (msg.onion != null) {
			r.peerOnion = msg.onion;
		}
		r.peerGen = msg.keyVersion;
		if (r.awaitsPeerCommit()) {
			r.nextRepairMs = clock.currentTimeMillis() + REPAIR_DELAY_MS;
		}
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
		retireOldOnceAcknowledged(txn, svc);
	}

	private boolean allAcked(Transaction txn) throws DbException {
		ServiceRecord svc = store.loadService(txn);
		for (OnionAuthRecord r : store.loadAll(txn)) {
			if (holdsOldAddress(txn, r, svc)) return false;
		}
		return true;
	}

	private boolean holdsOldAddress(Transaction txn, OnionAuthRecord r,
			ServiceRecord svc) throws DbException {
		if (svc.oldOnion == null) return false;
		if (!r.authorizesPeer() || r.rotateAcked) return false;
		if (lockedInSinceRotation(r, svc)) return false;
		return !silentTooLong(txn, r);
	}

	private boolean silentTooLong(Transaction txn, OnionAuthRecord r)
			throws DbException {
		long contact = Math.max(r.lastSeenMs, r.lastContactMs);
		if (contact <= 0) return false;
		if (clock.currentTimeMillis() - contact < OLD_ADDRESS_SILENCE_LIMIT_MS) {
			return false;
		}
		try {
			return !ZtpPoller.isDesignatedDialer(
					db.getContact(txn, r.contactId));
		} catch (NoSuchContactException e) {
			return false;
		}
	}

	private static boolean lockedInSinceRotation(OnionAuthRecord r,
			ServiceRecord svc) {
		return svc.oldOnion != null && r.lockedIn()
				&& r.negotiationStartedMs > svc.oldSinceMs;
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
		boolean dials = cached.state == AUTH_REQUIRED
				|| ((cached.state == AUTH_NEGOTIATING
				|| cached.state == AUTH_CONFIRMED)
				&& (cached.probing || cached.committed));
		if (!dials) return null;
		if (!cached.probing && cached.fallbackOnion != null) {
			return cached.fallbackOnion;
		}
		return cached.peerOnion;
	}

	public OnionAuthDiagnostics diagnostics(ContactId c) throws DbException {
		return db.transactionWithResult(true, txn -> {
			OnionAuthRecord r = store.load(txn, c);
			ServiceRecord svc = store.loadService(txn);
			return new OnionAuthDiagnostics(r.state, r.commitSent, r.repairs,
					r.rekeying, r.silenceRepairs, r.openDials, svc.gen,
					r.peerGen, holdsOldAddress(txn, r, svc));
		});
	}

	public List<ContactId> getContactsHoldingOldAddress() throws DbException {
		long now = clock.currentTimeMillis();
		return db.transactionWithResult(true, txn -> {
			List<ContactId> out = new ArrayList<>();
			ServiceRecord svc = store.loadService(txn);
			if (svc.oldOnion == null || now - svc.oldSinceMs < HOLD_NOTICE_MS) {
				return out;
			}
			for (OnionAuthRecord r : store.loadAll(txn)) {
				if (holdsOldAddress(txn, r, svc)) out.add(r.contactId);
			}
			return out;
		});
	}

	public long getLastContactMs(ContactId c) throws DbException {
		return db.transactionWithResult(true, txn -> {
			OnionAuthRecord r = store.load(txn, c);
			return Math.max(r.lastSeenMs, r.lastContactMs);
		});
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
		if (e instanceof ConnectionOpenedEvent) {
			contacted(((ConnectionOpenedEvent) e).getContactId());
		} else if (e instanceof MessageAddedEvent) {
			ContactId c = ((MessageAddedEvent) e).getContactId();
			if (c != null) contacted(c);
		} else if (e instanceof RemoteTransportPropertiesUpdatedEvent) {
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
					if (hooks.skipsNewNegotiations(txn)) continue;
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
			addCommittedCredential(ctl, r);
			ServiceRecord svc = store.loadService(txn);
			if (!r.lockedIn()) {
				if (serviceChanged || svc.onion != null) sendOffer(txn, r);
				if (credentialInstalled && svc.onion != null) {
					send(txn, c, OnionAuthRecords.ready(svc.gen),
							OnionAuthRecords.TYPE_READY, svc.gen);
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
			if (svc.onion != null || svc.oldOnion != null) {
				publishedOnion = null;
				publishedOldOnion = null;
				removeQuietly(ctl, svc.onion);
				removeQuietly(ctl, svc.oldOnion);
				svc.onion = null;
				clearOld(svc);
				db.transaction(false, txn -> store.saveService(txn, svc));
			}
			return false;
		}
		if (keys.size() > MAX_AUTHORIZED_CLIENTS) {
			throw new OnionServiceControl.CapacityException();
		}
		Set<String> keySet = keySet(keys);
		try {
			republishOld(ctl, target, svc, keys, keySet);
		} catch (IOException ignored) {
		}
		if (svc.onion != null && svc.onion.equals(publishedOnion)
				&& keySet.equals(publishedKeys)) {
			return false;
		}
		publishedOnion = null;
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
		if (!isRotationPeriod(svc.rotationPeriodMs)) {
			svc.rotationPeriodMs = drawRotationPeriodMs();
		}
		db.transaction(false, txn -> store.saveService(txn, svc));
		publishedOnion = p.onion;
		publishedKeys = keySet;
		return true;
	}

	private void republishCurrent(OnionServiceControl ctl, String target,
			ServiceRecord svc, List<byte[]> keys, Set<String> keySet)
			throws IOException {
		if (svc.onion == null || svc.privateKey == null) return;
		if (svc.onion.equals(publishedOnion) && keySet.equals(publishedKeys)) {
			return;
		}
		publishedOnion = null;
		removeQuietly(ctl, svc.onion);
		ctl.publish(svc.privateKey, target, REMOTE_PORT, keys);
		publishedOnion = svc.onion;
		publishedKeys = keySet;
	}

	private void republishOld(OnionServiceControl ctl, String target,
			ServiceRecord svc, List<byte[]> keys, Set<String> keySet)
			throws IOException {
		if (svc.oldOnion == null || svc.oldPrivateKey == null) {
			publishedOldOnion = null;
			return;
		}
		if (svc.oldOnion.equals(publishedOldOnion)
				&& keySet.equals(publishedOldKeys)) {
			return;
		}
		publishedOldOnion = null;
		removeQuietly(ctl, svc.oldOnion);
		ctl.publish(svc.oldPrivateKey, target, REMOTE_PORT, keys);
		publishedOldOnion = svc.oldOnion;
		publishedOldKeys = keySet;
	}

	private static void removeQuietly(OnionServiceControl ctl,
			@Nullable String onion) {
		if (onion == null) return;
		try {
			ctl.remove(onion);
		} catch (IOException ignored) {
		}
	}

	private static void clearOld(ServiceRecord svc) {
		svc.oldOnion = null;
		svc.oldPrivateKey = null;
		svc.oldSinceMs = 0;
	}

	private static Set<String> keySet(List<byte[]> keys) {
		Set<String> set = new HashSet<>();
		for (byte[] k : keys) set.add(Arrays.toString(k));
		return set;
	}

	private List<byte[]> authorizedKeys(Transaction txn) throws DbException {
		List<byte[]> keys = new ArrayList<>();
		for (OnionAuthRecord r : store.loadAll(txn)) {
			if (!r.authorizesPeer()) continue;
			if (r.peerPublicKey != null) keys.add(r.peerPublicKey);
			if (r.rekeying && r.committedPeerKey != null
					&& !Arrays.equals(r.committedPeerKey, r.peerPublicKey)) {
				keys.add(r.committedPeerKey);
			}
		}
		return keys;
	}

	private static void addCommittedCredential(OnionServiceControl ctl,
			OnionAuthRecord r) {
		if (!r.rekeying || r.committedPeerOnion == null
				|| r.dialPrivateKey == null
				|| r.committedPeerOnion.equals(r.peerOnion)) {
			return;
		}
		try {
			ctl.addClientKey(r.committedPeerOnion, r.dialPrivateKey);
		} catch (IOException ignored) {
		}
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
						addCommittedCredential(ctl, r);
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
		publishedOnion = null;
		publishedOldOnion = null;
		try {
			ServiceRecord pending = db.transactionWithResult(true,
					store::loadService);
			if (pending.revocationPending) rotateLocked(true, null);
			republishService(ctl);
		} catch (DbException | IOException ignored) {
		}
		try {
			for (OnionAuthRecord r : db.transactionWithResult(true,
					store::loadAll)) {
				if (r.state == LEGACY || r.state == REVOKED) continue;
				if (r.lockedIn()) {
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
		} catch (DbException ignored) {
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
			if (revocation && revoked == null && !svc.revocationPending) {
				return;
			}
			if (keys.isEmpty()) {
				if (revocation) dropService(ctl, svc);
				return;
			}
			Set<String> keySet = keySet(keys);
			if (svc.oldOnion != null) {
				if (!revocation) return;
				if (!db.transactionWithResult(true, this::allAcked)) {
					try {
						republishCurrent(ctl, target, svc, keys, keySet);
					} catch (IOException ignored) {
					}
					republishOld(ctl, target, svc, keys, keySet);
					return;
				}
				publishedOldOnion = null;
				removeQuietly(ctl, svc.oldOnion);
				clearOld(svc);
				db.transaction(false, txn -> store.saveService(txn, svc));
			}
			long now = clock.currentTimeMillis();
			if (svc.onion != null) {
				if (revocation) republishCurrent(ctl, target, svc, keys, keySet);
				if (svc.onion.equals(publishedOnion)) {
					publishedOldOnion = publishedOnion;
					publishedOldKeys = publishedKeys;
				} else {
					publishedOldOnion = null;
				}
				svc.oldOnion = svc.onion;
				svc.oldPrivateKey = svc.privateKey;
				svc.oldSinceMs = now;
			}
			publishedOnion = null;
			OnionServiceControl.Published p = ctl.publish(null, target,
					REMOTE_PORT, keys);
			publishedOnion = p.onion;
			publishedKeys = keySet;
			svc.onion = p.onion;
			svc.privateKey = p.privateKey;
			svc.gen++;
			svc.lastRotationMs = now;
			svc.rotationPeriodMs = drawRotationPeriodMs();
			svc.revocationPending = false;
			db.transaction(false, txn -> {
				store.saveService(txn, svc);
				for (OnionAuthRecord r : store.loadAll(txn)) {
					if (!r.authorizesPeer()) continue;
					if (revoked != null && r.contactId.equals(revoked)) continue;
					r.rotateAcked = false;
					r.localGen = svc.gen;
					if (r.awaitsPeerCommit()) scheduleRepairs(r);
					store.save(txn, r);
					send(txn, r.contactId, OnionAuthRecords.rotate(svc.gen,
							p.onion, null), OnionAuthRecords.TYPE_ROTATE, svc.gen);
				}
			});
		} catch (DbException | IOException ignored) {
		}
	}

	private void dropService(OnionServiceControl ctl, ServiceRecord svc)
			throws DbException {
		publishedOnion = null;
		publishedOldOnion = null;
		removeQuietly(ctl, svc.onion);
		removeQuietly(ctl, svc.oldOnion);
		svc.onion = null;
		svc.privateKey = null;
		clearOld(svc);
		svc.gen++;
		svc.lastRotationMs = clock.currentTimeMillis();
		svc.rotationPeriodMs = drawRotationPeriodMs();
		svc.revocationPending = false;
		db.transaction(false, txn -> store.saveService(txn, svc));
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
			if (!db.transactionWithResult(true, this::allAcked)) return;
			if (ctl != null) {
				publishedOldOnion = null;
				removeQuietly(ctl, svc.oldOnion);
			}
			clearOld(svc);
			db.transaction(false, txn -> store.saveService(txn, svc));
			if (svc.revocationPending) rotateLocked(true, null);
		} catch (DbException ignored) {
		}
	}

	private void abortNegotiation(Transaction txn, ContactId c)
			throws DbException {
		OnionAuthRecord r = store.load(txn, c);
		if (r.state != AUTH_NEGOTIATING && r.state != AUTH_CONFIRMED) return;
		if (r.commitSent || r.rekeying) return;
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
					if (r.holdsCommit()) {
						if (r.lastSeenMs == 0) {
							r.lastSeenMs = now;
							store.save(txn, r);
						}
						if (silenceRepairDue(r, now)) silenceRepair(txn, r, now);
						if (openDialDue(r, now)) openDial(txn, r, now);
					}
					if (r.lockedIn()) {
						if (!r.commitResent && now >= r.nextRepairMs) {
							answerWithCommit(txn, r);
							txn.attach(() -> requestRepairDial(r.contactId));
						}
						continue;
					}
					if (r.awaitsPeerCommit()) {
						if (r.repairs < MAX_REPAIRS && now >= r.nextRepairMs) {
							repair(txn, r, now);
							txn.attach(() -> requestRepairDial(r.contactId));
						}
						continue;
					}
					if (r.rekeying) {
						if (r.repairs < MAX_REPAIRS && now >= r.nextRepairMs) {
							rekeyRepair(txn, r, now);
							txn.attach(() -> requestRepairDial(r.contactId));
						}
					} else if ((r.state == AUTH_NEGOTIATING
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
							&& r.readyReceived && r.peerOnion != null
							&& !r.lockedIn()
							&& r.state != LEGACY && r.state != REVOKED
							&& now > r.probeUntilMs) {
						startProbe(txn, r);
						store.save(txn, r);
						cache(r);
					}
				}
				ServiceRecord svc = store.loadService(txn);
				retireOldOnceAcknowledged(txn, svc);
				if (svc.revocationPending) {
					txn.attach(() -> ioExecutor.execute(
							() -> rotate(true, null)));
				} else if (unpublished(svc, keySet(authorizedKeys(txn)))) {
					txn.attach(() -> ioExecutor.execute(this::republish));
				}
				if (svc.onion != null && !isRotationPeriod(svc.rotationPeriodMs)) {
					svc.rotationPeriodMs = drawRotationPeriodMs();
					store.saveRotationPeriod(txn, svc.rotationPeriodMs);
				}
				if (periodicRotationDue(svc, now)
						&& !silentSinceLastRotation(txn, svc)) {
					txn.attach(() -> ioExecutor.execute(
							() -> rotate(false, null)));
				}
			});
		} catch (DbException ignored) {
		}
	}

	private boolean unpublished(ServiceRecord svc, Set<String> keySet) {
		if (svc.onion != null && (!svc.onion.equals(publishedOnion)
				|| !keySet.equals(publishedKeys))) {
			return true;
		}
		return svc.oldOnion != null && (!svc.oldOnion.equals(publishedOldOnion)
				|| !keySet.equals(publishedOldKeys));
	}

	private void republish() {
		OnionServiceControl ctl = control();
		if (ctl == null) return;
		synchronized (serviceLock) {
			try {
				republishService(ctl);
			} catch (DbException | IOException | RuntimeException ignored) {
			}
		}
	}

	private boolean silentSinceLastRotation(Transaction txn, ServiceRecord svc)
			throws DbException {
		for (OnionAuthRecord r : store.loadAll(txn)) {
			if (!r.holdsCommit()) continue;
			long contact = Math.max(r.lastSeenMs, r.lastContactMs);
			if (contact <= svc.lastRotationMs) return true;
		}
		return false;
	}

	private void cache(OnionAuthRecord r) {
		cache.put(r.contactId, new Cached(r.state, r.peerOnion, r.probing,
				r.commitSent || r.rekeying, r.fallbackOnion()));
	}

	private void send(Transaction txn, ContactId c, BdfList body, int type,
			long keyVersion) throws DbException {
		if (type == OnionAuthRecords.TYPE_COMMIT
				&& hooks.holdsConfirmations(txn)) {
			return;
		}
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
