package org.zerionproject.core.transport;

import org.zerionproject.core.api.Bytes;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.core.api.system.Wakeful;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.TransportKeySet;
import org.zerionproject.core.api.transport.TransportKeys;
import org.zerionproject.core.transport.ReorderingWindow.Change;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.zerionproject.core.api.transport.TransportConstants.MAX_CLOCK_DIFFERENCE;
import static org.zerionproject.core.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.zerionproject.core.util.ByteUtils.MAX_32_BIT_UNSIGNED;
import static org.briarproject.nullsafety.NullSafety.requireExactlyOneNull;

@ThreadSafe
@NotNullByDefault
class TransportKeyManagerImpl implements TransportKeyManager {
	private final DatabaseComponent db;
	private final TransportCrypto transportCrypto;
	private final Executor dbExecutor;
	private final TaskScheduler scheduler;
	private final Clock clock;
	private final TransportId transportId;
	private final long timePeriodLength;
	private final AtomicBoolean used = new AtomicBoolean(false);
	private final ReentrantLock lock = new ReentrantLock();

	@GuardedBy("lock")
	private final Map<KeySetId, MutableTransportKeySet> keys = new HashMap<>();
	@GuardedBy("lock")
	private final Map<Bytes, TagContext> inContexts = new HashMap<>();
	@GuardedBy("lock")
	private final Map<ContactId, MutableTransportKeySet>
			contactOutContexts = new HashMap<>();
	@GuardedBy("lock")
	private final Map<PendingContactId, MutableTransportKeySet>
			pendingContactOutContexts = new HashMap<>();

	@GuardedBy("lock")
	private final Map<PendingContactId, Integer> activeHandshakes =
			new HashMap<>();

	TransportKeyManagerImpl(DatabaseComponent db,
			TransportCrypto transportCrypto,
			Executor dbExecutor,
			TaskScheduler scheduler,
			Clock clock,
			TransportId transportId,
			long maxLatency) {
		this.db = db;
		this.transportCrypto = transportCrypto;
		this.dbExecutor = dbExecutor;
		this.scheduler = scheduler;
		this.clock = clock;
		this.transportId = transportId;
		timePeriodLength = maxLatency + MAX_CLOCK_DIFFERENCE;
	}

	@Override
	public void start(Transaction txn) throws DbException {
		if (used.getAndSet(true)) throw new IllegalStateException();
		long now = clock.currentTimeMillis();
		lock.lock();
		try {
			Collection<TransportKeySet> loaded =
					db.getTransportKeys(txn, transportId);
			UpdateResult updateResult = updateKeys(loaded, now);
			addKeys(updateResult.current);
			if (!updateResult.updated.isEmpty())
				db.updateTransportKeys(txn, updateResult.updated);
		} finally {
			lock.unlock();
		}
		scheduleKeyUpdate(now);
	}

	private UpdateResult updateKeys(Collection<TransportKeySet> keys,
			long now) {
		UpdateResult updateResult = new UpdateResult();
		long timePeriod = now / timePeriodLength;
		for (TransportKeySet ks : keys) {
			TransportKeys k = ks.getKeys();
			TransportKeys k1 = transportCrypto.updateTransportKeys(k,
					timePeriod);
			if (k1.getTimePeriod() > k.getTimePeriod()) {
				TransportKeySet ks1 = new TransportKeySet(ks.getKeySetId(),
						ks.getContactId(), ks.getPendingContactId(), k1);
				updateResult.updated.add(ks1);
				updateResult.current.add(ks1);
			} else {
				updateResult.current.add(ks);
			}
		}
		return updateResult;
	}

	@GuardedBy("lock")
	private void addKeys(Collection<TransportKeySet> keys) {
		for (TransportKeySet ks : keys) {
			addKeys(ks.getKeySetId(), ks.getContactId(),
					ks.getPendingContactId(),
					new MutableTransportKeys(ks.getKeys()));
		}
	}

	@GuardedBy("lock")
	private void addKeys(KeySetId keySetId, @Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			MutableTransportKeys keys) {
		requireExactlyOneNull(contactId, pendingContactId);
		MutableTransportKeySet ks = new MutableTransportKeySet(keySetId,
				contactId, pendingContactId, keys);
		this.keys.put(keySetId, ks);
		boolean handshakeMode = keys.isHandshakeMode();
		encodeTags(keySetId, contactId, pendingContactId,
				keys.getPreviousIncomingKeys(), handshakeMode);
		encodeTags(keySetId, contactId, pendingContactId,
				keys.getCurrentIncomingKeys(), handshakeMode);
		encodeTags(keySetId, contactId, pendingContactId,
				keys.getNextIncomingKeys(), handshakeMode);
		considerReplacingOutgoingKeys(ks);
	}

	@GuardedBy("lock")
	private void encodeTags(KeySetId keySetId, @Nullable ContactId contactId,
			@Nullable PendingContactId pendingContactId,
			MutableIncomingKeys inKeys, boolean handshakeMode) {
		for (long streamNumber : inKeys.getWindow().getUnseen()) {
			TagContext tagCtx = new TagContext(keySetId, contactId,
					pendingContactId, inKeys, streamNumber, handshakeMode);
			byte[] tag = new byte[TAG_LENGTH];
			transportCrypto.encodeTag(tag, inKeys.getTagKey(), PROTOCOL_VERSION,
					streamNumber);
			inContexts.put(new Bytes(tag), tagCtx);
		}
	}

	@GuardedBy("lock")
	private void considerReplacingOutgoingKeys(MutableTransportKeySet ks) {
		if (ks.getKeys().getCurrentOutgoingKeys().isActive()) {
			MutableTransportKeySet old = getOutgoingKeySet(ks.getContactId(),
					ks.getPendingContactId());
			if (old == null || (old.getKeys().isHandshakeMode() &&
					!ks.getKeys().isHandshakeMode()) ||
					old.getKeySetId().getInt() < ks.getKeySetId().getInt()) {
				if (ks.getContactId() == null)
					pendingContactOutContexts.put(ks.getPendingContactId(), ks);
				else contactOutContexts.put(ks.getContactId(), ks);
			}
		}
	}

	@GuardedBy("lock")
	@Nullable
	private MutableTransportKeySet getOutgoingKeySet(@Nullable ContactId c,
			@Nullable PendingContactId p) {
		requireExactlyOneNull(c, p);
		if (c == null) return pendingContactOutContexts.get(p);
		else return contactOutContexts.get(c);
	}

	private void scheduleKeyUpdate(long now) {
		long delay = timePeriodLength - now % timePeriodLength;
		scheduler.schedule(this::updateKeys, dbExecutor, delay, MILLISECONDS);
	}

	@DatabaseExecutor
	@Wakeful
	private void updateKeys() {
		try {
			db.transaction(false, this::updateKeys);
		} catch (DbException e) {
		}
	}

	@Override
	public KeySetId addRotationKeys(Transaction txn, ContactId c,
			SecretKey rootKey, long timestamp, boolean alice, boolean active)
			throws DbException {
		lock.lock();
		try {
			long timePeriod = timestamp / timePeriodLength;
			TransportKeys k = transportCrypto.deriveRotationKeys(transportId,
					rootKey, timePeriod, alice, active);
			timePeriod = clock.currentTimeMillis() / timePeriodLength;
			k = transportCrypto.updateTransportKeys(k, timePeriod);
			KeySetId keySetId = db.addTransportKeys(txn, c, k);
			addKeys(keySetId, c, null, new MutableTransportKeys(k));
			return keySetId;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public KeySetId addHandshakeKeys(Transaction txn, ContactId c,
			SecretKey rootKey, boolean alice) throws DbException {
		lock.lock();
		try {
			long timePeriod = clock.currentTimeMillis() / timePeriodLength;
			TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
					rootKey, timePeriod, alice);
			KeySetId keySetId = db.addTransportKeys(txn, c, k);
			addKeys(keySetId, c, null, new MutableTransportKeys(k));
			return keySetId;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public KeySetId addHandshakeKeys(Transaction txn, PendingContactId p,
			SecretKey rootKey, boolean alice) throws DbException {
		lock.lock();
		try {

			MutableTransportKeySet existing =
					pendingContactOutContexts.get(p);
			if (existing != null) return existing.getKeySetId();
			long timePeriod = clock.currentTimeMillis() / timePeriodLength;
			TransportKeys k = transportCrypto.deriveHandshakeKeys(transportId,
					rootKey, timePeriod, alice);
			KeySetId keySetId = db.addTransportKeys(txn, p, k);
			addKeys(keySetId, null, p, new MutableTransportKeys(k));
			return keySetId;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void activateKeys(Transaction txn, KeySetId k) throws DbException {
		lock.lock();
		try {
			MutableTransportKeySet ks = keys.get(k);
			if (ks == null) throw new IllegalArgumentException();
			MutableTransportKeys m = ks.getKeys();
			m.getCurrentOutgoingKeys().activate();
			considerReplacingOutgoingKeys(ks);
			db.setTransportKeysActive(txn, m.getTransportId(), k);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void removeContact(ContactId c) {
		lock.lock();
		try {
			Iterator<TagContext> it = inContexts.values().iterator();
			while (it.hasNext())
				if (c.equals(it.next().contactId)) it.remove();
			contactOutContexts.remove(c);
			Iterator<MutableTransportKeySet> it1 = keys.values().iterator();
			while (it1.hasNext())
				if (c.equals(it1.next().getContactId())) it1.remove();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void removePendingContact(PendingContactId p) {
		lock.lock();
		try {

			Integer active = activeHandshakes.get(p);
			if (active != null && active > 0) return;
			activeHandshakes.remove(p);
			Iterator<TagContext> it = inContexts.values().iterator();
			while (it.hasNext())
				if (p.equals(it.next().pendingContactId)) it.remove();
			pendingContactOutContexts.remove(p);
			Iterator<MutableTransportKeySet> it1 = keys.values().iterator();
			while (it1.hasNext())
				if (p.equals(it1.next().getPendingContactId())) it1.remove();
		} finally {
			lock.unlock();
		}
	}

	public void acquireHandshakeLock(PendingContactId p) {
		lock.lock();
		try {
			activeHandshakes.merge(p, 1, Integer::sum);
		} finally {
			lock.unlock();
		}
	}

	public void releaseHandshakeLock(PendingContactId p) {
		lock.lock();
		try {
			Integer count = activeHandshakes.get(p);
			if (count != null && count > 1) {
				activeHandshakes.put(p, count - 1);
			} else {
				activeHandshakes.remove(p);
			}
		} finally {
			lock.unlock();
		}
	}

	@Override
	public boolean canSendOutgoingStreams(ContactId c) {
		return canSendOutgoingStreams(c, null);
	}

	@Override
	public boolean canSendOutgoingStreams(PendingContactId p) {
		return canSendOutgoingStreams(null, p);
	}

	private boolean canSendOutgoingStreams(@Nullable ContactId c,
			@Nullable PendingContactId p) {
		lock.lock();
		try {
			MutableTransportKeySet ks = getOutgoingKeySet(c, p);
			if (ks == null) return false;
			MutableOutgoingKeys outKeys = ks.getKeys().getCurrentOutgoingKeys();
			if (!outKeys.isActive()) throw new AssertionError();
			return outKeys.getStreamCounter() <= MAX_32_BIT_UNSIGNED;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public StreamContext getStreamContext(Transaction txn, ContactId c,
			boolean classical) throws DbException {
		return getStreamContext(txn, c, null, classical);
	}

	@Override
	public StreamContext getStreamContext(Transaction txn, PendingContactId p,
			boolean classical) throws DbException {
		return getStreamContext(txn, null, p, classical);
	}

	@Nullable
	private StreamContext getStreamContext(Transaction txn,
			@Nullable ContactId c, @Nullable PendingContactId p,
			boolean classical) throws DbException {
		lock.lock();
		try {
			MutableTransportKeySet ks = getOutgoingKeySet(c, p);
			if (ks == null) return null;
			MutableTransportKeys keys = ks.getKeys();
			MutableOutgoingKeys outKeys = keys.getCurrentOutgoingKeys();
			if (!outKeys.isActive()) throw new AssertionError();
			if (outKeys.getStreamCounter() > MAX_32_BIT_UNSIGNED) return null;
			StreamContext ctx = new StreamContext(c, p, transportId,
					outKeys.getTagKey(), outKeys.getHeaderKey(),
					outKeys.getStreamCounter(), keys.isHandshakeMode(),
					classical);
			outKeys.incrementStreamCounter();
			db.incrementStreamCounter(txn, transportId, ks.getKeySetId());
			return ctx;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public StreamContext getStreamContext(Transaction txn, byte[] tag,
			boolean classical) throws DbException {
		lock.lock();
		try {
			StreamContext ctx = streamContextFromTag(tag, classical);
			if (ctx == null) return null;
			markTagAsRecognised(txn, tag);
			return ctx;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public StreamContext getStreamContextOnly(Transaction txn, byte[] tag,
			boolean classical) {
		lock.lock();
		try {
			return streamContextFromTag(tag, classical);
		} finally {
			lock.unlock();
		}
	}

	@GuardedBy("lock")
	@Nullable
	private StreamContext streamContextFromTag(byte[] tag, boolean classical) {
		TagContext tagCtx = inContexts.get(new Bytes(tag));
		if (tagCtx == null) return null;
		MutableIncomingKeys inKeys = tagCtx.inKeys;
		return new StreamContext(tagCtx.contactId,
				tagCtx.pendingContactId, transportId,
				inKeys.getTagKey(), inKeys.getHeaderKey(),
				tagCtx.streamNumber, tagCtx.handshakeMode, classical);
	}

	@Override
	public void markTagAsRecognised(Transaction txn, byte[] tag)
			throws DbException {
		TagContext tagCtx = inContexts.remove(new Bytes(tag));
		if (tagCtx == null) return;
		MutableIncomingKeys inKeys = tagCtx.inKeys;
		ReorderingWindow window = inKeys.getWindow();
		Change change = window.setSeen(tagCtx.streamNumber);
		for (long streamNumber : change.getAdded()) {
			byte[] addTag = new byte[TAG_LENGTH];
			transportCrypto.encodeTag(addTag, inKeys.getTagKey(),
					PROTOCOL_VERSION, streamNumber);
			TagContext tagCtx1 = new TagContext(tagCtx.keySetId,
					tagCtx.contactId, tagCtx.pendingContactId, inKeys,
					streamNumber, tagCtx.handshakeMode);
			inContexts.put(new Bytes(addTag), tagCtx1);
		}
		for (long streamNumber : change.getRemoved()) {
			if (streamNumber == tagCtx.streamNumber) continue;
			byte[] removeTag = new byte[TAG_LENGTH];
			transportCrypto.encodeTag(removeTag, inKeys.getTagKey(),
					PROTOCOL_VERSION, streamNumber);
			inContexts.remove(new Bytes(removeTag));
		}
		db.setReorderingWindow(txn, tagCtx.keySetId, transportId,
				inKeys.getTimePeriod(), window.getBase(),
				window.getBitmap());
		MutableTransportKeySet ks = keys.get(tagCtx.keySetId);
		MutableOutgoingKeys outKeys =
				ks.getKeys().getCurrentOutgoingKeys();
		if (!outKeys.isActive()) {
			outKeys.activate();
			considerReplacingOutgoingKeys(ks);
			db.setTransportKeysActive(txn, transportId, tagCtx.keySetId);
		}
	}

	@DatabaseExecutor
	@Wakeful
	private void updateKeys(Transaction txn) throws DbException {
		long now = clock.currentTimeMillis();
		lock.lock();
		try {
			Collection<TransportKeySet> snapshot = new ArrayList<>(keys.size());
			for (MutableTransportKeySet ks : keys.values()) {
				snapshot.add(new TransportKeySet(ks.getKeySetId(),
						ks.getContactId(), ks.getPendingContactId(),
						ks.getKeys().snapshot()));
			}
			UpdateResult updateResult = updateKeys(snapshot, now);
			inContexts.clear();
			contactOutContexts.clear();
			pendingContactOutContexts.clear();
			keys.clear();
			addKeys(updateResult.current);
			if (!updateResult.updated.isEmpty())
				db.updateTransportKeys(txn, updateResult.updated);
		} finally {
			lock.unlock();
		}
		scheduleKeyUpdate(now);
	}

	private static class TagContext {

		private final KeySetId keySetId;
		@Nullable
		private final ContactId contactId;
		@Nullable
		private final PendingContactId pendingContactId;
		private final MutableIncomingKeys inKeys;
		private final long streamNumber;
		private final boolean handshakeMode;

		private TagContext(KeySetId keySetId, @Nullable ContactId contactId,
				@Nullable PendingContactId pendingContactId,
				MutableIncomingKeys inKeys, long streamNumber,
				boolean handshakeMode) {
			requireExactlyOneNull(contactId, pendingContactId);
			this.keySetId = keySetId;
			this.contactId = contactId;
			this.pendingContactId = pendingContactId;
			this.inKeys = inKeys;
			this.streamNumber = streamNumber;
			this.handshakeMode = handshakeMode;
		}
	}

	private static class UpdateResult {

		private final Collection<TransportKeySet> current = new ArrayList<>();
		private final Collection<TransportKeySet> updated = new ArrayList<>();
	}
}
