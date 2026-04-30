package org.briarproject.bramble.plugin.tor;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.crypto.FieldEncryption;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.Collection;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_LAST_ROTATION_TIME_MS_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_ONION3_CURRENT_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_ROTATION_PHASE_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_CONTACT_ONION3_PENDING_KEY_PREFIX;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_PEER_ROTATION_STATE_KEY_PREFIX;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ROTATION_ENABLED;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.briarproject.bramble.api.plugin.B4Constants.FORCE_EXPIRE_DAYS;
import static org.briarproject.bramble.api.plugin.B4Constants.ROTATION_MAX_DAYS;
import static org.briarproject.bramble.api.plugin.B4Constants.ROTATION_MIN_DAYS;
import static org.briarproject.bramble.util.StringUtils.UTF_8;
import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
public class B4OnionRotation {

	public enum RotationPhase {
		IDLE,
		ANNOUNCING,
		COMPLETE,
	}

	public enum PeerRotationState {
		CURRENT,
		PRE_ANNOUNCED,
		MIGRATED,
	}

	// Crash-safe-promotion sentinel. Written before the destructive
	// retirement steps (DEL_ONION + clear old keychain item + state
	// cleanup), cleared once promotion has fully landed. On startup,
	// resumeIfPromotionInterrupted reads this and re-runs the promotion
	// idempotently if the app died mid-retirement.
	private static final String B4_ALICE_PROMOTING_SENTINEL_KEY =
			"alice_rotation_promoting";

	private final Object rotationLock = new Object();

	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final AccountManager accountManager;
	private final ContactManager contactManager;
	private final Clock clock;

	@Inject
	public B4OnionRotation(DatabaseComponent db,
			SettingsManager settingsManager,
			AccountManager accountManager,
			ContactManager contactManager,
			Clock clock) {
		this.db = db;
		this.settingsManager = settingsManager;
		this.accountManager = accountManager;
		this.contactManager = contactManager;
		this.clock = clock;
	}

	public void evaluateTrigger() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		synchronized (rotationLock) {
			db.transaction(false, txn -> {
				if (loadPhase(txn) != RotationPhase.IDLE) return;
				long now = clock.currentTimeMillis();
				long last = loadLastRotationTimeMs(txn);
				long days = DAYS.convert(now - last,
						java.util.concurrent.TimeUnit.MILLISECONDS);
				if (days >= ROTATION_MAX_DAYS) {
					beginRotation(txn, now);
				} else if (days >= ROTATION_MIN_DAYS
						&& hasActiveContacts(txn)) {
					beginRotation(txn, now);
				}
			});
		}
	}

	public void forceRotate() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		synchronized (rotationLock) {
			db.transaction(false, txn -> {
				if (loadPhase(txn) != RotationPhase.IDLE) return;
				beginRotation(txn, clock.currentTimeMillis());
			});
		}
	}

	public void onAnnounceReceived(ContactId from, String pendingOnion,
			long announcedAtMs) throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		db.transaction(false, txn -> {
			Settings update = new Settings();
			update.put(B4_CONTACT_ONION3_PENDING_KEY_PREFIX + from.getInt(),
					sealString(pendingOnion));
			update.put(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX
							+ from.getInt(),
					sealString(String.valueOf(announcedAtMs)));
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
		});
	}

	public void onInboundConnectionOnNewOnion(ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		synchronized (rotationLock) {
			db.transaction(false, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return;
				setPeerState(txn, cid, PeerRotationState.MIGRATED);
				evaluateCompletion(txn);
			});
		}
	}

	public void onPeerSyncSessionEstablished(ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		synchronized (rotationLock) {
			db.transaction(false, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return;
				PeerRotationState state = loadPeerState(txn, cid);
				if (state == PeerRotationState.CURRENT) {
					// Mark PRE_ANNOUNCED before the actual outbound send so
					// a second concurrent session for the same contact
					// won't trigger a duplicate announce. Send wiring goes
					// in the TransportPropertyManager outbound hook.
					setPeerState(txn, cid, PeerRotationState.PRE_ANNOUNCED);
				}
			});
		}
	}

	public void markPeerMigrated(ContactId cid) throws DbException {
		// Receiver-side helper: dialer calls this once it has
		// successfully connected to a peer's pending onion and atomic-
		// swapped pending → current locally.
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
		final String[] result = new String[1];
		db.transaction(true, txn -> {
			result[0] = loadEncryptedString(txn,
					B4_CONTACT_ONION3_PENDING_KEY_PREFIX + cid.getInt());
		});
		return result[0];
	}

	public void resumeIfPromotionInterrupted() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		synchronized (rotationLock) {
			db.transaction(false, txn -> {
				String sentinel = loadEncryptedString(txn,
						B4_ALICE_PROMOTING_SENTINEL_KEY);
				if (sentinel == null) return;
				performAtomicPromotion(txn);
			});
		}
	}

	// Internal ----------------------------------------------------------

	private void beginRotation(Transaction txn, long now)
			throws DbException {
		// Tor publishHiddenService + new onion address persistence are
		// wired in the follow-up Tor-integration commit. For now this
		// records the intent — phase transition + last-rotation-time —
		// so the state machine surface is testable without Tor running.
		setPhase(txn, RotationPhase.ANNOUNCING);
		Settings update = new Settings();
		update.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
				sealString(String.valueOf(now)));
		settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
		// Reset every contact's per-peer state to CURRENT — fresh
		// announce campaign starts from scratch.
		Collection<Contact> contacts = contactManager.getContacts(txn);
		for (Contact c : contacts) {
			setPeerState(txn, c.getId(), PeerRotationState.CURRENT);
		}
	}

	private void evaluateCompletion(Transaction txn) throws DbException {
		// Two retirement triggers:
		//   (a) all peers MIGRATED — happy path.
		//   (b) FORCE_EXPIRE_DAYS reached since rotation began — even
		//       if some peers never connected back. The remaining
		//       laggard peers are presumed abandoned.
		long now = clock.currentTimeMillis();
		long last = loadLastRotationTimeMs(txn);
		long daysSince = DAYS.convert(now - last,
				java.util.concurrent.TimeUnit.MILLISECONDS);
		boolean forceExpired = daysSince >= FORCE_EXPIRE_DAYS;

		Collection<Contact> contacts = contactManager.getContacts(txn);
		boolean allMigrated = true;
		for (Contact c : contacts) {
			if (loadPeerState(txn, c.getId()) != PeerRotationState.MIGRATED) {
				allMigrated = false;
				break;
			}
		}

		if (allMigrated || forceExpired) {
			performAtomicPromotion(txn);
		}
	}

	public void evaluateForceExpire() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		synchronized (rotationLock) {
			db.transaction(false, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return;
				evaluateCompletion(txn);
			});
		}
	}

	private void performAtomicPromotion(Transaction txn) throws DbException {
		// Crash-safe ordering: write the sentinel BEFORE any destructive
		// op. If the app dies mid-promotion, resumeIfPromotionInterrupted
		// reads the sentinel at startup and re-runs this method
		// idempotently. Every step below must be safe to repeat.
		Settings sentinel = new Settings();
		sentinel.put(B4_ALICE_PROMOTING_SENTINEL_KEY, sealString("1"));
		settingsManager.mergeSettings(txn, sentinel, B4_SETTINGS_NAMESPACE);

		setPhase(txn, RotationPhase.COMPLETE);

		// Tor DEL_ONION on the old onion + private-key wipe land in the
		// follow-up Tor-integration commit. State-machine transition is
		// what we record here.
		String next = loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY);
		if (next != null) {
			Settings promote = new Settings();
			promote.put(B4_ALICE_ONION3_CURRENT_KEY, sealString(next));
			promote.put(B4_ALICE_ONION3_NEXT_KEY, "");
			settingsManager.mergeSettings(txn, promote,
					B4_SETTINGS_NAMESPACE);
		}

		// Reset peer states to CURRENT for the post-promotion world.
		Collection<Contact> contacts = contactManager.getContacts(txn);
		for (Contact c : contacts) {
			setPeerState(txn, c.getId(), PeerRotationState.CURRENT);
		}

		setPhase(txn, RotationPhase.IDLE);

		Settings clear = new Settings();
		clear.put(B4_ALICE_PROMOTING_SENTINEL_KEY, "");
		settingsManager.mergeSettings(txn, clear, B4_SETTINGS_NAMESPACE);
	}

	private boolean hasActiveContacts(Transaction txn) throws DbException {
		// Trigger refinement (subtask 4.2 follow-up): hook into
		// ConnectionRegistry to count active sessions instead of just
		// "any contact exists". For the state-machine skeleton, presence
		// of any contact is sufficient — no rotations fire on a fresh
		// install with zero contacts regardless.
		return !contactManager.getContacts(txn).isEmpty();
	}

	// Persistence -------------------------------------------------------

	private RotationPhase loadPhase(Transaction txn) throws DbException {
		String stored = loadEncryptedString(txn, B4_ALICE_ROTATION_PHASE_KEY);
		if (stored == null) return RotationPhase.IDLE;
		try {
			return RotationPhase.valueOf(stored);
		} catch (IllegalArgumentException e) {
			return RotationPhase.IDLE;
		}
	}

	private void setPhase(Transaction txn, RotationPhase phase)
			throws DbException {
		Settings s = new Settings();
		s.put(B4_ALICE_ROTATION_PHASE_KEY, sealString(phase.name()));
		settingsManager.mergeSettings(txn, s, B4_SETTINGS_NAMESPACE);
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
		} catch (GeneralSecurityException e) {
			// Tampered or unreadable; treat as absent rather than crash
			// so a user-visible feature degradation is preferred over a
			// fatal startup failure.
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
