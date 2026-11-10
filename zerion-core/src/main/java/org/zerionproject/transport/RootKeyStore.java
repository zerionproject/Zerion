package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.PcsSessionRow;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.core.api.db.DatabaseComponent.PCS_DIRECTION_SEND;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT;
import static org.zerionproject.core.api.db.DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING;

@ThreadSafe
@Singleton
@NotNullByDefault
public class RootKeyStore {

	public interface Listener {
		void rootKeysChanged(ContactId c);
	}

	public static final String SETTINGS_NAMESPACE =
			"org.zerionproject.transport.rootEvolution";
	public static final String KEY_PAUSED_UNTIL = "pausedUntil";
	static final String OUT_OF_SYNC_KEY_PREFIX = "outOfSync.";
	public static final String ENFORCES_KEY_PREFIX = "enforces.";
	public static final String EVOLVED_AT_KEY_PREFIX = "evolvedAt.";
	public static final String EVOLVES_KEY_PREFIX = "evolves.";
	public static final String STALLS_KEY_PREFIX = "stalls.";
	public static final String STALLED_SINCE_KEY_PREFIX = "stalledSince.";
	public static final String NOT_RENEWING_KEY_PREFIX = "notRenewing.";
	static final int PENDING_FLAG_CONFIRMED = 0x01;
	static final int PENDING_FLAG_SEPARATED = 0x02;

	private final DatabaseComponent db;
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private final java.util.concurrent.atomic.AtomicInteger snapshots =
			new java.util.concurrent.atomic.AtomicInteger();

	@Inject
	public RootKeyStore(DatabaseComponent db) {
		this.db = db;
	}

	public void addListener(Listener l) {
		listeners.add(l);
	}

	public void removeListener(Listener l) {
		listeners.remove(l);
	}

	@Nullable
	public ContactRootKeys load(ContactId c) {
		try {
			return db.transactionWithNullableResult(true,
					txn -> load(txn, c));
		} catch (DbException e) {
			return null;
		}
	}

	@Nullable
	public ContactRootKeys load(Transaction txn, ContactId c)
			throws DbException {
		PcsSessionRow base = db.getPcsMode2SessionState(txn, c,
				PCS_DIRECTION_SEND);
		PcsSessionRow cur = null;
		PcsSessionRow pen = null;
		try {
			if (base == null || base.getRootKey() == null
					|| !base.isMode2Enabled()) {
				return null;
			}
			long epoch = 0;
			byte[] current = base.getRootKey();
			cur = db.getPcsMode2SessionState(txn, c, PCS_SLOT_TRANSPORT_ROOT);
			if (cur != null && cur.getRootKey() != null) {
				epoch = cur.getMessageNumber();
				current = cur.getRootKey();
			}
			SecretKey pending = null;
			SecretKey pendingConfirmKey = null;
			boolean confirmed = false;
			pen = db.getPcsMode2SessionState(txn, c,
					PCS_SLOT_TRANSPORT_ROOT_PENDING);
			if (pen != null && pen.getRootKey() != null
					&& pen.getMessageNumber() == epoch + 1) {
				int flags = pen.getPreviousChainLength();
				pending = new SecretKey(pen.getRootKey().clone());
				confirmed = (flags & PENDING_FLAG_CONFIRMED) != 0;
				if ((flags & PENDING_FLAG_SEPARATED) != 0) {
					pendingConfirmKey =
							new SecretKey(pen.getChainKey().clone());
				}
			}
			return new ContactRootKeys(epoch, new SecretKey(current.clone()),
					pending, pendingConfirmKey, confirmed);
		} finally {
			wipe(base);
			wipe(cur);
			wipe(pen);
		}
	}

	private static void wipe(@Nullable PcsSessionRow row) {
		if (row == null) return;
		fill(row.getChainKey());
		fill(row.getRootKey());
		fill(row.getDhPrivateKey());
		fill(row.getMode3FullStateBlob());
	}

	private static void fill(@Nullable byte[] b) {
		if (b != null) java.util.Arrays.fill(b, (byte) 0);
	}

	public boolean storePending(ContactId c, long fromEpoch,
			SecretKey pending, boolean confirmed) throws DbException {
		return storePending(c, fromEpoch, pending, null, confirmed);
	}

	public boolean storePending(ContactId c, long fromEpoch,
			SecretKey pending, @Nullable SecretKey pendingConfirmKey,
			boolean confirmed) throws DbException {
		if (fromEpoch + 1 > Integer.MAX_VALUE) return false;
		if (isSnapshotting()) return false;
		boolean stored = db.transactionWithResult(false, txn -> {
			if (isSnapshotting()) return false;
			ContactRootKeys keys = load(txn, c);
			if (keys == null) return false;
			try {
				if (keys.getEpoch() != fromEpoch) return false;
				int flags = (confirmed ? PENDING_FLAG_CONFIRMED : 0)
						| (pendingConfirmKey != null ? PENDING_FLAG_SEPARATED
						: 0);
				writeSlot(txn, c, PCS_SLOT_TRANSPORT_ROOT_PENDING,
						pendingConfirmKey != null ? pendingConfirmKey : pending,
						pending, fromEpoch + 1, flags);
				return true;
			} finally {
				wipe(keys);
			}
		});
		if (stored) notifyChanged(c);
		return stored;
	}

	public boolean discardPending(ContactId c, long fromEpoch)
			throws DbException {
		if (isSnapshotting()) return false;
		boolean discarded = db.transactionWithResult(false, txn -> {
			if (isSnapshotting()) return false;
			ContactRootKeys keys = load(txn, c);
			if (keys == null) return false;
			try {
				if (keys.getEpoch() != fromEpoch
						|| keys.getPending() == null) {
					return false;
				}
				db.removePcsSessionState(txn, c,
						PCS_SLOT_TRANSPORT_ROOT_PENDING);
				return true;
			} finally {
				wipe(keys);
			}
		});
		if (discarded) notifyChanged(c);
		return discarded;
	}

	public boolean promote(ContactId c, long fromEpoch) throws DbException {
		if (isSnapshotting()) return false;
		boolean promoted = db.transactionWithResult(false, txn -> {
			if (isSnapshotting()) return false;
			ContactRootKeys keys = load(txn, c);
			if (keys == null) return false;
			try {
				if (keys.getEpoch() != fromEpoch) return false;
				SecretKey pending = keys.getPending();
				if (pending == null) return false;
				writeSlot(txn, c, PCS_SLOT_TRANSPORT_ROOT, pending, pending,
						fromEpoch + 1, 0);
				db.removePcsSessionState(txn, c,
						PCS_SLOT_TRANSPORT_ROOT_PENDING);
				return true;
			} finally {
				wipe(keys);
			}
		});
		if (promoted) notifyChanged(c);
		return promoted;
	}

	public void beginSnapshot() {
		snapshots.incrementAndGet();
	}

	public void endSnapshot() {
		snapshots.decrementAndGet();
	}

	public boolean isSnapshotting() {
		return snapshots.get() > 0;
	}

	public boolean isPaused(long now) {
		try {
			long until = db.transactionWithResult(true, txn ->
					db.getSettings(txn, SETTINGS_NAMESPACE)
							.getLong(KEY_PAUSED_UNTIL, 0));
			return now < until;
		} catch (DbException e) {
			return true;
		}
	}

	public void markOutOfSync(ContactId c, boolean outOfSync) {
		try {
			db.transaction(false, txn -> {
				String key = OUT_OF_SYNC_KEY_PREFIX + c.getInt();
				boolean before = db.getSettings(txn, SETTINGS_NAMESPACE)
						.getBoolean(key, false);
				if (before == outOfSync) return;
				Settings s = new Settings();
				s.putBoolean(key, outOfSync);
				db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
				txn.attach(new org.zerionproject.core.api.contact.event
						.ContactConnectionKeysEvent(c, outOfSync));
			});
		} catch (DbException e) {
		}
	}

	public boolean isOutOfSync(ContactId c) {
		try {
			return db.transactionWithResult(true, txn ->
					db.getSettings(txn, SETTINGS_NAMESPACE).getBoolean(
							OUT_OF_SYNC_KEY_PREFIX + c.getInt(), false));
		} catch (DbException e) {
			return false;
		}
	}

	public boolean peerEnforces(ContactId c) {
		try {
			return db.transactionWithResult(true, txn ->
					db.getSettings(txn, SETTINGS_NAMESPACE).getBoolean(
							ENFORCES_KEY_PREFIX + c.getInt(), false));
		} catch (DbException e) {
			return true;
		}
	}

	public void setPeerEnforces(ContactId c, boolean enforces) {
		try {
			db.transaction(false, txn -> {
				Settings s = new Settings();
				s.putBoolean(ENFORCES_KEY_PREFIX + c.getInt(), enforces);
				db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
			});
		} catch (DbException e) {
		}
	}

	public boolean peerEvolves(ContactId c) {
		try {
			return db.transactionWithResult(true, txn ->
					db.getSettings(txn, SETTINGS_NAMESPACE).getBoolean(
							EVOLVES_KEY_PREFIX + c.getInt(), false));
		} catch (DbException e) {
			return true;
		}
	}

	public void setPeerEvolves(ContactId c) {
		try {
			db.transaction(false, txn -> {
				Settings s = new Settings();
				s.putBoolean(EVOLVES_KEY_PREFIX + c.getInt(), true);
				db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
			});
		} catch (DbException e) {
		}
	}

	public long evolvedAt(ContactId c) {
		try {
			return db.transactionWithResult(true, txn ->
					db.getSettings(txn, SETTINGS_NAMESPACE).getLong(
							EVOLVED_AT_KEY_PREFIX + c.getInt(), 0));
		} catch (DbException e) {
			return 0;
		}
	}

	@Nullable
	public long[] stalls(ContactId c) {
		try {
			return db.transactionWithResult(true, txn -> {
				Settings s = db.getSettings(txn, SETTINGS_NAMESPACE);
				return new long[] {
						s.getInt(STALLS_KEY_PREFIX + c.getInt(), 0),
						s.getLong(STALLED_SINCE_KEY_PREFIX + c.getInt(), 0)};
			});
		} catch (DbException e) {
			return null;
		}
	}

	public void setStalls(ContactId c, int count, long since) {
		try {
			db.transaction(false, txn -> {
				Settings s = new Settings();
				s.putInt(STALLS_KEY_PREFIX + c.getInt(), count);
				s.putLong(STALLED_SINCE_KEY_PREFIX + c.getInt(), since);
				db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
			});
		} catch (DbException e) {
		}
	}

	public void markNotRenewing(ContactId c, boolean notRenewing) {
		try {
			db.transaction(false, txn -> {
				String key = NOT_RENEWING_KEY_PREFIX + c.getInt();
				boolean before = db.getSettings(txn, SETTINGS_NAMESPACE)
						.getBoolean(key, false);
				if (before == notRenewing) return;
				Settings s = new Settings();
				s.putBoolean(key, notRenewing);
				db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
				txn.attach(new org.zerionproject.core.api.contact.event
						.ContactKeyRenewalEvent(c, notRenewing));
			});
		} catch (DbException e) {
		}
	}

	public boolean isNotRenewing(ContactId c) {
		try {
			return db.transactionWithResult(true, txn ->
					db.getSettings(txn, SETTINGS_NAMESPACE).getBoolean(
							NOT_RENEWING_KEY_PREFIX + c.getInt(), false));
		} catch (DbException e) {
			return false;
		}
	}

	public void renewed(ContactId c, long at) {
		try {
			db.transaction(false, txn -> {
				Settings before = db.getSettings(txn, SETTINGS_NAMESPACE);
				String stalls = STALLS_KEY_PREFIX + c.getInt();
				String since = STALLED_SINCE_KEY_PREFIX + c.getInt();
				String notRenewing = NOT_RENEWING_KEY_PREFIX + c.getInt();
				String outOfSync = OUT_OF_SYNC_KEY_PREFIX + c.getInt();
				Settings s = new Settings();
				s.putLong(EVOLVED_AT_KEY_PREFIX + c.getInt(), at);
				if (before.getInt(stalls, 0) != 0) s.putInt(stalls, 0);
				if (before.getLong(since, 0) != 0) s.putLong(since, 0);
				if (before.getBoolean(notRenewing, false)) {
					s.putBoolean(notRenewing, false);
					txn.attach(new org.zerionproject.core.api.contact.event
							.ContactKeyRenewalEvent(c, false));
				}
				if (before.getBoolean(outOfSync, false)) {
					s.putBoolean(outOfSync, false);
					txn.attach(new org.zerionproject.core.api.contact.event
							.ContactConnectionKeysEvent(c, false));
				}
				db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
			});
		} catch (DbException e) {
		}
	}

	public void pauseUntil(Transaction txn, long until) throws DbException {
		Settings s = new Settings();
		s.putLong(KEY_PAUSED_UNTIL, until);
		db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
	}

	private void writeSlot(Transaction txn, ContactId c, int slot,
			SecretKey chainKey, SecretKey root, long epoch, int flags)
			throws DbException {
		db.setPcsMode2SessionState(txn, c, slot, chainKey, (int) epoch,
				flags, root, null, null, null, true, null);
	}

	private static void wipe(ContactRootKeys keys) {
		keys.getCurrent().clear();
		SecretKey pending = keys.getPending();
		if (pending != null) pending.clear();
		SecretKey pendingConfirmKey = keys.getPendingConfirmKey();
		if (pendingConfirmKey != null) pendingConfirmKey.clear();
	}

	void notifyChanged(ContactId c) {
		for (Listener l : listeners) l.rootKeysChanged(c);
	}
}
