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
import org.briarproject.onionwrapper.TorWrapper.HiddenServiceProperties;

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
import static org.zerionproject.core.api.plugin.B4Constants.B4_PEER_ROTATION_STATE_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ROTATION_ENABLED;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.plugin.B4Constants.FORCE_EXPIRE_DAYS;
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
public class B4OnionRotation {

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

		void removeHiddenService(String onion) throws IOException;

		void updateTorCurrentPrivKey(String newPrivKey);

		void mergeTorLocalProperties(TransportProperties props);
	}

	private static final String B4_ALICE_PROMOTING_SENTINEL_KEY =
			"alice_rotation_promoting";

	private static final String B4_ALICE_NEXT_ROTATION_DAYS_KEY =
			"alice_rotation_next_interval_days";

	private final java.security.SecureRandom rotationRng =
			new java.security.SecureRandom();

	private long drawNextRotationDays() {
		int range = (int) (ROTATION_MAX_DAYS - ROTATION_MIN_DAYS + 1);
		return ROTATION_MIN_DAYS + rotationRng.nextInt(range);
	}

	private final Object rotationLock = new Object();

	private final ScheduledExecutorService scheduler =
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "b4-rotation-rebroadcast");
				t.setDaemon(true);
				return t;
			});

	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final AccountManager accountManager;
	private final Clock clock;

	@Nullable
	private volatile B4TorAdapter adapter;

	@Inject
	public B4OnionRotation(DatabaseComponent db,
			SettingsManager settingsManager,
			AccountManager accountManager,
			Clock clock) {
		this.db = db;
		this.settingsManager = settingsManager;
		this.accountManager = accountManager;
		this.clock = clock;
	}

	public void bindAdapter(B4TorAdapter adapter) {
		this.adapter = adapter;
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

	public void forceRotate() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (adapter == null) {
			return;
		}
		synchronized (rotationLock) {
			long now = clock.currentTimeMillis();
			boolean shouldRotate = db.transactionWithResult(true, txn ->
					loadPhase(txn) == RotationPhase.IDLE);
			if (shouldRotate) executeRotation(now);
		}
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
			return;
		}
		if (existingPending != null) {
			String lastRaw = loadEncryptedString(txn,
					B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX
							+ from.getInt());
			if (lastRaw != null) {
				try {
					long lastMs = Long.parseLong(lastRaw);
					long now = clock.currentTimeMillis();
					if (now - lastMs < B4_ANNOUNCE_RATE_LIMIT_MS) {
						return;
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}
		Settings update = new Settings();
		update.put(B4_CONTACT_ONION3_PENDING_KEY_PREFIX + from.getInt(),
				sealString(pendingOnion));
		update.put(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX
						+ from.getInt(),
				sealString(String.valueOf(clock.currentTimeMillis())));
		update.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX
						+ from.getInt(), "");
		update.put(B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX
						+ from.getInt(), "");
		settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
		txn.attach(new B4PeerOnionAnnouncedEvent(from));
	}

	public void onSuccessfulConnect(ContactId cid, String dialedOnion)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (dialedOnion == null) return;
		db.transaction(false, txn -> {
			String pending = loadEncryptedString(txn,
					B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt());
			if (pending == null || !pending.equals(dialedOnion)) return;
			String alreadySucceeded = loadEncryptedString(txn,
					B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX
							+ cid.getInt());
			if ("1".equals(alreadySucceeded)) return;
			Settings update = new Settings();
			update.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX
					+ cid.getInt(), "");
			update.put(B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX
					+ cid.getInt(), sealString("1"));
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
		});
	}

	public void onPeerRotationComplete(Transaction txn, ContactId cid,
			String newCurrentOnion) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (newCurrentOnion == null || newCurrentOnion.isEmpty()) return;
		String pending = loadEncryptedString(txn,
				B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt());
		if (pending == null || !pending.equals(newCurrentOnion)) return;
		Settings clear = new Settings();
		clear.put(B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt(), "");
		clear.put(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX
				+ cid.getInt(), "");
		clear.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX
				+ cid.getInt(), "");
		clear.put(B4_CONTACT_PENDING_DIAL_SUCCEEDED_KEY_PREFIX
				+ cid.getInt(), "");
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
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
		});
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

	public void onInboundConnectionOnNewOnion(ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		boolean shouldComplete;
		synchronized (rotationLock) {
			shouldComplete = db.transactionWithResult(false, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return false;
				setPeerState(txn, cid, PeerRotationState.MIGRATED);
				return shouldRetireOldOnion(txn);
			});
			if (shouldComplete) executePromotion();
		}
	}

	public void onPeerSyncSessionEstablished(ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		final boolean[] transitioned = new boolean[1];
		synchronized (rotationLock) {
			db.transaction(false, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return;
				PeerRotationState state = loadPeerState(txn, cid);
				if (state == PeerRotationState.CURRENT) {
					setPeerState(txn, cid, PeerRotationState.PRE_ANNOUNCED);
					transitioned[0] = true;
				}
			});
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

	@Nullable
	public String getPendingOnionForContact(Transaction txn, ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return null;
		int failures = loadPendingDialFailures(txn, cid);
		if (failures >= B4_PENDING_DIAL_FAILURE_THRESHOLD) {
			return null;
		}
		return loadEncryptedString(txn,
				B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt());
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
	}

	public boolean forceCompleteRotation() throws DbException {
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

	private void executeRotation(long now) throws DbException {
		B4TorAdapter ad = adapter;
		if (ad == null) return;

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
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
			Collection<Contact> contacts = db.getContacts(txn);
			for (Contact c : contacts) {
				setPeerState(txn, c.getId(), PeerRotationState.CURRENT);
				contactIds.add(c.getId());
			}
		});

		TransportProperties props = new TransportProperties();
		props.put(WIRE_KEY_ONION3_NEXT, newOnion);
		props.put(WIRE_KEY_ONION3_ANNOUNCED_AT_MS, String.valueOf(now));
		props.put(WIRE_KEY_ONION3_PUBLISH_NONCE, "0");
		ad.mergeTorLocalProperties(props);
		scheduleRebroadcasts(now);
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

		String oldOnion = state[0];
		String newOnion = state[1];
		String newPrivKey = state[2];


		if (oldOnion != null) {
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
			update.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
					sealString(String.valueOf(completionTime)));
			update.put(B4_ALICE_ROTATION_PHASE_KEY,
					sealString(RotationPhase.IDLE.name()));
			update.put(B4_ALICE_PROMOTING_SENTINEL_KEY, "");
			update.put(B4_ALICE_NEXT_ROTATION_DAYS_KEY,
					sealString(String.valueOf(drawNextRotationDays())));
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
		long daysSince = DAYS.convert(now - announcedAt,
				java.util.concurrent.TimeUnit.MILLISECONDS);
		if (daysSince >= FORCE_EXPIRE_DAYS) return true;
		Collection<Contact> contacts = db.getContacts(txn);
		if (contacts.isEmpty()) return false;
		for (Contact c : contacts) {
			if (loadPeerState(txn, c.getId()) != PeerRotationState.MIGRATED) {
				return false;
			}
		}
		return true;
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
