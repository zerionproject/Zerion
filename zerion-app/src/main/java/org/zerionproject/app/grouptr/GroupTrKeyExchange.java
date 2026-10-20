package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupMemberListSnapshotEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.system.TaskScheduler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.app.grouptr.GroupTrMemberInvites.JOIN_WINDOW_MS;

@ThreadSafe
@NotNullByDefault
final class GroupTrKeyExchange {

	static final int PROBE_SLOTS = 16;
	static final long PROBE_DELAY_MIN_MS = 2L * 60L * 1000L;
	static final long PROBE_DELAY_SPREAD_MS = 8L * 60L * 1000L;
	static final int MAX_PENDING_PER_GROUP = 32;
	static final long PENDING_KEPT_MS = JOIN_WINDOW_MS;
	private static final String LABEL_SNAPSHOT =
			"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT";

	interface Host {

		@Nullable
		GroupTrState getGroup(byte[] groupId) throws DbException;

		Collection<GroupTrState> getGroups() throws DbException;

		ReentrantLock lockFor(byte[] groupId);

		void dispatch(Transaction txn, Contact c, long timestamp, BdfList body)
				throws DbException;

		@Nullable
		GroupTrKeys.Binding ownBinding(byte[] groupId) throws DbException;

		boolean isRemoved(byte[] groupId, byte[] pubKey) throws DbException;

		void bindingsChanged(byte[] groupId);

		byte[] localKey(GroupTrState s) throws DbException;

		byte[] sign(GroupTrState s, String label, byte[] signed)
				throws DbException;

		boolean verify(GroupTrState s, byte[] sig, String label, byte[] signed,
				byte[] pubKey);

		@Nullable
		byte[] boundIdentity(GroupTrState s, byte[] pubKey) throws DbException;

		void noteRemovedContact(byte[] groupId, byte[] pubKey, byte[] tag)
				throws DbException;

		void forgetRemovedTag(byte[] groupId, byte[] tag) throws DbException;
	}

	static final byte DEPARTED_REMOVED = 0;
	static final byte DEPARTED_LEFT = 1;

	private final DatabaseComponent db;
	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final GroupTrKeys keys;
	private final Clock clock;
	private final TaskScheduler scheduler;
	private final Executor ioExecutor;
	private final SecureRandom random;
	private final Host host;
	private final ClientHelper clientHelper;

	GroupTrKeyExchange(DatabaseComponent db, ContactManager contactManager,
			MessagingManager messagingManager, GroupTrKeys keys, Clock clock,
			TaskScheduler scheduler, Executor ioExecutor, SecureRandom random,
			ClientHelper clientHelper, Host host) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
		this.keys = keys;
		this.clock = clock;
		this.scheduler = scheduler;
		this.ioExecutor = ioExecutor;
		this.random = random;
		this.host = host;
	}

	static boolean listed(GroupTrState s, byte[] pubKey) {
		if (Arrays.equals(pubKey, s.getCreatorPubKey())) return true;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pubKey)) return true;
		}
		return false;
	}

	private static boolean lists(byte[] memberCanonical, byte[] pubKey) {
		for (int off = 0; off + 37 <= memberCanonical.length; off += 37) {
			if (Arrays.equals(Arrays.copyOfRange(memberCanonical, off,
					off + 32), pubKey)) {
				return true;
			}
		}
		return false;
	}

	void onRecord(ContactId contactId, int type, BdfList record) {
		try {
			if (type == MessageTypes.GROUP_KEY_BINDING) {
				GroupTrKeys.Binding b =
						GroupTrKeys.Binding.parse(record.getList(2));
				GroupTrState s = host.getGroup(record.getRaw(1));
				if (s != null) claim(s, contactId, b, false);
			} else {
				receiveProbe(contactId, record.getList(1));
			}
		} catch (DbException | FormatException | RuntimeException ex) {
		}
	}

	private void receiveProbe(ContactId contactId, BdfList entries)
			throws DbException, FormatException {
		if (entries.isEmpty() || entries.size() > PROBE_SLOTS) return;
		long now = clock.currentTimeMillis();
		List<GroupTrState> held = new ArrayList<>();
		List<List<byte[]>> secrets = new ArrayList<>();
		for (GroupTrState s : host.getGroups()) {
			if (s.isDissolved() || !s.usesGroupKeys()) continue;
			GroupTrKeys.Secret secret = keys.secret(s.getGroupId());
			if (secret == null) continue;
			List<byte[]> usable = new ArrayList<>(2);
			usable.add(secret.secret);
			byte[] previous = keys.previousSecret(s.getGroupId(), now);
			if (previous != null) usable.add(previous);
			held.add(s);
			secrets.add(usable);
		}
		boolean[] matched = new boolean[held.size()];
		for (int i = 0; i < entries.size(); i++) {
			BdfList entry = entries.getList(i);
			byte[] nonce = entry.getRaw(0);
			byte[] tag = entry.getRaw(1);
			byte[] sealed = entry.getRaw(2);
			if (nonce.length != GroupTrKeys.PROBE_NONCE_LENGTH
					|| tag.length != 32) {
				continue;
			}
			for (int g = 0; g < held.size(); g++) {
				if (matched[g]) continue;
				byte[] secret = null;
				for (byte[] candidate : secrets.get(g)) {
					if (Arrays.equals(tag, keys.probeTag(candidate, nonce))) {
						secret = candidate;
						break;
					}
				}
				if (secret == null) continue;
				matched[g] = true;
				GroupTrKeys.Binding b = keys.open(secret, nonce, sealed);
				if (b != null) claim(held.get(g), contactId, b, true);
			}
		}
	}

	void createSecret(Transaction txn, byte[] groupId) throws DbException {
		keys.storeSecret(txn, groupId, new GroupTrKeys.Secret(1L,
				bytes(GroupTrKeys.SECRET_LENGTH), new ArrayList<>()),
				clock.currentTimeMillis());
	}

	boolean hasSecret(byte[] groupId) throws DbException {
		return keys.secret(groupId) != null;
	}

	void rotate(byte[] groupId, byte[] departedKey, byte kind)
			throws DbException {
		GroupTrKeys.Secret current = keys.secret(groupId);
		long seq = current == null ? 1L : current.seq + 1L;
		List<byte[]> departed = new ArrayList<>();
		if (current != null) departed.addAll(current.departed);
		byte[] entry = new byte[33];
		System.arraycopy(departedKey, 0, entry, 0, 32);
		entry[32] = kind;
		departed.add(entry);
		while (departed.size() > GroupTrKeys.MAX_DEPARTED) departed.remove(0);
		GroupTrKeys.Secret next = new GroupTrKeys.Secret(seq,
				bytes(GroupTrKeys.SECRET_LENGTH), departed);
		db.transaction(false, txn -> keys.storeSecret(txn, groupId, next,
				clock.currentTimeMillis()));
	}

	@Nullable
	BdfList secretBlob(GroupTrState s, long epoch) throws DbException {
		GroupTrKeys.Secret secret = keys.secret(s.getGroupId());
		if (secret == null) return null;
		byte[] sig = host.sign(s, GroupTrKeys.LABEL_SECRET,
				keys.secretInput(s.getGroupId(), epoch, secret));
		return GroupTrKeys.secretList(secret, sig);
	}

	void onSnapshot(GroupMemberListSnapshotEvent e) {
		byte[] groupId = e.getGroupId();
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.getGroup(groupId);
			if (s == null || s.isDissolved() || !s.usesGroupKeys()) return;
			BdfList r = clientHelperList(e.getRecord());
			if (r == null || r.size() != 7) return;
			BdfList blob = r.getList(6);
			GroupTrKeys.Secret secret = GroupTrKeys.parseSecret(blob);
			byte[] sig = blob.getRaw(3);
			byte[] local = host.localKey(s);
			byte[] creator = s.getCreatorPubKey();
			if (Arrays.equals(local, creator)) return;
			if (!listed(s, local)) return;
			if (!lists(e.getMemberCanonical(), local)) return;
			GroupTrKeys.Secret stored = keys.secret(groupId);
			if (stored != null && secret.seq <= stored.seq) return;
			if (!host.verify(s, e.getRecordSig(), LABEL_SNAPSHOT,
					e.getSignedInput(), creator)) {
				return;
			}
			if (!host.verify(s, sig, GroupTrKeys.LABEL_SECRET,
					keys.secretInput(groupId, e.getEpoch(), secret),
					creator)) {
				return;
			}
			long now = clock.currentTimeMillis();
			db.transaction(false, txn ->
					keys.storeSecret(txn, groupId, secret, now));
			for (byte[] d : secret.departed) {
				byte[] key = Arrays.copyOf(d, 32);
				byte[] identity = host.boundIdentity(s, key);
				if (identity == null) continue;
				byte[] tag = keys.removalTag(groupId, identity);
				if (d[32] == DEPARTED_REMOVED) {
					host.noteRemovedContact(groupId, key, tag);
				} else {
					host.forgetRemovedTag(groupId, tag);
				}
			}
			if (stored == null) joined(groupId);
		} catch (DbException | FormatException | RuntimeException ex) {
		} finally {
			lock.unlock();
		}
	}

	@Nullable
	private BdfList clientHelperList(byte[] record) {
		try {
			return clientHelper.toList(record);
		} catch (FormatException ex) {
			return null;
		}
	}

	private void claim(GroupTrState state, ContactId contactId,
			GroupTrKeys.Binding b, boolean answer) throws DbException {
		byte[] groupId = state.getGroupId();
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.getGroup(groupId);
			if (s == null || s.isDissolved() || !s.usesGroupKeys()) return;
			GroupTrKeys.Pub own = keys.pub(groupId);
			if (own == null || Arrays.equals(own.pub, b.pub)) return;
			Contact c = contactOrNull(contactId);
			if (c == null) return;
			byte[] identity = c.getAuthor().getPublicKey().getEncoded();
			if (!keys.verify(groupId, b, identity, c.getMlDsaSigPublicKey())) {
				return;
			}
			for (GroupTrMember m : s.getMembers()) {
				if (!Arrays.equals(m.getPubKey(), b.pub)) continue;
				byte[] known = m.getMlDsaPubKey();
				if (known != null && !Arrays.equals(known, b.mlDsa)) return;
			}
			int id = contactId.getInt();
			long now = clock.currentTimeMillis();
			if (listed(s, b.pub)) {
				admit(s, c, identity, new GroupTrKeys.Bound(id, identity,
						b.pub, b.mlDsa, b.timestamp), answer, now);
				return;
			}
			if (host.isRemoved(groupId, b.pub)) {
				db.transaction(false, txn -> keys.dropPending(txn, groupId,
						Collections.singletonList(id)));
				return;
			}
			Map<Integer, GroupTrKeys.Pending> pending = keys.pending(groupId);
			if (!pending.containsKey(id)
					&& pending.size() >= MAX_PENDING_PER_GROUP) {
				return;
			}
			db.transaction(false, txn -> keys.storePending(txn, groupId, id,
					identity, b, now, answer));
		} finally {
			lock.unlock();
		}
	}

	private void admit(GroupTrState s, Contact c, byte[] identity,
			GroupTrKeys.Bound b, boolean answer, long now) throws DbException {
		byte[] groupId = s.getGroupId();
		int id = b.contactId;
		GroupTrKeys.Bound previous = keys.bindings(groupId).get(id);
		boolean sameIdentity = previous != null
				&& Arrays.equals(previous.identity, identity);
		boolean same = sameIdentity && Arrays.equals(previous.pub, b.pub);
		boolean refused = sameIdentity && !same && listed(s, previous.pub)
				&& previous.timestamp >= b.timestamp;
		boolean store = !same && !refused;
		GroupTrKeys.Binding mine = answer && !refused
				&& !keys.answeredRecently(groupId, id, b.pub, now)
				? host.ownBinding(groupId) : null;
		db.transaction(false, txn -> {
			keys.dropPending(txn, groupId, Collections.singletonList(id));
			if (store) {
				keys.storeBinding(txn, groupId, id, identity, b.pub, b.mlDsa,
						b.timestamp);
			}
			if (mine != null) {
				host.dispatch(txn, c, now, BdfList.of(
						(long) MessageTypes.GROUP_KEY_BINDING, groupId,
						mine.toList()));
				keys.markAnswered(txn, groupId, id, b.pub, now);
			}
		});
		if (store) host.bindingsChanged(groupId);
	}

	void membersChanged(byte[] groupId) {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.getGroup(groupId);
			if (s == null || !s.usesGroupKeys()) return;
			Map<Integer, GroupTrKeys.Pending> pending = keys.pending(groupId);
			if (pending.isEmpty()) return;
			long now = clock.currentTimeMillis();
			List<Integer> dropped = new ArrayList<>();
			for (Map.Entry<Integer, GroupTrKeys.Pending> e
					: pending.entrySet()) {
				GroupTrKeys.Pending p = e.getValue();
				GroupTrKeys.Bound b = p.bound;
				Contact c = contactOrNull(new ContactId(e.getKey()));
				if (s.isDissolved() || c == null
						|| !Arrays.equals(c.getAuthor().getPublicKey()
						.getEncoded(), b.identity)
						|| now - p.received > PENDING_KEPT_MS
						|| p.received > now + PENDING_KEPT_MS
						|| host.isRemoved(groupId, b.pub)) {
					dropped.add(e.getKey());
				} else if (listed(s, b.pub)) {
					admit(s, c, b.identity, b, p.answer, now);
				}
			}
			db.transaction(false, txn ->
					keys.dropPending(txn, groupId, dropped));
		} catch (DbException | RuntimeException ex) {
		} finally {
			lock.unlock();
		}
	}

	void joined(byte[] groupId) {
		long delay = PROBE_DELAY_MIN_MS
				+ (long) (random.nextDouble() * PROBE_DELAY_SPREAD_MS);
		long due = clock.currentTimeMillis() + delay;
		try {
			db.transaction(false, txn -> keys.setProbeDue(txn, groupId, due));
		} catch (DbException ex) {
			return;
		}
		scheduler.schedule(() -> probeGroup(groupId), ioExecutor, delay,
				TimeUnit.MILLISECONDS);
	}

	void sendDueProbes() {
		try {
			long now = clock.currentTimeMillis();
			for (GroupTrState s : host.getGroups()) {
				if (!s.usesGroupKeys()) continue;
				byte[] groupId = s.getGroupId();
				long due = keys.probeDue(groupId);
				if (due < 0L) continue;
				if (due <= now || due > now + PROBE_DELAY_MIN_MS
						+ PROBE_DELAY_SPREAD_MS) {
					probeGroup(groupId);
				} else {
					scheduler.schedule(() -> probeGroup(groupId), ioExecutor,
							due - now, TimeUnit.MILLISECONDS);
				}
			}
		} catch (DbException | RuntimeException ex) {
		}
	}

	private void probeGroup(byte[] groupId) {
		try {
			GroupTrState s = host.getGroup(groupId);
			if (s == null || s.isDissolved() || !s.usesGroupKeys()) return;
			GroupTrKeys.Secret secret = keys.secret(groupId);
			if (secret == null) return;
			GroupTrKeys.Binding mine = host.ownBinding(groupId);
			if (mine == null) return;
			long now = clock.currentTimeMillis();
			db.transaction(false, txn -> {
				Map<Integer, GroupTrKeys.Bound> bound =
						keys.bindings(txn, groupId);
				for (Contact c : contactManager.getContacts(txn)) {
					if (!wanted(txn, groupId, c, bound)) continue;
					List<BdfList> entries = new ArrayList<>(1);
					entries.add(entry(secret.secret, mine));
					host.dispatch(txn, c, now, probe(entries));
					keys.markProbed(txn, groupId, c.getId().getInt(), now);
				}
				keys.clearProbeDue(txn, groupId);
			});
		} catch (DbException | RuntimeException ex) {
		}
	}

	void contactUpgraded(ContactId contactId) {
		try {
			Contact c = contactOrNull(contactId);
			if (c == null) return;
			List<byte[]> groups = new ArrayList<>();
			List<byte[]> secrets = new ArrayList<>();
			List<GroupTrKeys.Binding> mine = new ArrayList<>();
			for (GroupTrState s : host.getGroups()) {
				if (s.isDissolved() || !s.usesGroupKeys()) continue;
				byte[] groupId = s.getGroupId();
				GroupTrKeys.Secret secret = keys.secret(groupId);
				if (secret == null) continue;
				boolean wanted = db.transactionWithResult(true, txn ->
						wanted(txn, groupId, c, keys.bindings(txn, groupId)));
				if (!wanted) continue;
				GroupTrKeys.Binding b = host.ownBinding(groupId);
				if (b == null) continue;
				groups.add(groupId);
				secrets.add(secret.secret);
				mine.add(b);
			}
			if (groups.isEmpty()) return;
			long now = clock.currentTimeMillis();
			db.transaction(false, txn -> {
				for (int from = 0; from < groups.size(); from += PROBE_SLOTS) {
					int to = Math.min(groups.size(), from + PROBE_SLOTS);
					List<BdfList> entries = new ArrayList<>(PROBE_SLOTS);
					for (int i = from; i < to; i++) {
						entries.add(entry(secrets.get(i), mine.get(i)));
					}
					while (entries.size() < PROBE_SLOTS) {
						entries.add(padding());
					}
					Collections.shuffle(entries, random);
					host.dispatch(txn, c, now, probe(entries));
				}
				for (byte[] groupId : groups) {
					keys.markProbed(txn, groupId, c.getId().getInt(), now);
				}
			});
		} catch (DbException | RuntimeException ex) {
		}
	}

	private boolean wanted(Transaction txn, byte[] groupId, Contact c,
			Map<Integer, GroupTrKeys.Bound> bound) throws DbException {
		int id = c.getId().getInt();
		GroupTrKeys.Bound b = bound.get(id);
		if (b != null && Arrays.equals(b.identity,
				c.getAuthor().getPublicKey().getEncoded())) {
			return false;
		}
		if (keys.probed(txn, groupId, id)) return false;
		return messagingManager.getContactClientMinorVersion(txn, c.getId())
				>= MessagingManager.GROUP_MEMBER_KEYS_MIN_VERSION;
	}

	private BdfList entry(byte[] secret, GroupTrKeys.Binding mine)
			throws DbException {
		byte[] nonce = bytes(GroupTrKeys.PROBE_NONCE_LENGTH);
		return BdfList.of(nonce, keys.probeTag(secret, nonce),
				keys.seal(secret, nonce, mine));
	}

	private BdfList padding() {
		return BdfList.of(bytes(GroupTrKeys.PROBE_NONCE_LENGTH), bytes(32),
				bytes(GroupTrKeys.SEALED_BINDING_BYTES));
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	private static BdfList probe(List<BdfList> entries) {
		BdfList list = new BdfList();
		list.addAll(entries);
		return BdfList.of((long) MessageTypes.GROUP_KEY_PROBE, list);
	}

	@Nullable
	private Contact contactOrNull(ContactId contactId) throws DbException {
		try {
			return db.transactionWithResult(true,
					txn -> contactManager.getContact(txn, contactId));
		} catch (NoSuchContactException ex) {
			return null;
		}
	}
}
