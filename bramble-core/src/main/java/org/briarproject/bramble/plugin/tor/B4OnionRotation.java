package org.briarproject.bramble.plugin.tor;

import org.briarproject.bramble.api.account.AccountManager;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.crypto.FieldEncryption;
import org.briarproject.nullsafety.NotNullByDefault;
import org.briarproject.onionwrapper.TorWrapper.HiddenServiceProperties;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static java.util.concurrent.TimeUnit.DAYS;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_LAST_ROTATION_TIME_MS_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_ONION3_CURRENT_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_PRIVKEY_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ALICE_ROTATION_PHASE_KEY;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_CONTACT_ONION3_PENDING_KEY_PREFIX;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_PEER_ROTATION_STATE_KEY_PREFIX;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_ROTATION_ENABLED;
import static org.briarproject.bramble.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.briarproject.bramble.api.plugin.B4Constants.FORCE_EXPIRE_DAYS;
import static org.briarproject.bramble.api.plugin.B4Constants.ROTATION_MAX_DAYS;
import static org.briarproject.bramble.api.plugin.B4Constants.ROTATION_MIN_DAYS;
import static org.briarproject.bramble.api.plugin.B4Constants.WIRE_KEY_ONION3;
import static org.briarproject.bramble.api.plugin.B4Constants.WIRE_KEY_ONION3_ANNOUNCED_AT_MS;
import static org.briarproject.bramble.api.plugin.B4Constants.WIRE_KEY_ONION3_NEXT;
import static org.briarproject.bramble.util.StringUtils.UTF_8;
import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.toHexString;

@Singleton
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

	/**
	 * The Tor + TPM calls B.4 needs to make to actually mint, retire,
	 * and advertise onions. Implemented by {@code TorPlugin}, which has
	 * the {@link org.briarproject.onionwrapper.TorWrapper} reference and
	 * the {@code PluginCallback} for setting / property writes.
	 *
	 * <p>Pulled out as an interface so {@code B4OnionRotation} doesn't
	 * have a direct dependency on {@code TorPlugin} (which would create
	 * a circular DI graph: TorPlugin needs the orchestrator for the
	 * trigger hook, the orchestrator needs Tor for publish/remove).
	 */
	public interface B4TorAdapter {
		HiddenServiceProperties publishHiddenService(@Nullable String privKey)
				throws IOException;

		void removeHiddenService(String onion) throws IOException;

		void updateTorCurrentPrivKey(String newPrivKey);

		void mergeTorLocalProperties(TransportProperties props);
	}

	private static final String B4_ALICE_PROMOTING_SENTINEL_KEY =
			"alice_rotation_promoting";

	private final Object rotationLock = new Object();

	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final AccountManager accountManager;
	private final Clock clock;

	@Nullable
	private volatile B4TorAdapter adapter;

	// ContactManager intentionally NOT injected here — it depends on
	// KeyManager which depends on PluginConfig which depends on
	// TorPluginFactory which depends on B4OnionRotation, forming a
	// Dagger cycle. DatabaseComponent.getContacts(txn) gives the same
	// list without crossing the contact-management module boundary.
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
		if (adapter == null) return;
		synchronized (rotationLock) {
			long now = clock.currentTimeMillis();
			boolean shouldRotate = db.transactionWithResult(true, txn -> {
				if (loadPhase(txn) != RotationPhase.IDLE) return false;
				long last = loadLastRotationTimeMs(txn);
				long days = DAYS.convert(now - last,
						java.util.concurrent.TimeUnit.MILLISECONDS);
				if (days >= ROTATION_MAX_DAYS) return true;
				return days >= ROTATION_MIN_DAYS && hasActiveContacts(txn);
			});
			if (shouldRotate) executeRotation(now);
		}
	}

	public void forceRotate() throws DbException {
		if (!B4_ROTATION_ENABLED) return;
		if (adapter == null) return;
		synchronized (rotationLock) {
			long now = clock.currentTimeMillis();
			boolean shouldRotate = db.transactionWithResult(true, txn ->
					loadPhase(txn) == RotationPhase.IDLE);
			if (shouldRotate) executeRotation(now);
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
		synchronized (rotationLock) {
			db.transaction(false, txn -> {
				if (loadPhase(txn) != RotationPhase.ANNOUNCING) return;
				PeerRotationState state = loadPeerState(txn, cid);
				if (state == PeerRotationState.CURRENT) {
					setPeerState(txn, cid, PeerRotationState.PRE_ANNOUNCED);
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

	// Same lookup but reusing an existing transaction — for callers
	// like TransportPropertyManager that already have one open. Avoids
	// nesting transactions during the dial-prep path.
	@Nullable
	public String getPendingOnionForContact(Transaction txn, ContactId cid)
			throws DbException {
		if (!B4_ROTATION_ENABLED) return null;
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

	// Rotation execution -----------------------------------------------

	// Tor + TPM calls happen OUTSIDE the persistence transaction so we
	// don't nest a TPM transaction (mergeLocalProperties opens its own)
	// inside our own. Crash-recovery on partial completion is handled
	// by sentinels and idempotent re-runs at startup.
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
			Settings update = new Settings();
			update.put(B4_ALICE_ROTATION_PHASE_KEY,
					sealString(RotationPhase.ANNOUNCING.name()));
			update.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
					sealString(String.valueOf(now)));
			update.put(B4_ALICE_ONION3_NEXT_KEY, sealString(newOnion));
			update.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY,
					sealString(newPrivKey));
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
			Collection<Contact> contacts = db.getContacts(txn);
			for (Contact c : contacts) {
				setPeerState(txn, c.getId(), PeerRotationState.CURRENT);
				contactIds.add(c.getId());
			}
		});

		// Advertise new onion to all peers via TPM update record. TPM
		// will dedup on no-change and only bump version when the
		// merged set genuinely changes from the prior version, so
		// re-running this on crash-recovery is idempotent.
		TransportProperties props = new TransportProperties();
		props.put(WIRE_KEY_ONION3_NEXT, newOnion);
		props.put(WIRE_KEY_ONION3_ANNOUNCED_AT_MS, String.valueOf(now));
		ad.mergeTorLocalProperties(props);
	}

	private void executePromotion() throws DbException {
		B4TorAdapter ad = adapter;
		if (ad == null) return;

		// Read the pre-promotion state under transaction.
		final String[] state = new String[3];
		db.transaction(false, txn -> {
			state[0] = loadEncryptedString(txn, B4_ALICE_ONION3_CURRENT_KEY);
			state[1] = loadEncryptedString(txn, B4_ALICE_ONION3_NEXT_KEY);
			state[2] = loadEncryptedString(txn,
					B4_ALICE_ONION3_NEXT_PRIVKEY_KEY);
			// Sentinel BEFORE any destructive op.
			Settings sentinel = new Settings();
			sentinel.put(B4_ALICE_PROMOTING_SENTINEL_KEY, sealString("1"));
			settingsManager.mergeSettings(txn, sentinel,
					B4_SETTINGS_NAMESPACE);
		});

		String oldOnion = state[0];
		String newOnion = state[1];
		String newPrivKey = state[2];

		// Idempotent: removeHiddenService on a non-existent onion is a
		// silent no-op per onionwrapper contract.
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
			// Clearing next + announced_at_ms in the wire props on the
			// post-promotion update so peers stop seeing the rotation
			// announce. Empty strings are filtered to "unset" by
			// TPM.mergeLocalProperties (see isNullOrEmpty filter at
			// TransportPropertyManagerImpl line 297).
			props.put(WIRE_KEY_ONION3_NEXT, "");
			props.put(WIRE_KEY_ONION3_ANNOUNCED_AT_MS, "");
			ad.mergeTorLocalProperties(props);
		}

		// Final state cleanup + sentinel clear, in transaction.
		db.transaction(false, txn -> {
			Settings update = new Settings();
			if (newOnion != null) {
				update.put(B4_ALICE_ONION3_CURRENT_KEY, sealString(newOnion));
			}
			update.put(B4_ALICE_ONION3_NEXT_KEY, "");
			update.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY, "");
			update.put(B4_ALICE_ROTATION_PHASE_KEY,
					sealString(RotationPhase.IDLE.name()));
			update.put(B4_ALICE_PROMOTING_SENTINEL_KEY, "");
			settingsManager.mergeSettings(txn, update, B4_SETTINGS_NAMESPACE);
			Collection<Contact> contacts = db.getContacts(txn);
			for (Contact c : contacts) {
				setPeerState(txn, c.getId(), PeerRotationState.CURRENT);
			}
		});
	}

	private boolean shouldRetireOldOnion(Transaction txn) throws DbException {
		long now = clock.currentTimeMillis();
		long last = loadLastRotationTimeMs(txn);
		long daysSince = DAYS.convert(now - last,
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
		} catch (org.briarproject.bramble.api.FormatException
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
