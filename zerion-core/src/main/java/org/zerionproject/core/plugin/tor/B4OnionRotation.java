package org.zerionproject.core.plugin.tor;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.event.B4OwnRotationCompletedEvent;
import org.zerionproject.core.api.plugin.event.B4PeerOnionAnnouncedEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.FieldEncryption;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.tor.TorWrapper.HiddenServiceProperties;
import org.zerionproject.transport.ZtpPoller;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.event.ConnectionOpenedEvent;
import org.zerionproject.core.api.sync.event.MessageAddedEvent;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_LAST_ROTATION_TIME_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_NEXT_ANNOUNCED_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ANNOUNCE_FALLBACK_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PENDING_RETRY_MAX_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PENDING_RETRY_MIN_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_CURRENT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_PRIVKEY_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ROTATION_PHASE_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_ONION3_PENDING_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ANNOUNCE_RATE_LIMIT_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PENDING_DIAL_FAILURE_THRESHOLD;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PEER_LAST_CONTACT_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PEER_ROTATION_STATE_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.OLD_ADDRESS_SILENCE_LIMIT_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ROTATION_ENABLED;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.plugin.B4Constants.ROTATION_MAX_DAYS;
import static org.zerionproject.core.api.plugin.B4Constants.ROTATION_MIN_DAYS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_REBROADCAST_DELAYS_SECONDS;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_ANNOUNCED_AT_MS;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_NEXT;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_PUBLISH_NONCE;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;

@Singleton
@ThreadSafe
@NotNullByDefault
public class B4OnionRotation implements EventListener {

	public enum RotationPhase {
		IDLE,
		ANNOUNCING,
	}

	public enum PeerRotationState {
		CURRENT,
		PRE_ANNOUNCED,
		MIGRATED,
	}

	public interface B4TorAdapter {
		HiddenServiceProperties publishHiddenService(@Nullable String privKey)
				throws IOException;

		boolean isPublished(String onion);

		@Nullable
		String getStartupOnion();

		void removeHiddenService(String onion) throws IOException;

		void updateTorCurrentPrivKey(String newPrivKey);

		void mergeTorLocalProperties(TransportProperties props);

		default boolean acceptsOpenInbound(ContactId c) {
			return true;
		}
	}

	public interface DeliveryCheck {
		boolean hasReceived(Transaction txn, ContactId c, String nextOnion)
				throws DbException;
	}

	private static final String B4_ALICE_PROMOTING_SENTINEL_KEY =
			"alice_rotation_promoting";

	static final String B4_ALICE_NEXT_ROTATION_DAYS_KEY =
			"alice_rotation_next_interval_days";

	private static final String B4_ALICE_REVOCATION_OWED_KEY =
			"alice_revocation_owed";

	private static final String B4_ALICE_REVOCATION_ROTATION_KEY =
			"alice_revocation_rotation";

	private static final String B4_ALICE_ANNOUNCE_REFRESHED_AT_KEY =
			"alice_announce_refreshed_at";

	private static final String B4_ALICE_ANNOUNCE_REFRESHES_KEY =
			"alice_announce_refreshes";

	static final long FIRST_REFRESH_AFTER_MS = 60L * 60 * 1000;

	static final long REFRESH_INTERVAL_MS = 24L * 60 * 60 * 1000;

	static final long ANNOUNCED_AT_GRANULARITY_MS = 60L * 60 * 1000;

	static long announcedAtOnWire(long announcedAtMs) {
		return announcedAtMs - Math.floorMod(announcedAtMs,
				ANNOUNCED_AT_GRANULARITY_MS);
	}

	private final java.security.SecureRandom rotationRng =
			new java.security.SecureRandom();

	protected long drawNextRotationDays() {
		int range = (int) (ROTATION_MAX_DAYS - ROTATION_MIN_DAYS + 1);
		return ROTATION_MIN_DAYS + rotationRng.nextInt(range);
	}

	private final Object rotationLock = new Object();

	private final ScheduledExecutorService scheduler;

	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final AccountManager accountManager;
	private final Clock clock;

	@Nullable
	private volatile B4TorAdapter adapter;
	@Nullable
	private volatile java.util.concurrent.ScheduledFuture<?> periodic;
	@Nullable
	private volatile DeliveryCheck deliveryCheck;

	static final long MIGRATION_GRACE_MS = 24L * 60 * 60 * 1000;

	static final long CONTACT_WRITE_INTERVAL_MS = 60L * 60 * 1000;

	private final java.util.Map<ContactId, Long> contactWritten =
			new java.util.concurrent.ConcurrentHashMap<>();

	@Inject
	public B4OnionRotation(DatabaseComponent db,
			SettingsManager settingsManager,
			AccountManager accountManager,
			Clock clock) {
		this(db, settingsManager, accountManager, clock,
				Executors.newSingleThreadScheduledExecutor(r -> {
					Thread t = new Thread(r, "b4-rotation-rebroadcast");
					t.setDaemon(true);
					return t;
				}));
	}

	protected B4OnionRotation(DatabaseComponent db,
			SettingsManager settingsManager, AccountManager accountManager,
			Clock clock, ScheduledExecutorService scheduler) {
		this.db = db;
		this.settingsManager = settingsManager;
		this.accountManager = accountManager;
		this.clock = clock;
		this.scheduler = scheduler;
	}

	public void bindAdapter(B4TorAdapter adapter) {
		this.adapter = adapter;
	}

	public void bindDeliveryCheck(DeliveryCheck check) {
		this.deliveryCheck = check;
	}

	@Override
	public void eventOccurred(Event e) {
		ContactId c = null;
		if (e instanceof ConnectionOpenedEvent) {
			c = ((ConnectionOpenedEvent) e).getContactId();
		} else if (e instanceof MessageAddedEvent) {
			c = ((MessageAddedEvent) e).getContactId();
		}
		if (c == null) return;
		ContactId contact = c;
		long now = clock.currentTimeMillis();
		Long written = contactWritten.get(contact);
		if (written != null && now - written < CONTACT_WRITE_INTERVAL_MS) {
			return;
		}
		contactWritten.put(contact, now);
		try {
			scheduler.execute(() -> recordContact(contact, now));
		} catch (RuntimeException ignored) {
		}
	}

	public void recordContact(ContactId c, long now) {
		try {
			db.transaction(false, txn -> {
				String key = B4_PEER_LAST_CONTACT_KEY_PREFIX + c.getInt();
				if (loadLong(txn, key) >= now) return;
				Settings s = new Settings();
				s.put(key, sealString(String.valueOf(now)));
				settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
			});
		} catch (DbException | RuntimeException ignored) {
		}
	}

	public long getLastContactMs(ContactId c) throws DbException {
		return db.transactionWithResult(true, txn ->
				loadLong(txn, B4_PEER_LAST_CONTACT_KEY_PREFIX + c.getInt()));
	}

	private void seedContactTimes() throws DbException {
		long now = clock.currentTimeMillis();
		db.transaction(false, txn -> {
			Settings s = new Settings();
			for (Contact c : db.getContacts(txn)) {
				if (loadEncryptedString(txn, B4_PEER_LAST_CONTACT_KEY_PREFIX
						+ c.getId().getInt()) == null) {
					s.put(B4_PEER_LAST_CONTACT_KEY_PREFIX
							+ c.getId().getInt(), sealString(String.valueOf(now)));
				}
			}
			if (!s.isEmpty()) {
				settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
			}
		});
	}

	private boolean silentTooLong(Transaction txn, ContactId c, long now)
			throws DbException {
		long last = loadLong(txn, B4_PEER_LAST_CONTACT_KEY_PREFIX
				+ c.getInt());
		return last > 0 && now - last >= OLD_ADDRESS_SILENCE_LIMIT_MS;
	}

	private boolean everyContactInContactSince(Transaction txn, long since)
			throws DbException {
		for (Contact c : db.getContacts(txn)) {
			if (loadLong(txn, B4_PEER_LAST_CONTACT_KEY_PREFIX
					+ c.getId().getInt()) <= since) {
				return false;
			}
		}
		return true;
	}

	public void startPeriodicEvaluation() {
		java.util.concurrent.ScheduledFuture<?> previous = periodic;
		if (previous != null) previous.cancel(false);
		periodic = scheduler.scheduleWithFixedDelay(this::evaluateAll,
				60, 60 * 60, TimeUnit.SECONDS);
	}

	void evaluateAll() {
		try {
			seedContactTimes();
		} catch (DbException | RuntimeException ignored) {
		}
		try {
			republishPendingOnion();
		} catch (DbException | RuntimeException ignored) {
		}
		try {
			announceNext(null);
		} catch (DbException | RuntimeException ignored) {
		}
		try {
			refreshAnnouncement();
		} catch (DbException | RuntimeException ignored) {
		}
		try {
			resumeIfPromotionInterrupted();
		} catch (DbException | RuntimeException ignored) {
		}
		try {
			runOwedRevocation();
		} catch (DbException | RuntimeException ignored) {
		}
		try {
			evaluateTrigger();
		} catch (DbException | RuntimeException ignored) {
		}
		try {
			evaluateForceExpire();
		} catch (DbException | RuntimeException ignored) {
		}
	}

	public void republishPendingOnion() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		B4TorAdapter ad = adapter;
		if (ad == null) return;
		synchronized (rotationLock) {
			String[] next = db.transactionWithResult(true, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) {
					return new String[2];
				}
				return new String[] {
						loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY),
						loadEncryptedString(txn,
								B4_ALICE_ONION3_NEXT_PRIVKEY_KEY)};
			});
			if (next[0] == null || next[1] == null) return;
			if (ad.isPublished(next[0])) return;
			try {
				ad.publishHiddenService(next[1]);
			} catch (IOException e) {
				throw new DbException(e);
			}
		}
	}

	public void evaluateTrigger() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (adapter == null) {
			return;
		}
		synchronized (rotationLock) {
			long now = clock.currentTimeMillis();
			final long[] daysHolder = new long[1];
			final RotationPhase[] phaseHolder = new RotationPhase[1];
			final boolean[] firstRunHolder = new boolean[1];
			boolean shouldRotate =
					db.transactionWithResult(false, txn -> {
				phaseHolder[0] = loadPhase(txn);
				if (phaseHolder[0] != RotationPhase.IDLE) return false;
				String lastRaw = loadEncryptedString(txn,
						B4_ALICE_LAST_ROTATION_TIME_MS_KEY);
				if (lastRaw == null) {
					Settings s = new Settings();
					s.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
							sealString(String.valueOf(now)));
					settingsManager.mergeSettings(txn, s,
							B4_SETTINGS_NAMESPACE);
					firstRunHolder[0] = true;
					return false;
				}
				long last;
				try {
					last = Long.parseLong(lastRaw);
				} catch (NumberFormatException e) {
					last = now;
				}
				if (!everyContactInContactSince(txn, last)) return false;
				long days = DAYS.convert(now - last,
						java.util.concurrent.TimeUnit.MILLISECONDS);
				daysHolder[0] = days;
				long targetDays;
				String targetRaw = loadEncryptedString(txn,
						B4_ALICE_NEXT_ROTATION_DAYS_KEY);
				if (targetRaw == null) {
					targetDays = drawNextRotationDays();
					Settings tgt = new Settings();
					tgt.put(B4_ALICE_NEXT_ROTATION_DAYS_KEY,
							sealString(String.valueOf(targetDays)));
					settingsManager.mergeSettings(txn, tgt,
							B4_SETTINGS_NAMESPACE);
				} else {
					try {
						targetDays = Long.parseLong(targetRaw);
					} catch (NumberFormatException e) {
						targetDays = drawNextRotationDays();
					}
					if (targetDays < ROTATION_MIN_DAYS
							|| targetDays > ROTATION_MAX_DAYS) {
						targetDays = drawNextRotationDays();
					}
				}
				return days >= targetDays;
			});
			if (shouldRotate) executeRotation(now);
		}
	}

	public boolean forceRotate() throws DbException {
		if (!B4_ROTATION_ENABLED) return false;
		if (adapter == null) {
			return false;
		}
		synchronized (rotationLock) {
			long now = clock.currentTimeMillis();
			boolean shouldRotate = db.transactionWithResult(true, txn ->
					loadPhase(txn) == RotationPhase.IDLE);
			return shouldRotate && executeRotation(now);
		}
	}

	public void revokeAfterContactRemoval() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		boolean owed = db.transactionWithResult(true, txn ->
				loadEncryptedString(txn, B4_ALICE_REVOCATION_OWED_KEY)
						!= null);
		if (!owed) db.transaction(false, this::recordRevocationOwed);
		runOwedRevocation();
	}

	private void recordRevocationOwed(Transaction txn) throws DbException {
		Settings s = new Settings();
		s.put(B4_ALICE_REVOCATION_OWED_KEY, sealString("1"));
		settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
	}

	public void runOwedRevocation() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		B4TorAdapter ad = adapter;
		if (ad == null) return;
		synchronized (rotationLock) {
			boolean owed = db.transactionWithResult(true, txn ->
					loadEncryptedString(txn, B4_ALICE_REVOCATION_OWED_KEY)
							!= null);
			if (!owed) return;
			RotationPhase phase = db.transactionWithResult(true,
					this::loadPhase);
			if (phase == RotationPhase.ANNOUNCING) abandonPendingNext(ad);
			long now = clock.currentTimeMillis();
			if (!executeRotation(now)) return;
			db.transaction(false, txn -> {
				Settings s = new Settings();
				s.put(B4_ALICE_REVOCATION_OWED_KEY, "");
				s.put(B4_ALICE_REVOCATION_ROTATION_KEY, sealString("1"));
				settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
			});
			boolean retire = db.transactionWithResult(true,
					this::shouldRetireOldOnion);
			if (retire) executePromotion();
		}
	}

	private void abandonPendingNext(B4TorAdapter ad) throws DbException {
		String next = db.transactionWithNullableResult(true, txn ->
				loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY));
		if (next != null && ad.isPublished(next)) {
			try {
				ad.removeHiddenService(next);
			} catch (IOException e) {
				throw new DbException(e);
			}
		}
		db.transaction(false, txn -> {
			Settings s = new Settings();
			s.put(B4_ALICE_ONION3_NEXT_KEY, "");
			s.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY, "");
			s.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY, "");
			s.put(B4_ALICE_NEXT_ANNOUNCED_KEY, "");
			s.put(B4_ALICE_ROTATION_PHASE_KEY,
					sealString(RotationPhase.IDLE.name()));
			settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
		});
		TransportProperties withdrawn = new TransportProperties();
		withdrawn.put(WIRE_KEY_ONION3_NEXT, "");
		withdrawn.put(WIRE_KEY_ONION3_ANNOUNCED_AT_MS, "");
		withdrawn.put(WIRE_KEY_ONION3_PUBLISH_NONCE, "");
		ad.mergeTorLocalProperties(withdrawn);
	}

	public void onAnnounceReceived(Transaction txn, ContactId from,
			String pendingOnion, long announcedAtMs) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (!isValidV3Onion(pendingOnion)) {
			return;
		}
		String existingPending = loadEncryptedString(txn,
				B4_CONTACT_ONION3_PENDING_KEY_PREFIX + from.getInt());
		if (pendingOnion.equals(existingPending)) {
			if (loadEncryptedString(txn, B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX
					+ from.getInt()) != null) {
				Settings retryNow = new Settings();
				retryNow.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX
						+ from.getInt(), "");
				settingsManager.mergeSettings(txn, retryNow,
						B4_SETTINGS_NAMESPACE);
			}
			return;
		}
		boolean notify = true;
		String lastRaw = loadEncryptedString(txn,
				B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX + from.getInt());
		if (lastRaw != null) {
			try {
				long lastMs = Long.parseLong(lastRaw);
				long now = clock.currentTimeMillis();
				if (now - lastMs < B4_ANNOUNCE_RATE_LIMIT_MS) {
					notify = false;
				}
			} catch (NumberFormatException ignored) {
			}
		}
		Settings update = new Settings();
		update.put(B4_CONTACT_ONION3_PENDING_KEY_PREFIX + from.getInt(),
				sealString(pendingOnion));
		if (notify) {
			update.put(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX
							+ from.getInt(),
					sealString(String.valueOf(clock.currentTimeMillis())));
		}
		update.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX
						+ from.getInt(), "");
		update.put(B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX
						+ from.getInt(), "");
		update.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + from.getInt(), "");
		settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
		if (notify) txn.attach(new B4PeerOnionAnnouncedEvent(from));
	}

	public void onSuccessfulConnect(ContactId cid, String dialedOnion)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (dialedOnion == null) return;
		db.transaction(false, txn -> {
			String pending = loadEncryptedString(txn,
					B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt());
			if (pending == null || !pending.equals(dialedOnion)) return;
			boolean alreadySucceeded = "1".equals(loadEncryptedString(txn,
					B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX
							+ cid.getInt()));
			boolean clean = loadPendingDialFailures(txn, cid) == 0
					&& loadEncryptedString(txn,
					B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + cid.getInt())
					== null;
			if (alreadySucceeded && clean) return;
			Settings update = new Settings();
			update.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX
					+ cid.getInt(), "");
			update.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX
					+ cid.getInt(), "");
			update.put(B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX
					+ cid.getInt(), sealString("1"));
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
		});
	}

	public void onFallbackDialFailed(ContactId cid) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		db.transaction(false, txn -> {
			if (loadEncryptedString(txn, B4_CONTACT_ONION3_PENDING_KEY_PREFIX
					+ cid.getInt()) == null) {
				return;
			}
			if (loadEncryptedString(txn, B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX
					+ cid.getInt()) == null) {
				return;
			}
			Settings retryNow = new Settings();
			retryNow.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + cid.getInt(),
					"");
			settingsManager.mergeSettings(txn, retryNow,
					B4_SETTINGS_NAMESPACE);
		});
	}

	public void onPeerRotationComplete(Transaction txn, ContactId cid,
			String newCurrentOnion) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (newCurrentOnion == null || newCurrentOnion.isEmpty()) return;
		String pending = loadEncryptedString(txn,
				B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt());
		if (pending == null || !pending.equals(newCurrentOnion)) return;
		clearPending(txn, cid, true);
	}

	public void onPeerNextWithdrawn(Transaction txn, ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (loadEncryptedString(txn, B4_CONTACT_ONION3_PENDING_KEY_PREFIX
				+ cid.getInt()) == null) {
			return;
		}
		clearPending(txn, cid, false);
	}

	private void clearPending(Transaction txn, ContactId cid,
			boolean notificationTime) throws DbException {
		Settings clear = new Settings();
		clear.put(B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt(), "");
		if (notificationTime) {
			clear.put(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX
					+ cid.getInt(), "");
		}
		clear.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX
				+ cid.getInt(), "");
		clear.put(B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX
				+ cid.getInt(), "");
		clear.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + cid.getInt(), "");
		settingsManager.mergeSettings(txn, clear, B4_SETTINGS_NAMESPACE);
	}

	public void onPendingDialFailed(ContactId cid) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		db.transaction(false, txn -> {
			int current = loadPendingDialFailures(txn, cid);
			int next = current + 1;
			Settings update = new Settings();
			update.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX
							+ cid.getInt(),
					sealString(String.valueOf(next)));
			if (next >= B4_PENDING_DIAL_FAILURE_THRESHOLD) {
				long retryAt = clock.currentTimeMillis()
						+ pendingRetryDelayMs(next);
				update.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX
						+ cid.getInt(), sealString(String.valueOf(retryAt)));
			}
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
		});
	}

	static long pendingRetryDelayMs(int failures) {
		int steps = Math.max(0, Math.min(failures
				- B4_PENDING_DIAL_FAILURE_THRESHOLD, 4));
		return Math.min(B4_PENDING_RETRY_MIN_MS << steps,
				B4_PENDING_RETRY_MAX_MS);
	}

	private int loadPendingDialFailures(Transaction txn, ContactId cid)
			throws DbException {
		String stored = loadEncryptedString(txn,
				B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX + cid.getInt());
		if (stored == null || stored.isEmpty()) return 0;
		try {
			return Integer.parseInt(stored);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	public void onPeerSyncSessionEstablished(ContactId cid,
			@Nullable String inboundOnion) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		boolean shouldComplete;
		synchronized (rotationLock) {
			shouldComplete = db.transactionWithResult(false, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return false;
				PeerRotationState state = loadPeerState(txn, cid);
				String next = loadEncryptedString(txn,
						B4_ALICE_ONION3_NEXT_KEY);
				if (state != PeerRotationState.MIGRATED
						&& inboundOnion != null
						&& inboundOnion.equals(next)) {
					setPeerState(txn, cid, PeerRotationState.MIGRATED);
				} else if (state == PeerRotationState.CURRENT) {
					setPeerState(txn, cid, PeerRotationState.PRE_ANNOUNCED);
				}
				return shouldRetireOldOnion(txn);
			});
			if (shouldComplete) executePromotion();
		}
	}

	private long loadAnnouncedAtMs(Transaction txn) throws DbException {
		String raw = loadEncryptedString(txn, B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY);
		if (raw == null) return 0L;
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	public void markPeerMigrated(ContactId cid) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		db.transaction(false, txn -> {
			Settings clear = new Settings();
			clear.put(B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt(), "");
			clear.put(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX
					+ cid.getInt(), "");
			settingsManager.mergeSettings(txn, clear, B4_SETTINGS_NAMESPACE);
		});
	}

	public void contactRemoved(Transaction txn, ContactId cid)
			throws DbException {
		if (B4_ROTATION_ENABLED) recordRevocationOwed(txn);
		Settings stored = settingsManager.getSettings(txn,
				B4_SETTINGS_NAMESPACE);
		Settings clear = new Settings();
		for (String prefix : new String[] {
				B4_CONTACT_ONION3_PENDING_KEY_PREFIX,
				B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX,
				B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX,
				B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX,
				B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX,
				B4_PEER_LAST_CONTACT_KEY_PREFIX,
				B4_PEER_ROTATION_STATE_KEY_PREFIX}) {
			String key = prefix + cid.getInt();
			String value = stored.get(key);
			if (value != null && !value.isEmpty()) clear.put(key, "");
		}
		if (!clear.isEmpty()) {
			settingsManager.mergeSettings(txn, clear, B4_SETTINGS_NAMESPACE);
		}
	}

	@Nullable
	public String getPendingOnionForContact(ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return null;
		return db.transactionWithNullableResult(true, txn ->
				loadEncryptedString(txn,
						B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt()));
	}

	public RotationPhase getPhase() throws DbException {
		if (!B4_ROTATION_ENABLED) return RotationPhase.IDLE;
		return db.transactionWithResult(true, this::loadPhase);
	}

	@Nullable
	public String getAliceNextOnion() throws DbException {
		if (!B4_ROTATION_ENABLED) return null;
		return db.transactionWithNullableResult(true, txn ->
				loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY));
	}

	public long getLastRotationTimeMs() throws DbException {
		if (!B4_ROTATION_ENABLED) return 0L;
		return db.transactionWithResult(true, this::loadLastRotationTimeMs);
	}

	public B4RotationDiagnostics diagnostics(ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) {
			return new B4RotationDiagnostics(RotationPhase.IDLE, false,
					PeerRotationState.CURRENT, false, 0L);
		}
		return db.transactionWithResult(true, txn -> {
			int id = cid.getInt();
			boolean pending = loadEncryptedString(txn,
					B4_CONTACT_ONION3_PENDING_KEY_PREFIX + id) != null;
			boolean suppressed = pending
					&& getPendingOnionForContact(txn, cid) == null;
			String retry = suppressed ? loadEncryptedString(txn,
					B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + id) : null;
			boolean announced = loadEncryptedString(txn,
					B4_ALICE_NEXT_ANNOUNCED_KEY) != null;
			RotationPhase phase = loadPhase(txn);
			PeerRotationState state = loadPeerState(txn, cid);
			if (phase == RotationPhase.ANNOUNCING
					&& state != PeerRotationState.MIGRATED
					&& hasMovedToNextOnion(txn, db.getContact(txn, cid),
					loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY))) {
				state = PeerRotationState.MIGRATED;
			}
			return new B4RotationDiagnostics(phase, announced, state, pending,
					retry == null ? 0L : Long.parseLong(retry));
		});
	}

	@Nullable
	public String getPendingOnionForContact(Transaction txn, ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return null;
		String pending = loadEncryptedString(txn,
				B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt());
		if (pending == null) return null;
		if (loadPendingDialFailures(txn, cid)
				< B4_PENDING_DIAL_FAILURE_THRESHOLD) {
			return pending;
		}
		String retryRaw = loadEncryptedString(txn,
				B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + cid.getInt());
		if (retryRaw == null) return pending;
		long retryAt;
		try {
			retryAt = Long.parseLong(retryRaw);
		} catch (NumberFormatException e) {
			return pending;
		}
		long now = clock.currentTimeMillis();
		if (retryAt <= now || retryAt - now > B4_PENDING_RETRY_MAX_MS) {
			return pending;
		}
		return null;
	}

	public void resumeIfPromotionInterrupted() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (adapter == null) return;
		synchronized (rotationLock) {
			boolean sentinelSet = db.transactionWithResult(true, txn ->
					loadEncryptedString(txn,
							B4_ALICE_PROMOTING_SENTINEL_KEY) != null);
			if (sentinelSet) executePromotion();
		}
	}

	public void evaluateForceExpire() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (adapter == null) return;
		synchronized (rotationLock) {
			boolean shouldComplete = db.transactionWithResult(true, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return false;
				return shouldRetireOldOnion(txn);
			});
			if (shouldComplete) executePromotion();
		}
	}

	public void shutdown() {
		adapter = null;
		java.util.concurrent.ScheduledFuture<?> p = periodic;
		if (p != null) p.cancel(false);
		periodic = null;
	}

	boolean forceCompleteRotation() throws DbException {
		if (!B4_ROTATION_ENABLED) return false;
		if (adapter == null) return false;
		synchronized (rotationLock) {
			RotationPhase phase = db.transactionWithResult(true,
					this::loadPhase);
			if (phase != RotationPhase.ANNOUNCING) {
				return false;
			}
			executePromotion();
			return true;
		}
	}

	private boolean executeRotation(long now) throws DbException {
		B4TorAdapter ad = adapter;
		if (ad == null) return false;

		HiddenServiceProperties hsProps;
		try {
			hsProps = ad.publishHiddenService(null);
		} catch (IOException e) {
			throw new DbException(e);
		}

		String newOnion = hsProps.onion;
		String newPrivKey = hsProps.privKey;
		List<ContactId> contactIds = new ArrayList<>();

		db.transaction(false, txn -> {
			Settings keyOnly = new Settings();
			keyOnly.put(B4_ALICE_ONION3_NEXT_KEY, sealString(newOnion));
			keyOnly.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY,
					sealString(newPrivKey));
			settingsManager.mergeSettings(txn, keyOnly,
					B4_SETTINGS_NAMESPACE);
		});

		db.transaction(false, txn -> {
			Settings update = new Settings();
			update.put(B4_ALICE_ROTATION_PHASE_KEY,
					sealString(RotationPhase.ANNOUNCING.name()));
			update.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY,
					sealString(String.valueOf(now)));
			update.put(B4_ALICE_NEXT_ANNOUNCED_KEY, "");
			update.put(B4_ALICE_ANNOUNCE_REFRESHED_AT_KEY, "");
			update.put(B4_ALICE_ANNOUNCE_REFRESHES_KEY, "");
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
			Collection<Contact> contacts = db.getContacts(txn);
			for (Contact c : contacts) {
				setPeerState(txn, c.getId(), PeerRotationState.CURRENT);
				contactIds.add(c.getId());
			}
		});

		scheduleAnnounceFallback();
		return true;
	}

	public void onDescriptorUploaded(String onion) {
		if (!B4_ROTATION_ENABLED) return;
		try {
			announceNext(onion);
		} catch (DbException | RuntimeException ignored) {
		}
	}

	private void scheduleAnnounceFallback() {
		try {
			scheduler.schedule(() -> {
				try {
					announceNext(null);
				} catch (DbException | RuntimeException ignored) {
				}
			}, B4_ANNOUNCE_FALLBACK_MS + 1000L, TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.RejectedExecutionException ignored) {
		}
	}

	void announceNext(@Nullable String uploadedOnion) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		B4TorAdapter ad = adapter;
		if (ad == null) return;
		synchronized (rotationLock) {
			String[] state = db.transactionWithNullableResult(true, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return null;
				String next = loadEncryptedString(txn,
						B4_ALICE_ONION3_NEXT_KEY);
				if (next == null) return null;
				boolean announced = loadEncryptedString(txn,
						B4_ALICE_NEXT_ANNOUNCED_KEY) != null;
				return new String[] {next,
						String.valueOf(loadAnnouncedAtMs(txn)),
						announced ? "1" : null};
			});
			if (state == null) return;
			if (!ad.isPublished(state[0])) return;
			long rotatedAt = Long.parseLong(state[1]);
			TransportProperties props = new TransportProperties();
			props.put(WIRE_KEY_ONION3_NEXT, state[0]);
			props.put(WIRE_KEY_ONION3_ANNOUNCED_AT_MS,
					String.valueOf(announcedAtOnWire(rotatedAt)));
			if (state[2] != null) {
				if (uploadedOnion == null) ad.mergeTorLocalProperties(props);
				return;
			}
			if (uploadedOnion == null) {
				if (clock.currentTimeMillis() - rotatedAt
						< B4_ANNOUNCE_FALLBACK_MS) {
					return;
				}
			} else if (!uploadedOnion.equals(state[0])) {
				return;
			}
			props.put(WIRE_KEY_ONION3_PUBLISH_NONCE, "0");
			ad.mergeTorLocalProperties(props);
			db.transaction(false, txn -> {
				Settings s = new Settings();
				s.put(B4_ALICE_NEXT_ANNOUNCED_KEY, sealString("1"));
				settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
			});
			scheduleRebroadcasts(rotatedAt);
		}
	}

	void refreshAnnouncement() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		B4TorAdapter ad = adapter;
		if (ad == null) return;
		synchronized (rotationLock) {
			long now = clock.currentTimeMillis();
			String[] state = db.transactionWithNullableResult(true, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return null;
				if (loadEncryptedString(txn, B4_ALICE_NEXT_ANNOUNCED_KEY)
						== null) {
					return null;
				}
				String next = loadEncryptedString(txn,
						B4_ALICE_ONION3_NEXT_KEY);
				if (next == null) return null;
				long announcedAt = loadAnnouncedAtMs(txn);
				long last = loadLong(txn, B4_ALICE_ANNOUNCE_REFRESHED_AT_KEY);
				boolean due = last == 0
						? now - announcedAt >= FIRST_REFRESH_AFTER_MS
						: now - last >= REFRESH_INTERVAL_MS;
				if (!due) return null;
				boolean waiting = false;
				for (Contact c : db.getContacts(txn)) {
					if (!hasMovedToNextOnion(txn, c, next)) waiting = true;
				}
				if (!waiting) return null;
				return new String[] {next, String.valueOf(announcedAt),
						String.valueOf(loadLong(txn,
								B4_ALICE_ANNOUNCE_REFRESHES_KEY) + 1)};
			});
			if (state == null || !ad.isPublished(state[0])) return;
			long count = Long.parseLong(state[2]);
			TransportProperties props = new TransportProperties();
			props.put(WIRE_KEY_ONION3_NEXT, state[0]);
			props.put(WIRE_KEY_ONION3_ANNOUNCED_AT_MS, String.valueOf(
					announcedAtOnWire(Long.parseLong(state[1]))));
			props.put(WIRE_KEY_ONION3_PUBLISH_NONCE, String.valueOf(
					B4_REBROADCAST_DELAYS_SECONDS.length + count));
			ad.mergeTorLocalProperties(props);
			db.transaction(false, txn -> {
				Settings s = new Settings();
				s.put(B4_ALICE_ANNOUNCE_REFRESHED_AT_KEY,
						sealString(String.valueOf(now)));
				s.put(B4_ALICE_ANNOUNCE_REFRESHES_KEY,
						sealString(String.valueOf(count)));
				settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
			});
		}
	}

	private long loadLong(Transaction txn, String key) throws DbException {
		String raw = loadEncryptedString(txn, key);
		if (raw == null) return 0L;
		try {
			return Long.parseLong(raw);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private boolean scheduledRotationDue(Transaction txn, long now, long last)
			throws DbException {
		long targetDays = loadLong(txn, B4_ALICE_NEXT_ROTATION_DAYS_KEY);
		if (targetDays < ROTATION_MIN_DAYS || targetDays > ROTATION_MAX_DAYS) {
			targetDays = ROTATION_MIN_DAYS;
		}
		return DAYS.convert(now - last, TimeUnit.MILLISECONDS) >= targetDays;
	}

	public List<ContactId> getContactsHoldingRotation() throws DbException {
		List<ContactId> out = new ArrayList<>();
		if (!B4_ROTATION_ENABLED) return out;
		long now = clock.currentTimeMillis();
		return db.transactionWithResult(true, txn -> {
			RotationPhase phase = loadPhase(txn);
			if (phase == RotationPhase.IDLE) {
				long last = loadLong(txn, B4_ALICE_LAST_ROTATION_TIME_MS_KEY);
				if (last <= 0 || !scheduledRotationDue(txn, now, last)) {
					return out;
				}
				for (Contact c : db.getContacts(txn)) {
					long contact = loadLong(txn,
							B4_PEER_LAST_CONTACT_KEY_PREFIX
									+ c.getId().getInt());
					if (contact > 0 && contact <= last) out.add(c.getId());
				}
				return out;
			}
			if (phase != RotationPhase.ANNOUNCING) return out;
			if (now - loadAnnouncedAtMs(txn) < MIGRATION_GRACE_MS) return out;
			String next = loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY);
			for (Contact c : db.getContacts(txn)) {
				if (!hasMovedToNextOnion(txn, c, next)) out.add(c.getId());
			}
			return out;
		});
	}

	private void scheduleRebroadcasts(long announcedAtMs) {
		for (int i = 0; i < B4_REBROADCAST_DELAYS_SECONDS.length; i++) {
			final int waveIndex = i + 1;
			final long delaySeconds = B4_REBROADCAST_DELAYS_SECONDS[i];
			scheduler.schedule(
					() -> tryRebroadcast(announcedAtMs, waveIndex,
							delaySeconds),
					delaySeconds, TimeUnit.SECONDS);
		}
	}

	private void tryRebroadcast(long announcedAtMs, int waveIndex,
			long delaySeconds) {
		if (!B4_ROTATION_ENABLED) return;
		B4TorAdapter ad = adapter;
		if (ad == null) return;

		final boolean[] shouldFire = new boolean[1];
		final String[] state = new String[3];

		synchronized (rotationLock) {
			try {
				db.transaction(true, txn -> {
					if (loadPhase(txn) != RotationPhase.ANNOUNCING) return;
					String storedAnnouncedAt = loadEncryptedString(txn,
							B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY);
					if (storedAnnouncedAt == null) return;
					long parsed;
					try {
						parsed = Long.parseLong(storedAnnouncedAt);
					} catch (NumberFormatException e) {
						return;
					}
					if (parsed != announcedAtMs) return;
					String newOnion = loadEncryptedString(txn,
							B4_ALICE_ONION3_NEXT_KEY);
					if (newOnion == null) return;
					Collection<Contact> contacts = db.getContacts(txn);
					if (contacts.isEmpty()) return;
					int nonMigrated = 0;
					for (Contact c : contacts) {
						if (loadPeerState(txn, c.getId())
								!= PeerRotationState.MIGRATED) {
							nonMigrated++;
						}
					}
					if (nonMigrated == 0) return;
					state[0] = newOnion;
					state[1] = storedAnnouncedAt;
					state[2] = String.valueOf(nonMigrated);
					shouldFire[0] = true;
				});
			} catch (DbException e) {
				return;
			}
		}

		if (!shouldFire[0]) return;

		TransportProperties props = new TransportProperties();
		props.put(WIRE_KEY_ONION3_PUBLISH_NONCE, String.valueOf(waveIndex));
		ad.mergeTorLocalProperties(props);
	}

	private void executePromotion() throws DbException {
		B4TorAdapter ad = adapter;
		if (ad == null) return;

		final String[] state = new String[3];
		db.transaction(false, txn -> {
			state[0] = loadEncryptedString(txn, B4_ALICE_ONION3_CURRENT_KEY);
			state[1] = loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY);
			state[2] = loadEncryptedString(txn,
					B4_ALICE_ONION3_NEXT_PRIVKEY_KEY);
			Settings sentinel = new Settings();
			sentinel.put(B4_ALICE_PROMOTING_SENTINEL_KEY, sealString("1"));
			settingsManager.mergeSettings(txn, sentinel,
					B4_SETTINGS_NAMESPACE);
		});

		String oldOnion = state[0] != null ? state[0] : ad.getStartupOnion();
		String newOnion = state[1];
		String newPrivKey = state[2];

		if (newOnion != null && newPrivKey != null
				&& !ad.isPublished(newOnion)) {
			try {
				ad.publishHiddenService(newPrivKey);
			} catch (IOException e) {
				throw new DbException(e);
			}
		}

		if (oldOnion != null && newOnion != null && newPrivKey != null
				&& !oldOnion.equals(newOnion) && ad.isPublished(oldOnion)) {
			try {
				ad.removeHiddenService(oldOnion);
			} catch (IOException e) {
				throw new DbException(e);
			}
		}

		if (newOnion != null && newPrivKey != null) {
			ad.updateTorCurrentPrivKey(newPrivKey);
			TransportProperties props = new TransportProperties();
			props.put(WIRE_KEY_ONION3, newOnion);
			props.put(WIRE_KEY_ONION3_NEXT, "");
			props.put(WIRE_KEY_ONION3_ANNOUNCED_AT_MS, "");
			props.put(WIRE_KEY_ONION3_PUBLISH_NONCE, "");
			ad.mergeTorLocalProperties(props);
		}

		long completionTime = clock.currentTimeMillis();
		db.transaction(false, txn -> {
			Settings update = new Settings();
			if (newOnion != null) {
				update.put(B4_ALICE_ONION3_CURRENT_KEY, sealString(newOnion));
			}
			update.put(B4_ALICE_ONION3_NEXT_KEY, "");
			update.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY, "");
			update.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY, "");
			update.put(B4_ALICE_NEXT_ANNOUNCED_KEY, "");
			update.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
					sealString(String.valueOf(completionTime)));
			update.put(B4_ALICE_ROTATION_PHASE_KEY,
					sealString(RotationPhase.IDLE.name()));
			update.put(B4_ALICE_PROMOTING_SENTINEL_KEY, "");
			update.put(B4_ALICE_REVOCATION_ROTATION_KEY, "");
			update.put(B4_ALICE_ANNOUNCE_REFRESHED_AT_KEY, "");
			update.put(B4_ALICE_ANNOUNCE_REFRESHES_KEY, "");
			update.put(B4_ALICE_NEXT_ROTATION_DAYS_KEY, "");
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
			Collection<Contact> contacts = db.getContacts(txn);
			for (Contact c : contacts) {
				setPeerState(txn, c.getId(), PeerRotationState.CURRENT);
			}
			txn.attach(new B4OwnRotationCompletedEvent());
		});
	}

	private boolean shouldRetireOldOnion(Transaction txn) throws DbException {
		long now = clock.currentTimeMillis();
		String announcedRaw = loadEncryptedString(txn,
				B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY);
		long announcedAt;
		if (announcedRaw == null) {
			announcedAt = now;
		} else {
			try {
				announcedAt = Long.parseLong(announcedRaw);
			} catch (NumberFormatException e) {
				announcedAt = now;
			}
		}
		boolean revocation = loadEncryptedString(txn,
				B4_ALICE_REVOCATION_ROTATION_KEY) != null;
		boolean graceOver = now - announcedAt >= MIGRATION_GRACE_MS;
		Collection<Contact> contacts = db.getContacts(txn);
		if (contacts.isEmpty()) return revocation || graceOver;
		if (!graceOver) return false;
		String next = loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY);
		for (Contact c : contacts) {
			if (!hasMovedToNextOnion(txn, c, next)) return false;
		}
		return true;
	}

	private boolean hasMovedToNextOnion(Transaction txn, Contact c,
			@Nullable String next) throws DbException {
		if (loadPeerState(txn, c.getId()) == PeerRotationState.MIGRATED) {
			return true;
		}
		if (next == null) return false;
		if (!ZtpPoller.isDesignatedDialer(c)
				&& silentTooLong(txn, c.getId(), clock.currentTimeMillis())) {
			return true;
		}
		B4TorAdapter ad = adapter;
		boolean dialsOurOpenOnion = !ZtpPoller.isDesignatedDialer(c)
				&& (ad == null || ad.acceptsOpenInbound(c.getId()));
		if (dialsOurOpenOnion) return false;
		DeliveryCheck check = deliveryCheck;
		return check != null && check.hasReceived(txn, c.getId(), next);
	}

	private boolean hasActiveContacts(Transaction txn) throws DbException {
		return !db.getContacts(txn).isEmpty();
	}

	private static boolean isValidV3Onion(String s) {
		if (s.length() != 56) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean lower = c >= 'a' && c <= 'z';
			boolean digit = c >= '2' && c <= '7';
			if (!lower && !digit) return false;
		}
		return s.charAt(55) == 'd';
	}

	private RotationPhase loadPhase(Transaction txn) throws DbException {
		String stored = loadEncryptedString(txn, B4_ALICE_ROTATION_PHASE_KEY);
		if (stored == null) return RotationPhase.IDLE;
		try {
			return RotationPhase.valueOf(stored);
		} catch (IllegalArgumentException e) {
			return RotationPhase.IDLE;
		}
	}

	private long loadLastRotationTimeMs(Transaction txn) throws DbException {
		String stored = loadEncryptedString(txn,
				B4_ALICE_LAST_ROTATION_TIME_MS_KEY);
		if (stored == null) return 0L;
		try {
			return Long.parseLong(stored);
		} catch (NumberFormatException e) {
			return 0L;
		}
	}

	private PeerRotationState loadPeerState(Transaction txn, ContactId cid)
			throws DbException {
		String stored = loadEncryptedString(txn,
				B4_PEER_ROTATION_STATE_KEY_PREFIX + cid.getInt());
		if (stored == null) return PeerRotationState.CURRENT;
		try {
			return PeerRotationState.valueOf(stored);
		} catch (IllegalArgumentException e) {
			return PeerRotationState.CURRENT;
		}
	}

	private void setPeerState(Transaction txn, ContactId cid,
			PeerRotationState state) throws DbException {
		Settings s = new Settings();
		s.put(B4_PEER_ROTATION_STATE_KEY_PREFIX + cid.getInt(),
				sealString(state.name()));
		settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
	}

	@Nullable
	private String loadEncryptedString(Transaction txn, String key)
			throws DbException {
		Settings s = settingsManager.getSettings(txn, B4_SETTINGS_NAMESPACE);
		String hex = s.get(key);
		if (hex == null || hex.isEmpty()) return null;
		SecretKey fieldKey = accountManager.getDatabaseKey();
		if (fieldKey == null) {
			throw new DbException(new IllegalStateException(
					"database locked"));
		}
		try {
			byte[] sealed = fromHexString(hex);
			byte[] plaintext = FieldEncryption.decrypt(fieldKey, sealed);
			return new String(plaintext, UTF_8);
		} catch (org.zerionproject.core.api.FormatException
				| GeneralSecurityException e) {
			return null;
		}
	}

	private String sealString(String plaintext) throws DbException {
		SecretKey fieldKey = accountManager.getDatabaseKey();
		if (fieldKey == null) {
			throw new DbException(new IllegalStateException(
					"database locked"));
		}
		try {
			byte[] sealed = FieldEncryption.encrypt(fieldKey,
					plaintext.getBytes(UTF_8));
			return toHexString(sealed);
		} catch (GeneralSecurityException e) {
			throw new DbException(e);
		}
	}
}
