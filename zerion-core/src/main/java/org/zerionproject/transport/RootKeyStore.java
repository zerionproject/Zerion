package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
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
		Object[] base = db.getPcsMode2SessionState(txn, c, PCS_DIRECTION_SEND);
		if (base == null || base[3] == null || !(Boolean) base[7]) return null;
		long epoch = 0;
		SecretKey current = new SecretKey((byte[]) base[3]);
		Object[] cur = db.getPcsMode2SessionState(txn, c,
				PCS_SLOT_TRANSPORT_ROOT);
		if (cur != null && cur[3] != null) {
			epoch = (Integer) cur[1];
			current = new SecretKey((byte[]) cur[3]);
		}
		SecretKey pending = null;
		boolean confirmed = false;
		Object[] pen = db.getPcsMode2SessionState(txn, c,
				PCS_SLOT_TRANSPORT_ROOT_PENDING);
		if (pen != null && pen[3] != null
				&& (Integer) pen[1] == epoch + 1) {
			pending = new SecretKey((byte[]) pen[3]);
			confirmed = (Integer) pen[2] == 1;
		}
		return new ContactRootKeys(epoch, current, pending, confirmed);
	}

	public boolean storePending(ContactId c, long fromEpoch,
			SecretKey pending, boolean confirmed) throws DbException {
		if (fromEpoch + 1 > Integer.MAX_VALUE) return false;
		if (isSnapshotting()) return false;
		boolean stored = db.transactionWithResult(false, txn -> {
			ContactRootKeys keys = load(txn, c);
			if (keys == null || keys.getEpoch() != fromEpoch) return false;
			writeSlot(txn, c, PCS_SLOT_TRANSPORT_ROOT_PENDING, pending,
					fromEpoch + 1, confirmed);
			return true;
		});
		if (stored) notifyChanged(c);
		return stored;
	}

	public boolean discardPending(ContactId c, long fromEpoch)
			throws DbException {
		if (isSnapshotting()) return false;
		boolean discarded = db.transactionWithResult(false, txn -> {
			ContactRootKeys keys = load(txn, c);
			if (keys == null || keys.getEpoch() != fromEpoch
					|| keys.getPending() == null) {
				return false;
			}
			db.removePcsSessionState(txn, c, PCS_SLOT_TRANSPORT_ROOT_PENDING);
			return true;
		});
		if (discarded) notifyChanged(c);
		return discarded;
	}

	public boolean promote(ContactId c, long fromEpoch) throws DbException {
		if (isSnapshotting()) return false;
		boolean promoted = db.transactionWithResult(false, txn -> {
			ContactRootKeys keys = load(txn, c);
			if (keys == null || keys.getEpoch() != fromEpoch) return false;
			SecretKey pending = keys.getPending();
			if (pending == null) return false;
			writeSlot(txn, c, PCS_SLOT_TRANSPORT_ROOT, pending,
					fromEpoch + 1, false);
			db.removePcsSessionState(txn, c, PCS_SLOT_TRANSPORT_ROOT_PENDING);
			return true;
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

	public void pauseUntil(Transaction txn, long until) throws DbException {
		Settings s = new Settings();
		s.putLong(KEY_PAUSED_UNTIL, until);
		db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
	}

	private void writeSlot(Transaction txn, ContactId c, int slot,
			SecretKey key, long epoch, boolean confirmed) throws DbException {
		db.setPcsMode2SessionState(txn, c, slot, key, (int) epoch,
				confirmed ? 1 : 0, key, null, null, null, true, null);
	}

	void notifyChanged(ContactId c) {
		for (Listener l : listeners) l.rootKeysChanged(c);
	}
}
