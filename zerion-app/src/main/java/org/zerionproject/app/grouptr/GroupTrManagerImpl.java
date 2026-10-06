package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMeshSink;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPendingInvite;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrSentInvite;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupEpochCommitEvent;
import org.zerionproject.app.api.messaging.event.GroupMemberListSnapshotEvent;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.contact.ContactId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.zerionproject.app.grouptr.GroupTrConstants.CLIENT_ID;
import static org.zerionproject.app.grouptr.GroupTrConstants.FORMAT_VERSION;
import static org.zerionproject.app.grouptr.GroupTrConstants.GROUP_ID_LABEL;
import static org.zerionproject.app.grouptr.GroupTrConstants.GROUP_SALT_LENGTH;
import static org.zerionproject.app.grouptr.GroupTrConstants.MAJOR_VERSION;
import static org.zerionproject.app.grouptr.GroupTrConstants.SETTINGS_NS_INDEX;
import static org.zerionproject.app.grouptr.GroupTrConstants.SETTINGS_NS_PREFIX;
import static org.zerionproject.app.grouptr.GroupTrConstants.SIGNING_LABEL_GROUP_EPOCH_COMMIT;
import static org.zerionproject.app.grouptr.GroupTrConstants.SIGNING_LABEL_GROUP_SETTINGS;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_SETTINGS_TIMESTAMP;
import static org.zerionproject.app.grouptr.GroupTrConstants.SIGNING_LABEL_GROUP_MEMBERSHIP;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_CREATED;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_CREATOR_NAME;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_CREATOR_PUBKEY;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_DEFAULT_TTL;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_DISSOLVED;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_EPOCH;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_GROUP_IDS;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_MEMBERS;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_NAME;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_REMOVED;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_SALT;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_STEALTH_NAME;
import static org.zerionproject.app.grouptr.GroupTrConstants.SETTINGS_NS_LOCAL_PREFIX;
import static org.zerionproject.app.grouptr.GroupTrConstants.S_SCREENSHOT_BLOCKED;
import static org.zerionproject.app.grouptr.GroupTrConstants.SETTINGS_NS_INVITES_SENT;
import static org.zerionproject.app.grouptr.GroupTrConstants.SETTINGS_NS_OFFERS_PENDING;
import static org.zerionproject.app.grouptr.GroupTrConstants.SIGNING_LABEL_GROUPTR_INVITE_OFFER;
import static org.zerionproject.app.grouptr.GroupTrConstants.SIGNING_LABEL_GROUPTR_INVITE_ACCEPT;
import static org.zerionproject.app.grouptr.GroupTrConstants.SIGNING_LABEL_GROUPTR_INVITE_DECLINE;

@ThreadSafe
@NotNullByDefault
class GroupTrManagerImpl
		implements GroupTrManager, EventListener, OpenDatabaseHook {

	private final DatabaseComponent db;
	private final Executor ioExecutor;
	private final SettingsManager settingsManager;
	private final ClientHelper clientHelper;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;
	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final EventBus eventBus;
	private final Clock clock;
	private final SecureRandom random;
	@Nullable
	private volatile GroupTrMeshSink meshSink;
	private final java.util.Map<String, java.util.ArrayDeque<GroupTrPost>>
			postCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String,
			java.util.TreeMap<Long, java.util.List<BufferedPost>>>
			futureBuffer = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> historyLoaded =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final int MAX_CACHED_POSTS_PER_GROUP = 200;
	private static final long MAX_CACHED_BYTES_PER_GROUP = 24L * 1024L * 1024L;
	private static final long MAX_CACHED_BYTES_TOTAL = 64L * 1024L * 1024L;
	private static final int STORED_POSTS_PER_READ = 64;
	private static final int EPOCH_BUFFER_TOLERANCE = 5;
	private static final int MAX_BUFFERED_POSTS_PER_GROUP = 500;
	private static final long MAX_BUFFERED_BYTES_PER_GROUP =
			8L * 1024L * 1024L;
	private static final int MAX_BUFFERED_POSTS_PER_SENDER = 125;
	private static final long MAX_BUFFERED_BYTES_PER_SENDER =
			2L * 1024L * 1024L;
	private static final long MAX_BUFFERED_BYTES_TOTAL = 16L * 1024L * 1024L;
	private static final int GROUP_LOCK_STRIPES = 64;
	private static final int MAX_PENDING_POSTS_PER_CONTACT = 125;
	private static final long MAX_PENDING_BYTES_PER_CONTACT =
			24L * 1024L * 1024L;
	private static final long CONVERSION_BATCH_BYTES = 4L * 1024L * 1024L;
	static final long LEAVE_CLOCK_SKEW_MS = 5L * 60L * 1000L;
	private final java.util.Map<String, long[]> pendingHeld =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final String STORAGE_NAMESPACE = "grouptr.storage";
	private static final String KEY_POST_FORMAT = "postFormat";
	private static final int POST_FORMAT_BODY_OUTSIDE_METADATA = 2;
	private static final long MAX_TIMER_MS = org.zerionproject.app.api
			.autodelete.AutoDeleteConstants.MAX_AUTO_DELETE_TIMER_MS;
	private static final long MIN_TIMER_MS = org.zerionproject.app.api
			.autodelete.AutoDeleteConstants.MIN_AUTO_DELETE_TIMER_MS;

	private final MlDsaKeyDirectory mlDsaKeys = new MlDsaKeyDirectory(
			this::lookupMemberMlDsaPubKey, this::lookupLocalMlDsaPubKey,
			this::lookupContactMlDsaPubKey);

	private final java.util.concurrent.locks.ReentrantLock[] groupLocks =
			newGroupLocks();

	private final Object indexLock = new Object();

	private static java.util.concurrent.locks.ReentrantLock[] newGroupLocks() {
		java.util.concurrent.locks.ReentrantLock[] locks =
				new java.util.concurrent.locks.ReentrantLock[
						GROUP_LOCK_STRIPES];
		for (int i = 0; i < locks.length; i++) {
			locks[i] = new java.util.concurrent.locks.ReentrantLock();
		}
		return locks;
	}

	private java.util.concurrent.locks.ReentrantLock lockFor(byte[] gid) {
		return lockFor(toHexString(gid));
	}

	private java.util.concurrent.locks.ReentrantLock lockFor(String groupHex) {
		return groupLocks[Math.floorMod(groupHex.hashCode(),
				GROUP_LOCK_STRIPES)];
	}

	private static final class BufferedPost {

		private final GroupTrPost post;
		private final byte[] identity;
		@Nullable
		private final MessageId id;

		private BufferedPost(GroupTrPost post, byte[] identity,
				@Nullable MessageId id) {
			this.post = post;
			this.identity = identity;
			this.id = id;
		}
	}

	private static final class StoredPost {

		private final MessageId id;
		private final long timestamp;
		private final long order;
		private final int sender;
		private final long size;
		private final boolean checked;
		@Nullable
		private List<MessageId> otherCopies;

		private StoredPost(MessageId id, long timestamp, long order,
				int sender, long size) {
			this(id, timestamp, order, sender, size, false);
		}

		private StoredPost(MessageId id, long timestamp, long order,
				int sender, long size, boolean checked) {
			this.id = id;
			this.timestamp = timestamp;
			this.order = order;
			this.sender = sender;
			this.size = size;
			this.checked = checked;
		}
	}

	private static final class StoredRecord {

		private final GroupTrPost post;
		@Nullable
		private final byte[] sig;
		private final byte[] bodyHash;
		private final long bodyLength;
		@Nullable
		private final Integer state;
		private final long effectiveTtl;

		private StoredRecord(GroupTrPost post, @Nullable byte[] sig,
				byte[] bodyHash, long bodyLength, @Nullable Integer state,
				long effectiveTtl) {
			this.post = post;
			this.sig = sig;
			this.bodyHash = bodyHash;
			this.bodyLength = bodyLength;
			this.state = state;
			this.effectiveTtl = effectiveTtl;
		}

		private long expiryTime() {
			return GroupTrPost.expiryTime(post.getTimerStart(), effectiveTtl);
		}
	}

	private interface StoredPostVisitor {

		void visit(int index, BdfDictionary meta) throws FormatException;
	}

	@Inject
	GroupTrManagerImpl(DatabaseComponent db, @IoExecutor Executor ioExecutor,
			SettingsManager settingsManager, ClientHelper clientHelper,
			CryptoComponent crypto, IdentityManager identityManager,
			ContactManager contactManager, MessagingManager messagingManager,
			EventBus eventBus, Clock clock) {
		this.db = db;
		this.ioExecutor = ioExecutor;
		this.settingsManager = settingsManager;
		this.clientHelper = clientHelper;
		this.crypto = crypto;
		this.identityManager = identityManager;
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
		this.eventBus = eventBus;
		this.clock = clock;
		this.random = new SecureRandom();
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		stampLegacySentInvites(txn, clock.currentTimeMillis());
		eventBus.addListener(this);
		purgeExpiredPosts();
		ioExecutor.execute(this::convertStoredStateSafely);
	}

	private void convertStoredStateSafely() {
		try {
			convertStoredState();
		} catch (DbException | RuntimeException ex) {
		}
	}

	private void convertStoredState() throws DbException {
		Settings done = settingsManager.getSettings(STORAGE_NAMESPACE);
		if (done.getInt(KEY_POST_FORMAT, 0)
				>= POST_FORMAT_BODY_OUTSIDE_METADATA) {
			return;
		}
		long now = clock.currentTimeMillis();
		java.util.List<MessageId> ids = new java.util.ArrayList<>();
		db.transaction(true, txn -> {
			BdfDictionary query = new BdfDictionary();
			query.put("messageType", 32L);
			for (Contact c : contactManager.getContacts(txn)) {
				org.zerionproject.core.api.sync.GroupId cg =
						messagingManager.getContactGroup(c).getId();
				try {
					ids.addAll(clientHelper.getMessageIds(txn, cg, query));
				} catch (DbException | FormatException ex) {
				}
			}
		});
		int[] next = {0};
		while (next[0] < ids.size()) {
			java.util.List<Converted> batch = new java.util.ArrayList<>();
			long[] bytes = {0L};
			db.transaction(true, txn -> {
				while (next[0] < ids.size()
						&& batch.size() < STORED_POSTS_PER_READ
						&& bytes[0] < CONVERSION_BATCH_BYTES) {
					MessageId id = ids.get(next[0]++);
					try {
						Converted c = convertStoredPost(txn, id, now);
						if (c == null) continue;
						batch.add(c);
						bytes[0] += c.bodyLength;
					} catch (DbException | FormatException ex) {
					}
				}
			});
			if (batch.isEmpty()) continue;
			db.transaction(false, txn -> {
				for (Converted c : batch) {
					try {
						applyConverted(txn, c);
					} catch (DbException | FormatException ex) {
					}
				}
			});
		}
		convertLegacyTimers(now);
		Settings mark = new Settings();
		mark.putInt(KEY_POST_FORMAT, POST_FORMAT_BODY_OUTSIDE_METADATA);
		settingsManager.mergeSettings(mark, STORAGE_NAMESPACE);
	}

	private static final class Converted {

		private final MessageId id;
		private final boolean expired;
		private final long expiry;
		@Nullable
		private final BdfDictionary update;
		private final long bodyLength;

		private Converted(MessageId id, boolean expired, long expiry,
				@Nullable BdfDictionary update, long bodyLength) {
			this.id = id;
			this.expired = expired;
			this.expiry = expiry;
			this.update = update;
			this.bodyLength = bodyLength;
		}
	}

	@Nullable
	private Converted convertStoredPost(Transaction txn, MessageId id,
			long now) throws DbException, FormatException {
		long ts;
		long ttl;
		byte[] body;
		BdfDictionary meta = readStoredMetadata(txn, id);
		if (meta != null) {
			Integer mt = meta.getOptionalInt("messageType");
			if (mt == null || mt != 32) return null;
			ts = meta.getLong("timestamp", 0L);
			ttl = meta.getLong("autoDeleteTimer", 0L);
			body = meta.getOptionalRaw("groupCiphertext");
		} else {
			Message m = clientHelper.getMessage(txn, id);
			BdfList record = clientHelper.toList(m);
			ts = m.getTimestamp();
			ttl = record.size() > 7 ? record.getLong(7) : 0L;
			body = record.getRaw(5);
		}
		long expiry = GroupTrPost.expiryTime(ts, ttl);
		if (expiry <= now) return new Converted(id, true, expiry, null, 0L);
		if (body == null) return new Converted(id, false, expiry, null, 0L);
		BdfDictionary update = new BdfDictionary();
		update.put(MessagingManager.MSG_KEY_GROUP_BODY_HASH, bodyHash(body));
		update.put(MessagingManager.MSG_KEY_GROUP_BODY_LENGTH,
				(long) body.length);
		update.put("groupCiphertext", BdfDictionary.NULL_VALUE);
		return new Converted(id, false, expiry, update, body.length);
	}

	private void applyConverted(Transaction txn, Converted c)
			throws DbException, FormatException {
		try {
			if (c.expired) {
				db.removeMessage(txn, c.id);
				return;
			}
			if (c.expiry != Long.MAX_VALUE) {
				db.setCleanupDeadline(txn, c.id, c.expiry);
			}
			if (c.update != null) {
				clientHelper.mergeMessageMetadata(txn, c.id, c.update);
			}
		} catch (org.zerionproject.core.api.db.NoSuchMessageException ex) {
		}
	}

	private void convertLegacyTimers(long now) throws DbException {
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		byte[] localPub = la.getPublicKey().getEncoded();
		for (GroupTrState s : getGroups()) {
			if (s.isDissolved()) continue;
			if (!Arrays.equals(s.getCreatorPubKey(), localPub)) continue;
			if (s.getDefaultAutoDeleteTimerMs() <= 0) continue;
			Settings current = settingsManager.getSettings(
					nsOf(s.getGroupId()));
			if (current.get(S_SETTINGS_TIMESTAMP) != null) continue;
			java.util.concurrent.locks.ReentrantLock lock =
					lockFor(s.getGroupId());
			lock.lock();
			try {
				persistSettings(s, now);
				BdfList record = settingsRecord(s, la.getPrivateKey());
				List<byte[]> to = new ArrayList<>();
				for (GroupTrMember m : s.getMembers()) {
					if (!Arrays.equals(m.getPubKey(), localPub)) {
						to.add(m.getPubKey());
					}
				}
				db.transaction(false,
						txn -> sendSettings(txn, s, to, record, now));
			} catch (FormatException ex) {
				throw new DbException(ex);
			} finally {
				lock.unlock();
			}
		}
	}

	private void purgeExpiredPosts() {
		long now = clock.currentTimeMillis();
		for (java.util.Map.Entry<String,
				java.util.ArrayDeque<GroupTrPost>> e
				: postCache.entrySet()) {
			java.util.ArrayDeque<GroupTrPost> q = e.getValue();
			synchronized (q) {
				java.util.Iterator<GroupTrPost> it = q.iterator();
				while (it.hasNext()) {
					if (it.next().isExpiredAt(now)) it.remove();
				}
			}
		}
		for (java.util.Map.Entry<String,
				java.util.TreeMap<Long, java.util.List<BufferedPost>>> e
				: futureBuffer.entrySet()) {
			java.util.TreeMap<Long, java.util.List<BufferedPost>> tm =
					e.getValue();
			synchronized (tm) {
				java.util.Iterator<java.util.Map.Entry<Long,
						java.util.List<BufferedPost>>> entries =
						tm.entrySet().iterator();
				while (entries.hasNext()) {
					java.util.List<BufferedPost> list =
							entries.next().getValue();
					list.removeIf(b -> b.post.isExpiredAt(now));
					if (list.isEmpty()) entries.remove();
				}
			}
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof GroupMembershipChangedEvent) {
			handleMembershipEvent((GroupMembershipChangedEvent) e);
		} else if (e instanceof org.zerionproject.app.api.messaging.event
				.GroupSettingsChangedEvent) {
			handleGroupSettings((org.zerionproject.app.api.messaging.event
					.GroupSettingsChangedEvent) e);
		} else if (e instanceof GroupEpochCommitEvent) {
			handleEpochCommit((GroupEpochCommitEvent) e);
		} else if (e instanceof GroupPostReceivedEvent) {
			receivePost((GroupPostReceivedEvent) e);
		} else if (e instanceof GroupMemberListSnapshotEvent) {
			handleMemberListSnapshot((GroupMemberListSnapshotEvent) e);
		} else if (e instanceof org.zerionproject.app.api.messaging.event
				.GroupTrInviteOfferReceivedEvent) {
			handleGrouptrInviteOffer(
					(org.zerionproject.app.api.messaging.event
							.GroupTrInviteOfferReceivedEvent) e);
		} else if (e instanceof org.zerionproject.app.api.messaging.event
				.GroupTrInviteResponseReceivedEvent) {
			handleGrouptrInviteResponse(
					(org.zerionproject.app.api.messaging.event
							.GroupTrInviteResponseReceivedEvent) e);
		} else if (e instanceof org.zerionproject.core.api.versioning.event
				.ClientVersionUpdatedEvent) {
			handleClientVersionUpdated(
					(org.zerionproject.core.api.versioning.event
							.ClientVersionUpdatedEvent) e);
		} else if (e instanceof org.zerionproject.core.api.contact.event
				.ContactRemovedEvent
				|| e instanceof org.zerionproject.core.api.contact.event
				.ContactAddedEvent) {
			mlDsaKeys.invalidate();
		}
	}

	private void handleClientVersionUpdated(
			org.zerionproject.core.api.versioning.event
					.ClientVersionUpdatedEvent e) {
		org.zerionproject.core.api.versioning.ClientVersion v =
				e.getClientVersion();
		if (!v.getClientId().equals(MessagingManager.CLIENT_ID)) return;
		if (v.getMajorVersion() != MessagingManager.MAJOR_VERSION) return;
		if (v.getMinorVersion()
				< MessagingManager.GROUP_PROTOCOL_V2_MIN_VERSION) {
			return;
		}
		byte[] memberPub = lookupSenderPubKey(e.getContactId());
		if (memberPub == null) return;
		try {
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			byte[] localPub = la.getPublicKey().getEncoded();
			for (GroupTrState s : getGroups()) {
				if (s.isDissolved()) continue;
				if (!Arrays.equals(s.getCreatorPubKey(), localPub)) continue;
				if (!isMemberOrCreator(s, memberPub)) continue;
				Settings current = settingsManager.getSettings(
						nsOf(s.getGroupId()));
				if (current.get(S_SETTINGS_TIMESTAMP) == null) continue;
				java.util.concurrent.locks.ReentrantLock lock =
						lockFor(s.getGroupId());
				lock.lock();
				try {
					BdfList record = settingsRecord(s, la.getPrivateKey());
					db.transaction(false, txn -> sendSettings(txn, s,
							Collections.singletonList(memberPub), record,
							clock.currentTimeMillis()));
				} finally {
					lock.unlock();
				}
			}
		} catch (DbException ex) {
		}
	}

	private enum Verdict {
		ACCEPTED, BUFFERED, PENDING, REFUSED
	}

	private void receivePost(GroupPostReceivedEvent e) {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(e.getGroupId());
		lock.lock();
		try {
			receivePostLocked(e);
		} catch (DbException | RuntimeException ex) {
		} finally {
			lock.unlock();
		}
	}

	private void receivePostLocked(GroupPostReceivedEvent e)
			throws DbException {
		MessageId id = e.getMessageId();
		byte[] groupId = e.getGroupId();
		GroupTrState s = getGroup(groupId);
		byte[] sig = e.getRecordSig();
		if (s == null || s.isDissolved() || sig == null || sig.length == 0) {
			removeStored(id);
			return;
		}
		byte[] deliveringPub = lookupSenderPubKey(e.getContactId());
		if (deliveringPub == null) return;
		long now = clock.currentTimeMillis();
		long arrival = e.getReceivedAt() > 0 ? e.getReceivedAt() : now;
		byte[] bodyHash = bodyHash(e.getCiphertext());
		byte[] signedInput = buildGroupPostSignedInput(groupId,
				(int) e.getEpoch(), e.getSenderPubKey(), e.getSenderName(),
				bodyHash, e.getTimestamp(), e.getAutoDeleteTimerMs());
		long ttl = effectiveTtl(e.getAutoDeleteTimerMs(),
				s.getDefaultAutoDeleteTimerMs());
		GroupTrPost p = new GroupTrPost(groupId, e.getSenderPubKey(),
				e.getSenderName(), e.getCiphertext(), e.getTimestamp(),
				e.getEpoch(), false, ttl, arrival);
		Verdict v = decide(s, p, sig, signedInput, deliveringPub, now);
		if (v == Verdict.REFUSED) {
			removeStored(id);
		} else if (v == Verdict.PENDING) {
			holdPending(id, e.getContactId(), p);
		} else if (v == Verdict.BUFFERED) {
			holdPending(id, e.getContactId(), p);
			bufferFuturePost(toHexString(groupId), new BufferedPost(p,
					postIdentity(signedInput), id));
		} else {
			acceptPost(id, p, postIdentity(signedInput));
		}
	}

	private Verdict decide(GroupTrState s, GroupTrPost p, byte[] sig,
			byte[] signedInput, byte[] deliveringPub, long now)
			throws DbException {
		if (p.isExpiredAt(now)) return Verdict.REFUSED;
		if (!groupPostDeliveryAccepted(s, p.getSenderPubKey(),
				deliveringPub)) {
			return Verdict.PENDING;
		}
		if (lookupPeerMlDsaPubKey(p.getSenderPubKey()) == null) {
			return Verdict.PENDING;
		}
		if (!verify(sig, "org.zerionproject/GROUP_POST", signedInput,
				p.getSenderPubKey())) {
			return Verdict.REFUSED;
		}
		long localEpoch = s.getEpoch();
		if (p.getEpoch() > localEpoch + EPOCH_BUFFER_TOLERANCE) {
			return Verdict.BUFFERED;
		}
		if (p.getEpoch() < localEpoch - 1L
				&& !signerWasMemberAt(s, p.getSenderPubKey(), p.getEpoch())) {
			return Verdict.PENDING;
		}
		return Verdict.ACCEPTED;
	}

	static boolean signerWasMemberAt(GroupTrState s, byte[] signerPubKey,
			long epoch) {
		if (Arrays.equals(signerPubKey, s.getCreatorPubKey())) return true;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), signerPubKey)) {
				return m.getJoinedAtEpoch() <= epoch;
			}
		}
		return false;
	}

	private boolean acceptPost(@Nullable MessageId id, GroupTrPost p,
			byte[] identity) throws DbException {
		if (!markGroupPostSeen(p.getGroupId(), identity)) {
			if (id != null) removeStored(id);
			return false;
		}
		if (id != null) markAccepted(id, p);
		deliverToCache(toHexString(p.getGroupId()), p);
		announceAccepted(p);
		return true;
	}

	private void announceAccepted(GroupTrPost p) {
		boolean local = isLocalKey(p.getSenderPubKey());
		if (!local) incrementUnread(p.getGroupId());
		eventBus.broadcast(new org.zerionproject.app.api.messaging.event
				.GroupTrPostAcceptedEvent(p.getGroupId(), local));
	}

	private void markAccepted(MessageId id, GroupTrPost p) throws DbException {
		BdfDictionary update = new BdfDictionary();
		update.put(MessagingManager.MSG_KEY_GROUP_POST_STATE,
				(long) MessagingManager.GROUP_POST_STATE_ACCEPTED);
		update.put(MessagingManager.MSG_KEY_GROUP_EFFECTIVE_TTL,
				p.getAutoDeleteTimerMs());
		if (!p.isLocal()) {
			update.put(MessagingManager.MSG_KEY_GROUP_RECEIVED_AT,
					p.getTimerStart());
		}
		long expiry = p.getExpiryTime();
		try {
			db.transaction(false, txn -> {
				clientHelper.mergeMessageMetadata(txn, id, update);
				if (expiry != Long.MAX_VALUE) {
					db.setCleanupDeadline(txn, id, expiry);
				}
			});
		} catch (org.zerionproject.core.api.db.NoSuchMessageException ex) {
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	private void holdPending(MessageId id, ContactId from, GroupTrPost p)
			throws DbException {
		byte[] groupId = p.getGroupId();
		BdfDictionary update = new BdfDictionary();
		update.put(MessagingManager.MSG_KEY_GROUP_POST_STATE,
				(long) MessagingManager.GROUP_POST_STATE_PENDING);
		update.put(MessagingManager.MSG_KEY_GROUP_EFFECTIVE_TTL,
				p.getAutoDeleteTimerMs());
		update.put(MessagingManager.MSG_KEY_GROUP_RECEIVED_AT,
				p.getTimerStart());
		long expiry = p.getExpiryTime();
		String heldKey = from.getInt() + ":" + toHexString(groupId);
		long[] held = pendingHeld.get(heldKey);
		boolean sweep = held == null;
		if (held != null) {
			held[0]++;
			held[1] += bodyLength(p);
			sweep = held[0] > MAX_PENDING_POSTS_PER_CONTACT
					|| held[1] > MAX_PENDING_BYTES_PER_CONTACT;
		}
		boolean doSweep = sweep;
		try {
			db.transaction(false, txn -> {
				clientHelper.mergeMessageMetadata(txn, id, update);
				if (expiry != Long.MAX_VALUE) {
					db.setCleanupDeadline(txn, id, expiry);
				}
				if (doSweep) {
					long[] left = sweepPending(txn, id, from, groupId);
					pendingHeld.put(heldKey, left);
				}
			});
		} catch (org.zerionproject.core.api.db.NoSuchMessageException ex) {
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	private long[] sweepPending(Transaction txn, MessageId keep,
			ContactId from, byte[] groupId)
			throws DbException, FormatException {
		Contact c = contactManager.getContact(txn, from);
		org.zerionproject.core.api.sync.GroupId cg =
				messagingManager.getContactGroup(c).getId();
		BdfDictionary query = new BdfDictionary();
		query.put("messageType", 32L);
		query.put("groupId", groupId);
		query.put(MessagingManager.MSG_KEY_GROUP_POST_STATE,
				(long) MessagingManager.GROUP_POST_STATE_PENDING);
		java.util.List<long[]> held = new ArrayList<>();
		java.util.List<MessageId> heldIds = new ArrayList<>();
		for (MessageId m : clientHelper.getMessageIds(txn, cg, query)) {
			BdfDictionary meta = readStoredMetadata(txn, m);
			if (meta == null) continue;
			held.add(new long[] {meta.getLong("timestamp", 0L),
					storedBodyLength(meta), heldIds.size()});
			heldIds.add(m);
		}
		held.sort((a, b) -> Long.compare(a[0], b[0]));
		long count = held.size();
		long bytes = 0;
		for (long[] h : held) bytes += h[1];
		for (long[] h : held) {
			if (count <= MAX_PENDING_POSTS_PER_CONTACT
					&& bytes <= MAX_PENDING_BYTES_PER_CONTACT) {
				break;
			}
			MessageId m = heldIds.get((int) h[2]);
			if (m.equals(keep)) continue;
			db.removeMessage(txn, m);
			count--;
			bytes -= h[1];
		}
		return new long[] {count, bytes};
	}

	private void removeStored(MessageId id) {
		try {
			db.transaction(false, txn -> {
				try {
					db.removeMessage(txn, id);
				} catch (org.zerionproject.core.api.db
						.NoSuchMessageException gone) {
				}
			});
		} catch (DbException ex) {
		}
	}

	static long effectiveTtl(long postTtl, long groupTtl) {
		if (groupTtl <= 0) return Math.max(0L, postTtl);
		if (postTtl <= 0) return groupTtl;
		return Math.min(postTtl, groupTtl);
	}

	private byte[] bodyHash(byte[] body) {
		return crypto.hash(MessagingManager.GROUP_POST_BODY_HASH_LABEL, body);
	}

	private byte[] postIdentity(byte[] signedInput) {
		return crypto.hash("org.zerionproject/GROUP_POST_SEEN", signedInput);
	}

	private boolean isLocalKey(byte[] pub) {
		try {
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			return Arrays.equals(la.getPublicKey().getEncoded(), pub);
		} catch (DbException ex) {
			return false;
		}
	}

	private static long storedBodyLength(BdfDictionary meta)
			throws FormatException {
		Long length = meta.getOptionalLong(
				MessagingManager.MSG_KEY_GROUP_BODY_LENGTH);
		if (length != null) return length;
		byte[] body = meta.getOptionalRaw("groupCiphertext");
		return body == null ? 0L : body.length;
	}

	private void deliverToCache(String key, GroupTrPost p) {
		java.util.ArrayDeque<GroupTrPost> q = postCache.computeIfAbsent(
				key, k -> new java.util.ArrayDeque<>());
		synchronized (q) {
			for (GroupTrPost existing : q) {
				if (isSamePost(existing, p)) return;
			}
			q.addLast(p);
			capCache(q);
		}
		capCacheTotal(key);
	}

	private static boolean isSamePost(GroupTrPost a, GroupTrPost b) {
		return a.getEpoch() == b.getEpoch()
				&& a.getTimestamp() == b.getTimestamp()
				&& Arrays.equals(a.getSenderPubKey(), b.getSenderPubKey())
				&& Arrays.equals(a.getBody(), b.getBody());
	}

	private void bufferFuturePost(String key, BufferedPost b) {
		java.util.TreeMap<Long, java.util.List<BufferedPost>> bucket =
				futureBuffer.computeIfAbsent(key,
						k -> new java.util.TreeMap<>());
		synchronized (bucket) {
			bucket.computeIfAbsent(b.post.getEpoch(),
					k -> new ArrayList<>()).add(b);
			capBuffer(bucket, b.post.getSenderPubKey());
		}
		capBufferTotal();
	}

	private static void capBuffer(
			java.util.TreeMap<Long, java.util.List<BufferedPost>> bucket,
			byte[] sender) {
		while (true) {
			int count = 0;
			long bytes = 0;
			int senderCount = 0;
			long senderBytes = 0;
			for (java.util.List<BufferedPost> list : bucket.values()) {
				for (BufferedPost b : list) {
					long size = bodyLength(b.post);
					count++;
					bytes += size;
					if (Arrays.equals(b.post.getSenderPubKey(), sender)) {
						senderCount++;
						senderBytes += size;
					}
				}
			}
			if (senderCount > 1
					&& (senderCount > MAX_BUFFERED_POSTS_PER_SENDER
					|| senderBytes > MAX_BUFFERED_BYTES_PER_SENDER)) {
				removeFurthest(bucket, sender);
			} else if (count > 1 && (count > MAX_BUFFERED_POSTS_PER_GROUP
					|| bytes > MAX_BUFFERED_BYTES_PER_GROUP)) {
				removeFurthest(bucket, null);
			} else {
				return;
			}
		}
	}

	private void capBufferTotal() {
		while (true) {
			long total = 0;
			String largest = null;
			long largestBytes = 0;
			for (java.util.Map.Entry<String, java.util.TreeMap<Long,
					java.util.List<BufferedPost>>> e
					: futureBuffer.entrySet()) {
				long b;
				synchronized (e.getValue()) {
					b = bufferedBytes(e.getValue());
				}
				total += b;
				if (largest == null || b > largestBytes) {
					largest = e.getKey();
					largestBytes = b;
				}
			}
			if (largest == null || total <= MAX_BUFFERED_BYTES_TOTAL) return;
			java.util.TreeMap<Long, java.util.List<BufferedPost>> bucket =
					futureBuffer.get(largest);
			if (bucket == null) continue;
			synchronized (bucket) {
				if (!removeFurthest(bucket, null)) return;
				if (bucket.isEmpty()) futureBuffer.remove(largest, bucket);
			}
		}
	}

	private static boolean removeFurthest(
			java.util.TreeMap<Long, java.util.List<BufferedPost>> bucket,
			@Nullable byte[] sender) {
		for (java.util.Map.Entry<Long, java.util.List<BufferedPost>> entry
				: bucket.descendingMap().entrySet()) {
			java.util.List<BufferedPost> list = entry.getValue();
			for (int i = 0; i < list.size(); i++) {
				if (sender != null && !Arrays.equals(
						list.get(i).post.getSenderPubKey(), sender)) {
					continue;
				}
				list.remove(i);
				if (list.isEmpty()) bucket.remove(entry.getKey());
				return true;
			}
		}
		return false;
	}

	private static long bufferedBytes(
			java.util.TreeMap<Long, java.util.List<BufferedPost>> bucket) {
		long bytes = 0;
		for (java.util.List<BufferedPost> list : bucket.values()) {
			for (BufferedPost b : list) bytes += bodyLength(b.post);
		}
		return bytes;
	}

	private void drainFutureBuffer(byte[] groupId, long newLocalEpoch) {
		String key = toHexString(groupId);
		java.util.TreeMap<Long, java.util.List<BufferedPost>> bucket =
				futureBuffer.get(key);
		if (bucket == null) return;
		java.util.List<BufferedPost> released = new ArrayList<>();
		synchronized (bucket) {
			java.util.Iterator<java.util.Map.Entry<Long,
					java.util.List<BufferedPost>>> it =
					bucket.entrySet().iterator();
			while (it.hasNext()) {
				java.util.Map.Entry<Long, java.util.List<BufferedPost>>
						entry = it.next();
				if (entry.getKey() <= newLocalEpoch
						+ EPOCH_BUFFER_TOLERANCE) {
					released.addAll(entry.getValue());
					it.remove();
				} else {
					break;
				}
			}
			if (bucket.isEmpty()) futureBuffer.remove(key);
		}
		for (BufferedPost b : released) {
			try {
				acceptPost(b.id, b.post, b.identity);
			} catch (DbException ex) {
			}
		}
	}

	private void cacheLocalPost(byte[] groupId, byte[] senderPub,
			String senderName, byte[] body, long timestamp, long epoch,
			long autoDeleteTimerMs) {
		String key = toHexString(groupId);
		java.util.ArrayDeque<GroupTrPost> q = postCache.computeIfAbsent(
				key, k -> new java.util.ArrayDeque<>());
		synchronized (q) {
			q.addLast(new GroupTrPost(groupId, senderPub, senderName,
					body, timestamp, epoch, true, autoDeleteTimerMs));
			capCache(q);
		}
		capCacheTotal(key);
	}

	private static void capCache(java.util.ArrayDeque<GroupTrPost> q) {
		if (q.size() <= MAX_CACHED_POSTS_PER_GROUP
				&& cachedBytes(q, false) <= MAX_CACHED_BYTES_PER_GROUP) {
			return;
		}
		int n = q.size();
		int[] senders = new int[n];
		long[] sizes = new long[n];
		java.util.Map<String, Integer> senderIds = new java.util.HashMap<>();
		int i = 0;
		for (GroupTrPost p : q) {
			senders[i] = senderId(senderIds, p.getSenderPubKey());
			sizes[i] = bodyLength(p);
			i++;
		}
		boolean[] keep = keepWithinGroupBounds(senders, sizes, n);
		java.util.List<GroupTrPost> kept = new ArrayList<>(n);
		i = 0;
		for (GroupTrPost p : q) {
			if (keep[i++]) kept.add(p);
		}
		q.clear();
		q.addAll(kept);
	}

	private static int senderId(java.util.Map<String, Integer> senderIds,
			byte[] senderPub) {
		String hex = toHexString(senderPub);
		Integer id = senderIds.get(hex);
		if (id == null) {
			id = senderIds.size();
			senderIds.put(hex, id);
		}
		return id;
	}

	private static boolean[] keepWithinGroupBounds(int[] senders,
			long[] sizes, int n) {
		boolean[] keep = new boolean[n];
		Arrays.fill(keep, true);
		int senderCount = 0;
		for (int i = 0; i < n; i++) {
			senderCount = Math.max(senderCount, senders[i] + 1);
		}
		long[] heldPosts = new long[senderCount];
		long[] heldBytes = new long[senderCount];
		int[] oldest = new int[senderCount];
		Arrays.fill(oldest, -1);
		int[] nextOfSender = new int[n];
		long count = n;
		long bytes = 0;
		for (int i = n - 1; i >= 0; i--) {
			int s = senders[i];
			heldPosts[s]++;
			heldBytes[s] += sizes[i];
			bytes += sizes[i];
			if (i == n - 1) continue;
			nextOfSender[i] = oldest[s];
			oldest[s] = i;
		}
		for (int measure = 0; measure < 2; measure++) {
			long[] held = measure == 0 ? heldPosts : heldBytes;
			java.util.TreeSet<Integer> most = new java.util.TreeSet<>(
					(x, y) -> held[x] != held[y]
							? Long.compare(held[y], held[x])
							: Integer.compare(oldest[x], oldest[y]));
			for (int s = 0; s < senderCount; s++) {
				if (oldest[s] >= 0) most.add(s);
			}
			while (count > 1 && (measure == 0
					? count > MAX_CACHED_POSTS_PER_GROUP
					: bytes > MAX_CACHED_BYTES_PER_GROUP)) {
				Integer s = most.pollFirst();
				if (s == null) break;
				int i = oldest[s];
				keep[i] = false;
				oldest[s] = nextOfSender[i];
				count--;
				bytes -= sizes[i];
				heldPosts[s]--;
				heldBytes[s] -= sizes[i];
				if (oldest[s] >= 0) most.add(s);
			}
		}
		return keep;
	}

	private void capCacheTotal(String keep) {
		long total = 0;
		for (java.util.ArrayDeque<GroupTrPost> q : postCache.values()) {
			synchronized (q) {
				total += cachedBytes(q, false);
			}
		}
		java.util.Set<String> passed = new java.util.HashSet<>();
		passed.add(keep);
		while (total > MAX_CACHED_BYTES_TOTAL) {
			String largest = null;
			long largestBytes = 0;
			for (java.util.Map.Entry<String, java.util.ArrayDeque<GroupTrPost>>
					e : postCache.entrySet()) {
				if (passed.contains(e.getKey())) continue;
				long b;
				synchronized (e.getValue()) {
					b = cachedBytes(e.getValue(), true);
				}
				if (largest == null || b > largestBytes) {
					largest = e.getKey();
					largestBytes = b;
				}
			}
			if (largest == null) return;
			passed.add(largest);
			java.util.concurrent.locks.ReentrantLock lock = lockFor(largest);
			if (!lock.tryLock()) continue;
			try {
				java.util.ArrayDeque<GroupTrPost> q = postCache.get(largest);
				if (q == null) continue;
				synchronized (q) {
					total -= cachedBytes(q, true);
					q.removeIf(p -> !p.isLocal());
				}
				historyLoaded.remove(largest);
			} finally {
				lock.unlock();
			}
		}
	}

	private static long cachedBytes(java.util.Collection<GroupTrPost> posts,
			boolean remoteOnly) {
		long bytes = 0;
		for (GroupTrPost p : posts) {
			if (remoteOnly && p.isLocal()) continue;
			bytes += bodyLength(p);
		}
		return bytes;
	}

	private static long bodyLength(@Nullable GroupTrPost p) {
		if (p == null) return 0;
		byte[] b = p.getBody();
		return b == null ? 0 : b.length;
	}

	private static final String SEEN_NAMESPACE_PREFIX = "grouptr-seen:";
	private static final String SEEN_KEY = "seen";
	private static final int MAX_SEEN_POSTS_PER_GROUP = 512;

	private boolean markGroupPostSeen(byte[] groupId, byte[] identity) {
		String ns = SEEN_NAMESPACE_PREFIX + toHexString(groupId);
		String id = toHexString(identity);
		try {
			return db.transactionWithResult(false, txn -> {
				Settings s = settingsManager.getSettings(txn, ns);
				java.util.LinkedHashSet<String> set =
						parseSeen(s.get(SEEN_KEY));
				if (!set.add(id)) return false;
				while (set.size() > MAX_SEEN_POSTS_PER_GROUP) {
					set.remove(set.iterator().next());
				}
				Settings upd = new Settings();
				upd.put(SEEN_KEY, joinSeen(set));
				settingsManager.mergeSettings(txn, upd, ns);
				return true;
			});
		} catch (DbException e) {
			return true;
		}
	}

	private static java.util.LinkedHashSet<String> parseSeen(
			@Nullable String csv) {
		java.util.LinkedHashSet<String> set =
				new java.util.LinkedHashSet<>();
		if (csv == null || csv.isEmpty()) return set;
		for (String p : csv.split(",")) {
			if (!p.isEmpty()) set.add(p);
		}
		return set;
	}

	private static String joinSeen(java.util.LinkedHashSet<String> set) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (String s : set) {
			if (!first) sb.append(',');
			sb.append(s);
			first = false;
		}
		return sb.toString();
	}

	private static final String UNREAD_NAMESPACE = "grouptr-unread";

	private void incrementUnread(byte[] groupId) {
		String hex = toHexString(groupId);
		try {
			db.transaction(false, txn -> {
				org.zerionproject.core.api.settings.Settings cur =
						settingsManager.getSettings(txn, UNREAD_NAMESPACE);
				int current = cur.getInt(hex, 0);
				org.zerionproject.core.api.settings.Settings upd =
						new org.zerionproject.core.api.settings.Settings();
				upd.putInt(hex, current + 1);
				settingsManager.mergeSettings(txn, upd, UNREAD_NAMESPACE);
			});
			eventBus.broadcast(new org.zerionproject.app.api.messaging.event
					.GroupTrLocalStateChangedEvent(groupId,
							org.zerionproject.app.api.messaging.event
									.GroupTrLocalStateChangedEvent.Kind.UPDATED));
		} catch (DbException ignored) {
		}
	}

	@Override
	public int getUnreadCount(byte[] groupId) {
		String hex = toHexString(groupId);
		try {
			org.zerionproject.core.api.settings.Settings s =
					settingsManager.getSettings(UNREAD_NAMESPACE);
			return s.getInt(hex, 0);
		} catch (DbException ignored) {
			return 0;
		}
	}

	@Override
	public void markGroupRead(byte[] groupId) {
		String hex = toHexString(groupId);
		try {
			boolean changed = db.transactionWithResult(false, txn -> {
				org.zerionproject.core.api.settings.Settings cur =
						settingsManager.getSettings(txn, UNREAD_NAMESPACE);
				if (cur.getInt(hex, 0) == 0) return false;
				org.zerionproject.core.api.settings.Settings upd =
						new org.zerionproject.core.api.settings.Settings();
				upd.putInt(hex, 0);
				settingsManager.mergeSettings(txn, upd, UNREAD_NAMESPACE);
				return true;
			});
			if (!changed) return;
			eventBus.broadcast(new org.zerionproject.app.api.messaging.event
					.GroupTrLocalStateChangedEvent(groupId,
							org.zerionproject.app.api.messaging.event
									.GroupTrLocalStateChangedEvent.Kind.UPDATED));
		} catch (DbException ignored) {
		}
	}

	@Override
	public java.util.List<GroupTrPost> getRecentPosts(byte[] groupId) {
		String key = toHexString(groupId);
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			java.util.ArrayDeque<GroupTrPost> q = postCache.get(key);
			if (!historyLoaded.contains(key) || q == null || q.isEmpty()) {
				try {
					loadHistoryIntoCache(groupId);
					historyLoaded.add(key);
				} catch (DbException ex) {
				}
				q = postCache.get(key);
			}
			if (q == null) return java.util.Collections.emptyList();
			long now = clock.currentTimeMillis();
			synchronized (q) {
				List<GroupTrPost> live = new ArrayList<>(q.size());
				for (GroupTrPost p : q) {
					if (!p.isExpiredAt(now)) live.add(p);
				}
				return live;
			}
		} finally {
			lock.unlock();
		}
	}

	static boolean groupPostDeliveryAccepted(@Nullable GroupTrState s,
			byte[] signerPubKey, byte[] deliveringPubKey) {
		return isMemberOrCreator(s, signerPubKey)
				&& isMemberOrCreator(s, deliveringPubKey);
	}

	private static boolean isMemberOrCreator(@Nullable GroupTrState s,
			byte[] pubKey) {
		if (s == null || s.isDissolved()) return false;
		if (Arrays.equals(pubKey, s.getCreatorPubKey())) return true;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pubKey)) return true;
		}
		return false;
	}

	private void loadHistoryIntoCache(byte[] groupId) throws DbException {
		String key = toHexString(groupId);
		java.util.ArrayDeque<GroupTrPost> q =
				postCache.computeIfAbsent(key,
						k -> new java.util.ArrayDeque<>());
		java.util.Set<GroupTrPost> cachedBefore =
				java.util.Collections.newSetFromMap(
						new java.util.IdentityHashMap<>());
		java.util.List<GroupTrPost> preexistingLocal =
				new java.util.ArrayList<>();
		synchronized (q) {
			for (GroupTrPost p : q) {
				cachedBefore.add(p);
				if (p.isLocal()) preexistingLocal.add(p);
			}
		}
		GroupTrState state = getGroup(groupId);
		long now = clock.currentTimeMillis();
		byte[][] localPub = new byte[1][];
		java.util.List<MessageId> ids = new java.util.ArrayList<>();
		java.util.List<byte[]> deliverers = new java.util.ArrayList<>();
		db.transaction(true, txn -> {
			try {
				localPub[0] = identityManager.getLocalAuthor(txn)
						.getPublicKey().getEncoded();
			} catch (DbException ex) {
				localPub[0] = new byte[0];
			}
			BdfDictionary query = new BdfDictionary();
			query.put("messageType", 32L);
			query.put("groupId", groupId);
			BdfDictionary ownQuery = new BdfDictionary();
			ownQuery.put("messageType", 32L);
			ownQuery.put("groupId", groupId);
			ownQuery.put("groupSenderPubKey", localPub[0]);
			for (Contact c : contactManager.getContacts(txn)) {
				org.zerionproject.core.api.sync.GroupId cg =
						messagingManager.getContactGroup(c).getId();
				byte[] deliverer = c.getAuthor().getPublicKey().getEncoded();
				BdfDictionary asked =
						isMemberOrCreator(state, deliverer) ? query : ownQuery;
				try {
					for (MessageId m
							: clientHelper.getMessageIds(txn, cg, asked)) {
						ids.add(m);
						deliverers.add(deliverer);
					}
				} catch (FormatException ex) {
				}
			}
		});
		java.util.List<StoredPost> stored = new java.util.ArrayList<>();
		java.util.Map<String, StoredPost> localCopies =
				new java.util.HashMap<>();
		java.util.Set<String> seenRemoteCopies = new java.util.HashSet<>();
		java.util.Map<String, Integer> senderIds = new java.util.HashMap<>();
		java.util.List<byte[]> senderKeys = new java.util.ArrayList<>();
		java.util.List<MessageId> undecided = new java.util.ArrayList<>();
		java.util.List<byte[]> undecidedDeliverers =
				new java.util.ArrayList<>();
		readStoredPosts(ids, (i, meta) -> {
			StoredRecord r = parseStoredPost(groupId, meta, localPub[0]);
			if (r == null) return;
			GroupTrPost p = r.post;
			if (now >= r.expiryTime()) return;
			boolean checked;
			if (p.isLocal() && (r.state == null || r.state
					== MessagingManager.GROUP_POST_STATE_ACCEPTED)) {
				String dedupKey = p.getTimestamp() + ":" + p.getEpoch() + ":"
						+ toHexString(r.bodyHash);
				StoredPost first = localCopies.get(dedupKey);
				if (first != null) {
					if (first.otherCopies == null) {
						first.otherCopies = new java.util.ArrayList<>(2);
					}
					first.otherCopies.add(ids.get(i));
					return;
				}
				checked = true;
			} else if (r.state != null && r.state
					!= MessagingManager.GROUP_POST_STATE_ACCEPTED) {
				undecided.add(ids.get(i));
				undecidedDeliverers.add(deliverers.get(i));
				return;
			} else if (!groupPostDeliveryAccepted(state, p.getSenderPubKey(),
					deliverers.get(i))
					|| !seenRemoteCopies.add(storedCopyIdentity(r))) {
				return;
			} else if (r.state != null) {
				checked = true;
			} else if (!hasHybridSignatureLength(r.sig)) {
				return;
			} else {
				checked = false;
			}
			int sender = senderId(senderIds, p.getSenderPubKey());
			if (sender == senderKeys.size()) {
				senderKeys.add(p.getSenderPubKey());
			}
			StoredPost sp = new StoredPost(ids.get(i), p.getTimestamp(),
					stored.size(), sender, r.bodyLength, checked);
			stored.add(sp);
			if (p.isLocal()) {
				localCopies.put(p.getTimestamp() + ":" + p.getEpoch() + ":"
						+ toHexString(r.bodyHash), sp);
			}
		});
		ids.clear();
		deliverers.clear();
		localCopies.clear();
		for (int u = 0; u < undecided.size(); u++) {
			StoredRecord r = decideStoredPost(groupId, undecided.get(u),
					undecidedDeliverers.get(u), state, now, localPub[0]);
			if (r == null || !seenRemoteCopies.add(storedCopyIdentity(r))) {
				continue;
			}
			int sender = senderId(senderIds, r.post.getSenderPubKey());
			if (sender == senderKeys.size()) {
				senderKeys.add(r.post.getSenderPubKey());
			}
			stored.add(new StoredPost(undecided.get(u),
					r.post.getTimestamp(), stored.size(), sender,
					r.bodyLength, true));
		}
		undecided.clear();
		undecidedDeliverers.clear();
		seenRemoteCopies.clear();
		dropPostsBeyondTheBound(stored, senderKeys, localPub[0]);
		boolean[] unverifiable = new boolean[senderKeys.size()];
		for (int s = 0; s < senderKeys.size(); s++) {
			byte[] sender = senderKeys.get(s);
			if (Arrays.equals(sender, localPub[0])) continue;
			try {
				unverifiable[s] = lookupPeerMlDsaPubKey(sender) == null;
			} catch (DbException ex) {
				unverifiable[s] = true;
			}
		}
		stored.removeIf(sp -> !sp.checked && unverifiable[sp.sender]);
		stored.sort((a, b) -> a.timestamp != b.timestamp
				? Long.compare(a.timestamp, b.timestamp)
				: Long.compare(a.order, b.order));
		java.util.List<GroupTrPost> merged = takeStoredPosts(groupId, stored,
				senderKeys.size(), localPub[0]);
		for (GroupTrPost lp : preexistingLocal) addIfAbsent(merged, lp);
		synchronized (q) {
			for (GroupTrPost p : q) {
				if (!cachedBefore.contains(p)) addIfAbsent(merged, p);
			}
			merged.sort((a, b) ->
					Long.compare(a.getTimestamp(), b.getTimestamp()));
			java.util.ArrayDeque<GroupTrPost> next =
					new java.util.ArrayDeque<>(merged);
			capCache(next);
			q.clear();
			q.addAll(next);
		}
		capCacheTotal(key);
	}

	private void dropPostsBeyondTheBound(java.util.List<StoredPost> stored,
			java.util.List<byte[]> senderKeys, byte[] localPub) {
		int[] accepted = new int[senderKeys.size()];
		java.util.List<MessageId> drop = new ArrayList<>();
		for (int i = stored.size() - 1; i >= 0; i--) {
			StoredPost sp = stored.get(i);
			if (!sp.checked) continue;
			if (Arrays.equals(senderKeys.get(sp.sender), localPub)) continue;
			if (++accepted[sp.sender] > MAX_CACHED_POSTS_PER_GROUP) {
				drop.add(sp.id);
				stored.remove(i);
			}
		}
		for (MessageId id : drop) removeStored(id);
	}

	@Nullable
	private StoredRecord decideStoredPost(byte[] groupId, MessageId id,
			byte[] deliverer, @Nullable GroupTrState state, long now,
			byte[] localPub) throws DbException {
		if (state == null) return null;
		BdfDictionary meta = db.transactionWithNullableResult(true,
				txn -> readStoredMetadata(txn, id));
		if (meta == null) return null;
		StoredRecord r;
		byte[] body;
		try {
			r = parseStoredPost(groupId, meta, localPub);
			if (r == null) return null;
			body = r.post.getBody();
			if (body == null) body = readStoredBody(id);
		} catch (FormatException ex) {
			removeStored(id);
			return null;
		}
		if (body == null || r.sig == null) {
			removeStored(id);
			return null;
		}
		GroupTrPost p = new GroupTrPost(groupId, r.post.getSenderPubKey(),
				r.post.getSenderName(), body, r.post.getTimestamp(),
				r.post.getEpoch(), false, effectiveTtl(
						r.post.getAutoDeleteTimerMs(),
						state.getDefaultAutoDeleteTimerMs()),
				r.post.getTimerStart());
		byte[] signedInput = storedPostSignedInput(r);
		Verdict v = decide(state, p, r.sig, signedInput, deliverer, now);
		if (v == Verdict.REFUSED) {
			removeStored(id);
			return null;
		}
		if (v != Verdict.ACCEPTED) return null;
		if (!markGroupPostSeen(groupId, postIdentity(signedInput))) {
			removeStored(id);
			return null;
		}
		markAccepted(id, p);
		announceAccepted(p);
		return new StoredRecord(p, r.sig, r.bodyHash, r.bodyLength,
				MessagingManager.GROUP_POST_STATE_ACCEPTED,
				p.getAutoDeleteTimerMs());
	}

	@Nullable
	private byte[] readStoredBody(MessageId id) throws DbException {
		try {
			return db.transactionWithNullableResult(true, txn -> {
				try {
					return clientHelper.getMessageAsList(txn, id).getRaw(5);
				} catch (org.zerionproject.core.api.db.NoSuchMessageException
						| FormatException ex) {
					return null;
				}
			});
		} catch (org.zerionproject.core.api.db.MessageDeletedException ex) {
			return null;
		}
	}

	private java.util.List<GroupTrPost> takeStoredPosts(byte[] groupId,
			java.util.List<StoredPost> stored, int senderCount,
			byte[] localPub) throws DbException {
		int n = stored.size();
		int[] olderOfSender = new int[n];
		int[] nextOfSender = new int[senderCount];
		int[] postsOfSender = new int[senderCount];
		Arrays.fill(nextOfSender, -1);
		for (int i = 0; i < n; i++) {
			int s = stored.get(i).sender;
			olderOfSender[i] = nextOfSender[s];
			nextOfSender[s] = i;
			postsOfSender[s]++;
		}
		int[][] candidates = new int[senderCount][];
		int[] candidateCount = new int[senderCount];
		for (int s = 0; s < senderCount; s++) {
			candidates[s] = new int[Math.min(postsOfSender[s],
					MAX_CACHED_POSTS_PER_GROUP)];
			while (candidateCount[s] < candidates[s].length) {
				candidates[s][candidateCount[s]++] = nextOfSender[s];
				nextOfSender[s] = olderOfSender[nextOfSender[s]];
			}
		}
		int capacity = MAX_CACHED_POSTS_PER_GROUP + senderCount;
		int[] at = new int[capacity];
		int[] senders = new int[capacity];
		long[] sizes = new long[capacity];
		GroupTrPost[] chosen = new GroupTrPost[capacity];
		int[] heldAt = new int[capacity];
		GroupTrPost[] held = new GroupTrPost[capacity];
		int heldCount = 0;
		int[] refusedAt = new int[capacity];
		int[] refusedSender = new int[capacity];
		int[] sendersWithCount = new int[MAX_CACHED_POSTS_PER_GROUP + 1];
		boolean complete = false;
		while (!complete) {
			int level = choiceLevel(candidateCount, sendersWithCount);
			int m = 0;
			for (int s = 0; s < senderCount; s++) {
				int take = Math.min(candidateCount[s], level);
				System.arraycopy(candidates[s], 0, at, m, take);
				m += take;
			}
			Arrays.sort(at, 0, m);
			for (int k = 0; k < m; k++) {
				StoredPost sp = stored.get(at[k]);
				senders[k] = sp.sender;
				sizes[k] = sp.size;
			}
			boolean[] keep = keepWithinGroupBounds(senders, sizes, m);
			int h = 0;
			for (int k = 0; k < m; k++) {
				while (h < heldCount && heldAt[h] < at[k]) h++;
				if (keep[k] && h < heldCount && heldAt[h] == at[k]) {
					chosen[k] = held[h];
				}
			}
			Arrays.fill(held, 0, heldCount, null);
			complete = true;
			int refusals = 0;
			for (int k = m - 1; k >= 0; k--) {
				if (!keep[k] || chosen[k] != null) continue;
				chosen[k] = readCheckedPost(groupId, stored.get(at[k]),
						localPub);
				if (chosen[k] == null) {
					refusedAt[refusals] = at[k];
					refusedSender[refusals++] = senders[k];
					complete = false;
				}
			}
			heldCount = 0;
			for (int k = 0; k < m; k++) {
				if (chosen[k] == null) continue;
				heldAt[heldCount] = at[k];
				held[heldCount++] = chosen[k];
				chosen[k] = null;
			}
			for (int r = 0; r < refusals; r++) {
				int s = refusedSender[r];
				int[] c = candidates[s];
				int j = 0;
				while (c[j] != refusedAt[r]) j++;
				System.arraycopy(c, j + 1, c, j, candidateCount[s] - j - 1);
				candidateCount[s]--;
				if (nextOfSender[s] >= 0) {
					c[candidateCount[s]++] = nextOfSender[s];
					nextOfSender[s] = olderOfSender[nextOfSender[s]];
				}
			}
		}
		java.util.List<GroupTrPost> out = new java.util.ArrayList<>(heldCount);
		for (int k = 0; k < heldCount; k++) out.add(held[k]);
		return out;
	}

	private static int choiceLevel(int[] candidateCount,
			int[] sendersWithCount) {
		Arrays.fill(sendersWithCount, 0);
		long total = 0;
		for (int c : candidateCount) {
			sendersWithCount[c]++;
			total += c;
		}
		if (total <= MAX_CACHED_POSTS_PER_GROUP) {
			return MAX_CACHED_POSTS_PER_GROUP;
		}
		int sendersBeyond = candidateCount.length - sendersWithCount[0];
		long reached = 0;
		for (int level = 1; level < MAX_CACHED_POSTS_PER_GROUP; level++) {
			reached += sendersBeyond;
			if (reached >= MAX_CACHED_POSTS_PER_GROUP) return level;
			sendersBeyond -= sendersWithCount[level];
		}
		return MAX_CACHED_POSTS_PER_GROUP;
	}

	private static boolean hasHybridSignatureLength(@Nullable byte[] sig) {
		return sig != null && sig.length == org.zerionproject.core.api.crypto
				.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
	}

	private static void addIfAbsent(java.util.List<GroupTrPost> posts,
			GroupTrPost p) {
		for (GroupTrPost o : posts) {
			if (isSamePost(o, p)) return;
		}
		posts.add(p);
	}

	@Nullable
	private GroupTrPost readCheckedPost(byte[] groupId, StoredPost sp,
			byte[] localPub) throws DbException {
		GroupTrPost p = readStoredPost(groupId, sp.id, sp.checked, localPub);
		if (p != null || sp.otherCopies == null) return p;
		for (MessageId copy : sp.otherCopies) {
			p = readStoredPost(groupId, copy, true, localPub);
			if (p != null) return p;
		}
		return null;
	}

	@Nullable
	private GroupTrPost readStoredPost(byte[] groupId, MessageId id,
			boolean checked, byte[] localPub) throws DbException {
		BdfDictionary meta = db.transactionWithNullableResult(true,
				txn -> readStoredMetadata(txn, id));
		if (meta == null) return null;
		StoredRecord r;
		try {
			r = parseStoredPost(groupId, meta, localPub);
		} catch (FormatException ex) {
			return null;
		}
		if (r == null) return null;
		GroupTrPost p = r.post;
		byte[] body = p.getBody();
		if (body == null) body = readStoredBody(id);
		if (body == null) return null;
		GroupTrPost shown = new GroupTrPost(groupId, p.getSenderPubKey(),
				p.getSenderName(), body, p.getTimestamp(), p.getEpoch(),
				p.isLocal(), r.effectiveTtl, p.getTimerStart());
		if (checked || p.isLocal()) return shown;
		if (r.sig == null || r.sig.length == 0
				|| !verify(r.sig, "org.zerionproject/GROUP_POST",
				storedPostSignedInput(r), p.getSenderPubKey())) {
			removeStored(id);
			return null;
		}
		return shown;
	}

	private byte[] storedPostSignedInput(StoredRecord r) {
		GroupTrPost p = r.post;
		return buildGroupPostSignedInput(p.getGroupId(), (int) p.getEpoch(),
				p.getSenderPubKey(), p.getSenderName(), r.bodyHash,
				p.getTimestamp(), p.getAutoDeleteTimerMs());
	}

	private String storedCopyIdentity(StoredRecord r) {
		byte[] sig = r.sig == null ? new byte[0] : r.sig;
		return toHexString(crypto.hash("org.zerionproject/GROUP_POST_SEEN",
				storedPostSignedInput(r), sig));
	}

	private void readStoredPosts(java.util.List<MessageId> ids,
			StoredPostVisitor visitor) throws DbException {
		for (int from = 0; from < ids.size(); from += STORED_POSTS_PER_READ) {
			int start = from;
			int end = Math.min(ids.size(), from + STORED_POSTS_PER_READ);
			db.transaction(true, txn -> {
				for (int i = start; i < end; i++) {
					BdfDictionary meta = readStoredMetadata(txn, ids.get(i));
					if (meta == null) continue;
					try {
						visitor.visit(i, meta);
					} catch (FormatException ex) {
					}
				}
			});
		}
	}

	@Nullable
	private BdfDictionary readStoredMetadata(Transaction txn, MessageId id)
			throws DbException {
		try {
			return clientHelper.getMessageMetadataAsDictionary(txn, id);
		} catch (org.zerionproject.core.api.db.NoSuchMessageException
				| FormatException ex) {
			return null;
		}
	}

	@Nullable
	private StoredRecord parseStoredPost(byte[] groupId,
			BdfDictionary meta, byte[] localPub) throws FormatException {
		Integer mt = meta.getOptionalInt("messageType");
		if (mt == null || mt != 32) return null;
		byte[] gid = meta.getOptionalRaw("groupId");
		if (gid == null || !Arrays.equals(gid, groupId)) return null;
		long epoch = meta.getLong("groupEpoch", 0L);
		byte[] senderPub = meta.getRaw("groupSenderPubKey");
		String senderName = meta.getOptionalString("groupSenderName");
		if (senderName == null) senderName = "";
		byte[] body = meta.getOptionalRaw("groupCiphertext");
		byte[] bodyHash = meta.getOptionalRaw(
				MessagingManager.MSG_KEY_GROUP_BODY_HASH);
		if (bodyHash == null) {
			if (body == null) throw new FormatException();
			bodyHash = bodyHash(body);
		}
		long bodyLength = storedBodyLength(meta);
		long ts = meta.getLong("timestamp", 0L);
		long ttl = meta.getLong("autoDeleteTimer", 0L);
		long effective = meta.getLong(
				MessagingManager.MSG_KEY_GROUP_EFFECTIVE_TTL, ttl);
		byte[] recordSig = meta.getOptionalRaw("groupRecordSig");
		Long state = meta.getOptionalLong(
				MessagingManager.MSG_KEY_GROUP_POST_STATE);
		boolean local = Arrays.equals(senderPub, localPub);
		long timerStart = local ? ts : meta.getLong(
				MessagingManager.MSG_KEY_GROUP_RECEIVED_AT, ts);
		return new StoredRecord(new GroupTrPost(groupId, senderPub,
				senderName, body, ts, epoch, local, ttl, timerStart),
				recordSig, bodyHash, bodyLength,
				state == null ? null : state.intValue(), effective);
	}

	@Override
	@Nullable
	public GroupTrState getGroup(byte[] groupId) throws DbException {
		try {
			Settings s = settingsManager.getSettings(nsOf(groupId));
			if (s.isEmpty()) return null;
			if (s.getBoolean(S_REMOVED, false)) return null;
			return deserialize(groupId, s);
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	@Override
	public Collection<GroupTrState> getGroups() throws DbException {
		try {
			Settings index = settingsManager.getSettings(SETTINGS_NS_INDEX);
			String ids = index.get(S_GROUP_IDS);
			if (ids == null || ids.isEmpty()) return Collections.emptyList();
			String[] parts = ids.split(",");
			List<GroupTrState> out = new ArrayList<>(parts.length);
			for (String hex : parts) {
				if (hex.isEmpty()) continue;
				byte[] gid;
				try {
					gid = fromHexString(hex);
				} catch (RuntimeException badHex) {
					continue;
				}
				Settings gs = settingsManager.getSettings(nsOf(gid));
				if (gs.isEmpty() || gs.getBoolean(S_REMOVED, false)) continue;
				try {
					out.add(deserialize(gid, gs));
				} catch (FormatException corrupt) {
				}
			}
			return out;
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	@Override
	public GroupTrState createGroup(String name) throws DbException {
		byte[] salt = new byte[GROUP_SALT_LENGTH];
		random.nextBytes(salt);
		long now = clock.currentTimeMillis();
		try {
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			byte[] creatorPub = la.getPublicKey().getEncoded();
			String creatorName = la.getName();
			byte[] groupId = deriveGroupId(creatorName, creatorPub, name, salt);
			List<GroupTrMember> members = new ArrayList<>(1);
			members.add(new GroupTrMember(creatorPub, creatorName, now, 0L,
					MemberRole.CREATOR));
			GroupTrState s = new GroupTrState(groupId, name, salt,
					creatorPub, creatorName, now, 0L, false, members);
			db.transaction(false, txn -> {
				try {
					persist(txn, s);
				} catch (FormatException e) {
					throw new DbException(e);
				}
				addToIndex(txn, groupId);
			});
			return s;
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	@Override
	public void inviteContactToGroup(byte[] grouptrGroupId, ContactId contactId,
			byte[] contactPubKey, String contactName) throws DbException {
		GroupTrState s = getGroup(grouptrGroupId);
		if (s == null) {
			throw new GroupTrAuthException(
					GroupTrAuthException.Reason.GROUP_NOT_FOUND);
		}
		if (s.isDissolved()) {
			throw new GroupTrAuthException(
					GroupTrAuthException.Reason.GROUP_DISSOLVED);
		}
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		byte[] creatorPub = la.getPublicKey().getEncoded();
		if (!Arrays.equals(creatorPub, s.getCreatorPubKey())) {
			throw new GroupTrAuthException(
					GroupTrAuthException.Reason.NOT_CREATOR);
		}
		if (Arrays.equals(creatorPub, contactPubKey)) {
			return;
		}
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), contactPubKey)) {
				return;
			}
		}
		long timestamp = clock.currentTimeMillis();
		byte[] signed = offerSignedInputBound(grouptrGroupId, creatorPub,
				contactPubKey, timestamp, s.getName(), s.getSalt(),
				s.getCreatorName());
		byte[] sig = signOrThrow(SIGNING_LABEL_GROUPTR_INVITE_OFFER,
				signed, la.getPrivateKey());
		BdfList body = BdfList.of(
				(long) MessageTypes.GROUPTR_INVITE_OFFER,
				grouptrGroupId, s.getName(), s.getSalt(), s.getCreatorName(),
				creatorPub, timestamp, sig);
		db.transaction(false, txn -> {
			Contact c = contactManager.getContact(txn, contactId);
			dispatchToContact(txn, c, timestamp, body);
			persistInviteSent(txn, grouptrGroupId, contactId, contactPubKey,
					contactName, timestamp);
		});
	}

	@Override
	public void acceptInvite(byte[] grouptrGroupId) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock =
				lockFor(grouptrGroupId);
		lock.lock();
		try {
			PendingInviteReceived pi = loadInviteReceived(grouptrGroupId);
			if (pi == null) return;
			if (!inviteOfferTimely(clock.currentTimeMillis(),
					pi.inviteTimestamp)) {
				removeInviteReceived(grouptrGroupId);
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.INVITE_EXPIRED);
			}
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			byte[] localPub = la.getPublicKey().getEncoded();
			GroupTrState existing = getGroup(grouptrGroupId);
			if (existing != null) {
				boolean amCurrentMember = false;
				for (GroupTrMember m : existing.getMembers()) {
					if (Arrays.equals(m.getPubKey(), localPub)) {
						amCurrentMember = true;
						break;
					}
				}
				if (amCurrentMember
						&& !inBootstrapState(existing, localPub)) {
					removeInviteReceived(grouptrGroupId);
					return;
				}
				removeFromDevice(grouptrGroupId);
			}
			long ts = clock.currentTimeMillis();
			byte[] signed = offerSignedInputBound(grouptrGroupId, localPub,
					pi.creatorPubKey, ts, pi.groupName, pi.salt,
					pi.creatorName);
			byte[] sig = signOrThrow(SIGNING_LABEL_GROUPTR_INVITE_ACCEPT,
					signed, la.getPrivateKey());
			BdfList body = BdfList.of(
					(long) MessageTypes.GROUPTR_INVITE_ACCEPT,
					grouptrGroupId, ts, sig);
			db.transaction(false, txn -> {
				Contact c = contactManager.getContact(txn, pi.contactId);
				dispatchToContact(txn, c, ts, body);
			});
			materializeLocalState(grouptrGroupId, pi.creatorPubKey,
					pi.creatorName, pi.groupName, pi.salt, pi.inviteTimestamp,
					la);
			removeInviteReceived(grouptrGroupId);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void declineInvite(byte[] grouptrGroupId) throws DbException {
		PendingInviteReceived pi = loadInviteReceived(grouptrGroupId);
		if (pi == null) return;
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		byte[] localPub = la.getPublicKey().getEncoded();
		long ts = clock.currentTimeMillis();
		byte[] signed = offerSignedInputBound(grouptrGroupId, localPub,
				pi.creatorPubKey, ts, pi.groupName, pi.salt, pi.creatorName);
		byte[] sig = signOrThrow(SIGNING_LABEL_GROUPTR_INVITE_DECLINE,
				signed, la.getPrivateKey());
		BdfList body = BdfList.of(
				(long) MessageTypes.GROUPTR_INVITE_DECLINE,
				grouptrGroupId, ts, sig);
		db.transaction(false, txn -> {
			Contact c = contactManager.getContact(txn, pi.contactId);
			dispatchToContact(txn, c, ts, body);
		});
		removeInviteReceived(grouptrGroupId);
	}

	private void materializeLocalState(byte[] grouptrGroupId,
			byte[] creatorPubKey, String creatorName, String groupName,
			byte[] salt, long timestamp, LocalAuthor la) throws DbException {
		byte[] localPub = la.getPublicKey().getEncoded();
		byte[] localMlDsa = identityManager.getLocalMlDsaSigPublicKey();
		byte[] creatorMlDsa = lookupPeerMlDsaPubKey(creatorPubKey);
		List<GroupTrMember> members = new ArrayList<>(2);
		members.add(new GroupTrMember(creatorPubKey, creatorName,
				timestamp, 0L, MemberRole.CREATOR, creatorMlDsa));
		members.add(new GroupTrMember(localPub, la.getName(), timestamp,
				0L, MemberRole.MEMBER, localMlDsa));
		GroupTrState s = new GroupTrState(grouptrGroupId, groupName, salt,
				creatorPubKey, creatorName, timestamp, 0L, false, members);
		db.transaction(false, txn -> {
			try {
				persist(txn, s);
			} catch (FormatException ex) {
				throw new DbException(ex);
			}
			addToIndex(txn, grouptrGroupId);
		});
		eventBus.broadcast(new org.zerionproject.app.api.messaging.event
				.GroupTrLocalStateChangedEvent(grouptrGroupId,
				org.zerionproject.app.api.messaging.event
						.GroupTrLocalStateChangedEvent.Kind.CREATED));
	}

	private static byte[] offerSignedInputBound(byte[] grouptrGid,
			byte[] creatorPub, byte[] contactPub, long ts, String groupName,
			byte[] salt, String creatorName) {
		byte[] gn = groupName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] cn = creatorName.getBytes(
				java.nio.charset.StandardCharsets.UTF_8);
		int len = 32 + 32 + 32 + 8 + 4 + gn.length + 4 + salt.length + 4
				+ cn.length;
		java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(len);
		buf.put(grouptrGid);
		buf.put(creatorPub);
		buf.put(contactPub);
		buf.putLong(ts);
		buf.putInt(gn.length);
		buf.put(gn);
		buf.putInt(salt.length);
		buf.put(salt);
		buf.putInt(cn.length);
		buf.put(cn);
		return buf.array();
	}

	private void persistInviteSent(Transaction txn, byte[] grouptrGroupId,
			ContactId contactId, byte[] contactPubKey, String contactName,
			long timestamp) throws DbException {
		try {
			BdfList list = BdfList.of(contactPubKey, contactName, timestamp);
			String hex = toHexString(clientHelper.toByteArray(list));
			Settings out = new Settings();
			out.put(toHexString(grouptrGroupId) + ":" + contactId.getInt(),
					hex);
			settingsManager.mergeSettings(txn, out,
					SETTINGS_NS_INVITES_SENT);
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	@javax.annotation.Nullable
	private PendingInviteSent loadInviteSent(byte[] grouptrGroupId,
			ContactId contactId) {
		try {
			Settings s = settingsManager.getSettings(SETTINGS_NS_INVITES_SENT);
			String hex = s.get(toHexString(grouptrGroupId) + ":"
					+ contactId.getInt());
			if (hex == null || hex.isEmpty()) return null;
			return parseInviteSent(hex);
		} catch (DbException | FormatException ex) {
			return null;
		}
	}

	@Nullable
	private PendingInviteSent parseInviteSent(String hex)
			throws FormatException {
		if (hex.isEmpty()) return null;
		BdfList list = clientHelper.toList(fromHexString(hex));
		if (list.size() < 2) return null;
		byte[] contactPubKey = list.getRaw(0);
		String contactName = list.getString(1);
		long sent = list.size() > 2 ? list.getLong(2) : 0L;
		return new PendingInviteSent(contactPubKey, contactName, sent);
	}

	private void stampLegacySentInvites(Transaction txn, long now)
			throws DbException {
		Settings s = settingsManager.getSettings(txn,
				SETTINGS_NS_INVITES_SENT);
		Settings out = new Settings();
		for (Map.Entry<String, String> e : s.entrySet()) {
			String value = e.getValue();
			if (value == null || value.isEmpty()) continue;
			try {
				BdfList list = clientHelper.toList(fromHexString(value));
				if (list.size() != 2) continue;
				out.put(e.getKey(), toHexString(clientHelper.toByteArray(
						BdfList.of(list.getRaw(0), list.getString(1), now))));
			} catch (FormatException ex) {
				out.put(e.getKey(), "");
			}
		}
		if (!out.isEmpty()) {
			settingsManager.mergeSettings(txn, out, SETTINGS_NS_INVITES_SENT);
		}
	}

	static boolean sentInviteOpen(long now, long sent) {
		return sent >= 0 && sent <= now + INVITE_OFFER_FUTURE_SKEW_MS
				&& sent >= now - INVITE_OFFER_MAX_AGE_MS
				- INVITE_ANSWER_GRACE_MS;
	}

	static boolean inviteResponseTimely(long now, long sent, long answered) {
		if (!sentInviteOpen(now, sent)) return false;
		if (answered < 0) return false;
		if (answered > now + INVITE_OFFER_FUTURE_SKEW_MS) return false;
		if (answered < sent - INVITE_OFFER_FUTURE_SKEW_MS) return false;
		return answered <= sent + INVITE_OFFER_MAX_AGE_MS
				+ INVITE_OFFER_FUTURE_SKEW_MS;
	}

	@Override
	public Collection<GroupTrSentInvite> getSentInvites(byte[] groupId)
			throws DbException {
		String prefix = toHexString(groupId) + ":";
		Settings s = settingsManager.getSettings(SETTINGS_NS_INVITES_SENT);
		long now = clock.currentTimeMillis();
		List<GroupTrSentInvite> out = new ArrayList<>();
		Settings stale = new Settings();
		for (Map.Entry<String, String> e : s.entrySet()) {
			if (!e.getKey().startsWith(prefix)) continue;
			String value = e.getValue();
			if (value == null || value.isEmpty()) continue;
			try {
				PendingInviteSent pis = parseInviteSent(value);
				int contact = Integer.parseInt(
						e.getKey().substring(prefix.length()));
				if (pis == null || !sentInviteOpen(now, pis.sent)) {
					stale.put(e.getKey(), "");
					continue;
				}
				out.add(new GroupTrSentInvite(new ContactId(contact),
						pis.contactName, pis.sent));
			} catch (FormatException | NumberFormatException ex) {
				stale.put(e.getKey(), "");
			}
		}
		if (!stale.isEmpty()) {
			settingsManager.mergeSettings(stale, SETTINGS_NS_INVITES_SENT);
		}
		return out;
	}

	@Override
	public void revokeInvite(byte[] groupId, ContactId contactId)
			throws DbException {
		GroupTrState s = requireWritable(groupId);
		requireLocalIsCreator(s);
		removeInviteSent(groupId, contactId);
	}

	private void removeInviteSent(byte[] grouptrGroupId, ContactId contactId)
			throws DbException {
		Settings out = new Settings();
		out.put(toHexString(grouptrGroupId) + ":" + contactId.getInt(), "");
		settingsManager.mergeSettings(out, SETTINGS_NS_INVITES_SENT);
	}

	static final int MAX_PENDING_INVITES = 64;

	private void persistInviteReceived(byte[] grouptrGroupId,
			String groupName, byte[] salt, String creatorName,
			byte[] creatorPubKey, ContactId contactId, long inviteTimestamp)
			throws DbException {
		if (getPendingInvites().size() >= MAX_PENDING_INVITES) return;
		try {
			BdfList list = BdfList.of(groupName, salt, creatorName,
					creatorPubKey, (long) contactId.getInt(), inviteTimestamp);
			String hex = toHexString(clientHelper.toByteArray(list));
			Settings out = new Settings();
			out.put(toHexString(grouptrGroupId), hex);
			settingsManager.mergeSettings(out, SETTINGS_NS_OFFERS_PENDING);
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	@javax.annotation.Nullable
	private PendingInviteReceived loadInviteReceived(byte[] grouptrGroupId) {
		try {
			Settings s = settingsManager.getSettings(
					SETTINGS_NS_OFFERS_PENDING);
			String hex = s.get(toHexString(grouptrGroupId));
			if (hex == null || hex.isEmpty()) return null;
			BdfList list = clientHelper.toList(fromHexString(hex));
			if (list.size() < 6) return null;
			String groupName = list.getString(0);
			byte[] salt = list.getRaw(1);
			String creatorName = list.getString(2);
			byte[] creatorPubKey = list.getRaw(3);
			int contactInt = list.getLong(4).intValue();
			long inviteTs = list.getLong(5);
			return new PendingInviteReceived(groupName, salt,
					creatorName, creatorPubKey, new ContactId(contactInt),
					inviteTs);
		} catch (DbException | FormatException ex) {
			return null;
		}
	}

	private void removeInviteReceived(byte[] grouptrGroupId)
			throws DbException {
		Settings out = new Settings();
		out.put(toHexString(grouptrGroupId), "");
		settingsManager.mergeSettings(out, SETTINGS_NS_OFFERS_PENDING);
	}

	@Override
	public Collection<GroupTrPendingInvite> getPendingInvites()
			throws DbException {
		Settings s = settingsManager.getSettings(SETTINGS_NS_OFFERS_PENDING);
		List<GroupTrPendingInvite> result = new ArrayList<>();
		Settings stale = new Settings();
		long now = clock.currentTimeMillis();
		for (Map.Entry<String, String> e : s.entrySet()) {
			String key = e.getKey();
			String value = e.getValue();
			if (value == null || value.isEmpty()) continue;
			try {
				BdfList list = clientHelper.toList(fromHexString(value));
				if (list.size() < 6) continue;
				String groupName = list.getString(0);
				String creatorName = list.getString(2);
				long inviteTs = list.getLong(5);
				if (!inviteOfferTimely(now, inviteTs)) {
					stale.put(key, "");
					continue;
				}
				result.add(new GroupTrPendingInvite(fromHexString(key),
						groupName, creatorName, inviteTs));
			} catch (FormatException ex) {
				stale.put(key, "");
			}
		}
		if (!stale.isEmpty()) {
			settingsManager.mergeSettings(stale, SETTINGS_NS_OFFERS_PENDING);
		}
		return result;
	}

	private static final class PendingInviteSent {
		final byte[] contactPubKey;
		final String contactName;
		final long sent;

		PendingInviteSent(byte[] contactPubKey, String contactName,
				long sent) {
			this.contactPubKey = contactPubKey;
			this.contactName = contactName;
			this.sent = sent;
		}
	}

	private static final class PendingInviteReceived {
		final String groupName;
		final byte[] salt;
		final String creatorName;
		final byte[] creatorPubKey;
		final ContactId contactId;
		final long inviteTimestamp;

		PendingInviteReceived(String groupName,
				byte[] salt, String creatorName, byte[] creatorPubKey,
				ContactId contactId, long inviteTimestamp) {
			this.groupName = groupName;
			this.salt = salt;
			this.creatorName = creatorName;
			this.creatorPubKey = creatorPubKey;
			this.contactId = contactId;
			this.inviteTimestamp = inviteTimestamp;
		}
	}

	static final long INVITE_OFFER_MAX_AGE_MS =
			7L * 24L * 60L * 60L * 1000L;
	static final long INVITE_OFFER_FUTURE_SKEW_MS = 24L * 60L * 60L * 1000L;
	static final long INVITE_ANSWER_GRACE_MS = 7L * 24L * 60L * 60L * 1000L;

	static boolean inviteOfferTimely(long now, long inviteTs) {
		if (inviteTs < 0) return false;
		if (inviteTs > now + INVITE_OFFER_FUTURE_SKEW_MS) return false;
		return inviteTs >= now - INVITE_OFFER_MAX_AGE_MS;
	}

	static boolean inBootstrapState(GroupTrState s, byte[] selfPubKey) {
		if (s.isDissolved() || s.getEpoch() != 0L) return false;
		if (s.getMembers().size() != 2) return false;
		boolean self = false;
		boolean creator = false;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), selfPubKey)) {
				if (m.getJoinedAtEpoch() != 0L) return false;
				self = true;
			} else if (Arrays.equals(m.getPubKey(), s.getCreatorPubKey())) {
				creator = true;
			}
		}
		return self && creator;
	}

	static boolean inviteOfferAdmissible(@Nullable byte[] senderPubKey,
			byte[] creatorPubKey, @Nullable GroupTrState existing,
			byte[] selfPubKey, boolean offerAlreadyPending,
			byte[] derivedGroupId, byte[] groupId) {
		if (senderPubKey == null
				|| !Arrays.equals(senderPubKey, creatorPubKey)) return false;
		if (existing != null && !existing.isDissolved()
				&& !inBootstrapState(existing, selfPubKey)) {
			for (GroupTrMember m : existing.getMembers()) {
				if (Arrays.equals(m.getPubKey(), selfPubKey)) return false;
			}
		}
		if (offerAlreadyPending) return false;
		return Arrays.equals(derivedGroupId, groupId);
	}

	static boolean inviteResponseAdmissible(@Nullable byte[] invitedPubKey,
			@Nullable byte[] responderPubKey, @Nullable GroupTrState group) {
		if (invitedPubKey == null || responderPubKey == null) return false;
		if (!Arrays.equals(responderPubKey, invitedPubKey)) return false;
		return group != null;
	}

	private void handleGrouptrInviteOffer(
			org.zerionproject.app.api.messaging.event
					.GroupTrInviteOfferReceivedEvent ev) {
		byte[] grouptrGid = ev.getGrouptrGroupId();
		byte[] creatorPub = ev.getCreatorPubKey();
		try {
			if (!inviteOfferTimely(clock.currentTimeMillis(),
					ev.getInviteTimestamp())) return;
			byte[] senderPub = lookupSenderPubKey(ev.getContactId());
			if (senderPub == null
					|| !Arrays.equals(senderPub, creatorPub)) return;
			GroupTrState existing = getGroup(grouptrGid);
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			byte[] localPub = la.getPublicKey().getEncoded();
			byte[] derived = deriveGroupId(ev.getCreatorName(), creatorPub,
					ev.getGroupName(), ev.getSalt());
			if (!inviteOfferAdmissible(senderPub, creatorPub, existing,
					localPub, loadInviteReceived(grouptrGid) != null,
					derived, grouptrGid)) return;
			byte[] signed = offerSignedInputBound(grouptrGid, creatorPub,
					localPub, ev.getInviteTimestamp(), ev.getGroupName(),
					ev.getSalt(), ev.getCreatorName());
			if (!verify(ev.getRecordSig(),
					SIGNING_LABEL_GROUPTR_INVITE_OFFER, signed, creatorPub)) {
				return;
			}
			persistInviteReceived(grouptrGid, ev.getGroupName(),
					ev.getSalt(), ev.getCreatorName(), creatorPub,
					ev.getContactId(), ev.getInviteTimestamp());
		} catch (DbException | FormatException ex) {
		}
	}

	private void handleGrouptrInviteResponse(
			org.zerionproject.app.api.messaging.event
					.GroupTrInviteResponseReceivedEvent ev) {
		byte[] grouptrGid = ev.getGrouptrGroupId();
		ContactId contactId = ev.getContactId();
		PendingInviteSent pis = loadInviteSent(grouptrGid, contactId);
		if (pis == null) return;
		byte[] responderPub = lookupSenderPubKey(contactId);
		GroupTrState s;
		try {
			s = getGroup(grouptrGid);
		} catch (DbException ex) {
			return;
		}
		if (!inviteResponseAdmissible(pis.contactPubKey, responderPub, s)) {
			return;
		}
		if (!inviteResponseTimely(clock.currentTimeMillis(), pis.sent,
				ev.getInviteTimestamp())) {
			if (!sentInviteOpen(clock.currentTimeMillis(), pis.sent)) {
				try {
					removeInviteSent(grouptrGid, contactId);
				} catch (DbException ex) {
				}
			}
			return;
		}
		byte[] signed = offerSignedInputBound(grouptrGid, responderPub,
				s.getCreatorPubKey(), ev.getInviteTimestamp(),
				s.getName(), s.getSalt(), s.getCreatorName());
		String label = ev.getKind() ==
				org.zerionproject.app.api.messaging.event
						.GroupTrInviteResponseReceivedEvent.Kind.ACCEPT
				? SIGNING_LABEL_GROUPTR_INVITE_ACCEPT
				: SIGNING_LABEL_GROUPTR_INVITE_DECLINE;
		if (!verify(ev.getRecordSig(), label, signed, responderPub)) return;
		try {
			removeInviteSent(grouptrGid, contactId);
		} catch (DbException ex) {
		}
		if (ev.getKind() == org.zerionproject.app.api.messaging.event
				.GroupTrInviteResponseReceivedEvent.Kind.ACCEPT) {
			try {
				addMember(grouptrGid, pis.contactPubKey, pis.contactName);
				try {
					sendMemberListSnapshot(grouptrGid);
				} catch (DbException ex) {
				}
			} catch (DbException ex) {
			}
		}
	}

	@Override
	public boolean isCreator(byte[] groupId, byte[] pubKey)
			throws DbException {
		GroupTrState s = getGroup(groupId);
		return s != null
				&& Arrays.equals(s.getCreatorPubKey(), pubKey);
	}

	@Override
	public boolean isMember(byte[] groupId, byte[] pubKey)
			throws DbException {
		GroupTrState s = getGroup(groupId);
		if (s == null) return false;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pubKey)) return true;
		}
		return false;
	}

	@Override
	public long getEpoch(byte[] groupId) throws DbException {
		GroupTrState s = getGroup(groupId);
		return s == null ? -1L : s.getEpoch();
	}

	@Override
	public boolean isDissolved(byte[] groupId) throws DbException {
		GroupTrState s = getGroup(groupId);
		return s != null && s.isDissolved();
	}

	private void handleMembershipEvent(GroupMembershipChangedEvent e) {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(e.getGroupId());
		lock.lock();
		try {
			handleMembershipEventLocked(e);
		} finally {
			lock.unlock();
		}
	}

	private void handleMembershipEventLocked(GroupMembershipChangedEvent e) {
		try {
			GroupTrState s = getGroup(e.getGroupId());
			if (s == null) return;
			byte[] senderPubKey = lookupSenderPubKey(e.getContactId());
			byte[] signer = membershipEventSigner(s, e.getKind(),
					senderPubKey, e.getTargetPubKey(), e.getEpoch(),
					e.getToEpoch());
			if (signer == null) return;
			if (!verify(e.getRecordSig(), SIGNING_LABEL_GROUP_MEMBERSHIP,
					e.getSignedInput(), signer)) return;
			switch (e.getKind()) {
				case MEMBER_ADDED:
					applyMemberAdded(s, e);
					break;
				case MEMBER_REMOVED:
					applyMemberRemoved(s, e);
					break;
				case MEMBER_LEFT:
					applyMemberLeft(s, e);
					break;
				case GROUP_DISSOLVED:
					s.setDissolved(true);
					s.setEpoch(e.getEpoch());
					persist(s);
					removeFromDevice(s.getGroupId());
					break;
				case ROLE_CHANGED:
					applyRoleChanged(s, e);
					break;
			}
		} catch (DbException | FormatException ex) {
		}
	}

	@Nullable
	static byte[] membershipEventSigner(GroupTrState s,
			GroupMembershipChangedEvent.ChangeKind kind,
			@Nullable byte[] senderPubKey, @Nullable byte[] targetPubKey,
			long epoch, long toEpoch) {
		if (s.isDissolved()) return null;
		byte[] creator = s.getCreatorPubKey();
		boolean senderIsCreator = senderPubKey != null
				&& Arrays.equals(senderPubKey, creator);
		switch (kind) {
			case MEMBER_ADDED:
				return senderIsCreator ? creator : null;
			case MEMBER_REMOVED:
				if (targetPubKey == null) return null;
				if (Arrays.equals(targetPubKey, creator)) return null;
				if (toEpoch < s.getEpoch()) return null;
				return senderIsCreator ? creator : null;
			case MEMBER_LEFT:
				if (targetPubKey == null) return null;
				if (Arrays.equals(targetPubKey, creator)) return null;
				return targetPubKey;
			case GROUP_DISSOLVED:
				return epoch > s.getEpoch() ? creator : null;
			case ROLE_CHANGED:
				return creator;
			default:
				return null;
		}
	}

	static boolean epochCommitAccepted(GroupTrState s, long fromEpoch,
			long toEpoch, @Nullable byte[] senderPubKey) {
		if (s.isDissolved()) return false;
		if (fromEpoch != s.getEpoch()) return false;
		if (toEpoch != fromEpoch + 1) return false;
		return senderPubKey != null
				&& Arrays.equals(senderPubKey, s.getCreatorPubKey());
	}

	static boolean snapshotShapeAccepted(GroupTrState s, long epoch,
			int memberCanonicalLength) {
		if (epoch <= s.getEpoch()) return false;
		return snapshotShapeValid(s, memberCanonicalLength);
	}

	private static boolean snapshotShapeValid(GroupTrState s,
			int memberCanonicalLength) {
		if (s.isDissolved()) return false;
		if (memberCanonicalLength % 37 != 0) return false;
		return memberCanonicalLength / 37
				<= org.zerionproject.app.grouptr.GroupTrConstants
				.MAX_GROUP_MEMBERS;
	}

	static boolean ownJoinSnapshotAccepted(GroupTrState s, long epoch,
			byte[] memberCanonical, byte[] localPubKey) {
		if (epoch != s.getEpoch()) return false;
		if (!snapshotShapeValid(s, memberCanonical.length)) return false;
		GroupTrMember self = null;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), localPubKey)) self = m;
		}
		if (self == null || self.getJoinedAtEpoch() == epoch) return false;
		for (int off = 0; off + 37 <= memberCanonical.length; off += 37) {
			byte[] pk = Arrays.copyOfRange(memberCanonical, off, off + 32);
			if (!Arrays.equals(pk, localPubKey)) continue;
			long joinedAtEpoch = 0L;
			for (int j = 0; j < 4; j++) {
				joinedAtEpoch = (joinedAtEpoch << 8)
						| (memberCanonical[off + 33 + j] & 0xFFL);
			}
			return joinedAtEpoch == epoch;
		}
		return false;
	}

	@javax.annotation.Nullable
	private byte[] lookupSenderPubKey(
			org.zerionproject.core.api.contact.ContactId contactId) {
		try {
			return db.transactionWithNullableResult(true, txn -> {
				try {
					org.zerionproject.core.api.contact.Contact c =
							contactManager.getContact(txn, contactId);
					return c.getAuthor().getPublicKey().getEncoded();
				} catch (DbException ex) {
					return null;
				}
			});
		} catch (DbException ex) {
			return null;
		}
	}

	private void handleEpochCommit(GroupEpochCommitEvent e) {
		try {
			GroupTrState s = getGroup(e.getGroupId());
			if (s == null) return;
			byte[] senderPubKey = lookupSenderPubKey(e.getContactId());
			if (!epochCommitAccepted(s, e.getFromEpoch(), e.getToEpoch(),
					senderPubKey)) return;
			if (!verify(e.getRecordSig(), SIGNING_LABEL_GROUP_EPOCH_COMMIT,
					e.getSignedInput(), senderPubKey)) return;
			s.setEpoch(e.getToEpoch());
			persist(s);
			drainFutureBuffer(s.getGroupId(), e.getToEpoch());
		} catch (DbException | FormatException ex) {

		}
	}

	private java.util.Map<String, String> loadContactNames() {
		java.util.Map<String, String> names = new java.util.HashMap<>();
		try {
			db.transaction(true, txn -> {
				for (Contact c : contactManager.getContacts(txn)) {
					names.put(toHexString(
							c.getAuthor().getPublicKey().getEncoded()),
							c.getAuthor().getName());
				}
				LocalAuthor la = identityManager.getLocalAuthor(txn);
				names.put(toHexString(la.getPublicKey().getEncoded()),
						la.getName());
			});
		} catch (DbException ignored) {
		}
		return names;
	}

	private void handleMemberListSnapshot(GroupMemberListSnapshotEvent e) {
		java.util.concurrent.locks.ReentrantLock lock =
				lockFor(e.getGroupId());
		lock.lock();
		try {
			GroupTrState s = getGroup(e.getGroupId());
			if (s == null) return;
			byte[] mc = e.getMemberCanonical();
			if (!snapshotShapeAccepted(s, e.getEpoch(), mc.length)) {
				LocalAuthor la = db.transactionWithResult(true,
						identityManager::getLocalAuthor);
				if (!ownJoinSnapshotAccepted(s, e.getEpoch(), mc,
						la.getPublicKey().getEncoded())) return;
			}
			if (!verify(e.getRecordSig(),
					"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT",
					e.getSignedInput(), s.getCreatorPubKey())) return;
			int n = mc.length / 37;
			List<GroupTrMember> reconciled = new ArrayList<>(n);
			List<GroupTrMember> prev = s.getMembers();
			java.util.Map<String, String> contactNames =
					loadContactNames();
			for (int i = 0; i < n; i++) {
				byte[] pk = new byte[32];
				System.arraycopy(mc, i * 37, pk, 0, 32);
				int roleInt = mc[i * 37 + 32] & 0xFF;
				long joinedAtEpoch = 0L;
				for (int j = 0; j < 4; j++) {
					joinedAtEpoch = (joinedAtEpoch << 8)
							| (mc[i * 37 + 33 + j] & 0xFFL);
				}
				MemberRole r = Arrays.equals(pk, s.getCreatorPubKey())
						? MemberRole.CREATOR
						: MemberRole.valueOf(roleInt);
				String name = "";
				long joinedAt = 0L;
				byte[] mlDsaPub = null;
				for (GroupTrMember pm : prev) {
					if (Arrays.equals(pm.getPubKey(), pk)) {
						name = pm.getName();
						joinedAt = pm.getJoinedAt();
						mlDsaPub = pm.getMlDsaPubKey();
						break;
					}
				}
				if (name.isEmpty()) {
					if (Arrays.equals(pk, s.getCreatorPubKey())) {
						name = s.getCreatorName();
					} else {
						String resolved = contactNames.get(toHexString(pk));
						if (resolved != null) name = resolved;
					}
				}
				if (mlDsaPub == null) {
					mlDsaPub = lookupPeerMlDsaPubKey(pk);
				}
				reconciled.add(new GroupTrMember(pk, name, joinedAt,
						joinedAtEpoch, r, mlDsaPub));
			}
			s.setMembers(reconciled);
			s.setEpoch(e.getEpoch());
			persist(s);
			drainFutureBuffer(s.getGroupId(), s.getEpoch());
			historyLoaded.remove(toHexString(s.getGroupId()));
		} catch (DbException | FormatException ex) {

		} finally {
			lock.unlock();
		}
	}

	private void applyMemberAdded(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] pk = e.getTargetPubKey();
		if (pk == null) return;
		if (e.getEpoch() <= s.getEpoch()) return;
		String mname = e.getTargetName();
		if (mname == null) mname = "";
		byte[] localPub = db.transactionWithResult(true,
				identityManager::getLocalAuthor).getPublicKey().getEncoded();
		List<GroupTrMember> next = new ArrayList<>(s.getMembers());
		for (int i = 0; i < next.size(); i++) {
			GroupTrMember m = next.get(i);
			if (!Arrays.equals(m.getPubKey(), pk)) continue;
			if (!Arrays.equals(pk, localPub)) {
				next.set(i, new GroupTrMember(pk, m.getName(),
						e.getTimestamp(), e.getEpoch(), m.getRole(),
						m.getMlDsaPubKey()));
				s.setMembers(next);
			}
			s.setEpoch(e.getEpoch());
			persist(s);
			drainFutureBuffer(s.getGroupId(), e.getEpoch());
			return;
		}
		next.add(new GroupTrMember(pk, mname, e.getTimestamp(),
				e.getEpoch()));
		s.setMembers(next);
		s.setEpoch(e.getEpoch());
		persist(s);
		drainFutureBuffer(s.getGroupId(), e.getEpoch());
		historyLoaded.remove(toHexString(s.getGroupId()));
	}

	private void applyMemberRemoved(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] pk = e.getTargetPubKey();
		if (pk == null) return;
		if (e.getToEpoch() < s.getEpoch()) return;
		boolean advances = e.getToEpoch() > s.getEpoch();
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		byte[] localPub = la.getPublicKey().getEncoded();
		if (Arrays.equals(pk, localPub)) {
			String groupName = s.getName();
			byte[] groupId = s.getGroupId();
			try {
				removeFromDevice(groupId);
			} catch (DbException ignored) {
			}
			eventBus.broadcast(new org.zerionproject.app.api.messaging.event
					.GroupTrSelfRemovedEvent(groupId, groupName,
					e.getContactId()));
			return;
		}
		List<GroupTrMember> next = new ArrayList<>(s.getMembers().size());
		boolean found = false;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pk)) {
				found = true;
			} else {
				next.add(m);
			}
		}
		if (!found && !advances) return;
		s.setMembers(next);
		if (advances) s.setEpoch(e.getToEpoch());
		persist(s);
		drainFutureBuffer(s.getGroupId(), s.getEpoch());
	}

	private void applyMemberLeft(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] pk = e.getTargetPubKey();
		if (pk == null) return;
		GroupTrMember leaver = null;
		List<GroupTrMember> next = new ArrayList<>(s.getMembers().size());
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pk)) {
				leaver = m;
			} else {
				next.add(m);
			}
		}
		if (leaver == null) return;
		if (e.getEpoch() <= leaver.getJoinedAtEpoch()) return;
		if (e.getTimestamp() + LEAVE_CLOCK_SKEW_MS <= leaver.getJoinedAt()) {
			return;
		}
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		boolean localIsCreator = Arrays.equals(
				la.getPublicKey().getEncoded(), s.getCreatorPubKey());
		if (!localIsCreator && !creatorConfirmsLeavings(s)) {
			if (e.getEpoch() <= s.getEpoch()) return;
			s.setMembers(next);
			s.setEpoch(Math.min(e.getEpoch(), s.getEpoch() + 1));
			persist(s);
			drainFutureBuffer(s.getGroupId(), s.getEpoch());
			return;
		}
		s.setMembers(next);
		persist(s);
		if (localIsCreator) confirmLeaving(s, pk);
	}

	private boolean creatorConfirmsLeavings(GroupTrState s)
			throws DbException {
		return db.transactionWithResult(true, txn -> {
			Contact creator = findContactByPubKey(txn, s.getCreatorPubKey());
			if (creator == null) return false;
			return messagingManager.getContactClientMinorVersion(txn,
					creator.getId())
					>= MessagingManager.GROUP_PROTOCOL_V2_MIN_VERSION;
		});
	}

	private void confirmLeaving(GroupTrState s, byte[] leaverPubKey)
			throws DbException {
		if (s.getEpoch() >= Integer.MAX_VALUE - 1) return;
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		PrivateKey signingKey = la.getPrivateKey();
		long timestamp = clock.currentTimeMillis();
		int fromEpoch = (int) s.getEpoch();
		int toEpoch = fromEpoch + 1;
		byte[] sigRemoved = signOrThrow(SIGNING_LABEL_GROUP_MEMBERSHIP,
				removedSignedInput(s.getGroupId(), leaverPubKey, fromEpoch,
						toEpoch, timestamp), signingKey);
		BdfList removedBody = BdfList.of(34L, s.getGroupId(), leaverPubKey,
				(long) fromEpoch, (long) toEpoch, timestamp, sigRemoved);
		byte[] pqSeed = new byte[32];
		random.nextBytes(pqSeed);
		byte[] sigCommit = signOrThrow(SIGNING_LABEL_GROUP_EPOCH_COMMIT,
				epochCommitSignedInput(s.getGroupId(), fromEpoch, toEpoch,
						pqSeed, timestamp), signingKey);
		BdfList commitBody = BdfList.of(37L, s.getGroupId(),
				(long) fromEpoch, (long) toEpoch, pqSeed, sigCommit);
		db.transaction(false, txn -> {
			fanOut(txn, s, removedBody, timestamp, null);
			fanOut(txn, s, commitBody, timestamp, null);
		});
		s.setEpoch(toEpoch);
		try {
			persist(s);
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
		drainFutureBuffer(s.getGroupId(), s.getEpoch());
		eventBus.broadcast(new org.zerionproject.app.api.messaging.event
				.GroupTrLocalStateChangedEvent(s.getGroupId(),
				org.zerionproject.app.api.messaging.event
						.GroupTrLocalStateChangedEvent.Kind.MEMBER_REMOVED));
	}

	private void handleGroupSettings(org.zerionproject.app.api.messaging
			.event.GroupSettingsChangedEvent e) {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(e.getGroupId());
		lock.lock();
		try {
			GroupTrState s = getGroup(e.getGroupId());
			if (s == null || s.isDissolved()) return;
			byte[] senderPubKey = lookupSenderPubKey(e.getContactId());
			if (senderPubKey == null
					|| !Arrays.equals(senderPubKey, s.getCreatorPubKey())) {
				return;
			}
			if (!timerAllowed(e.getAutoDeleteTimerMs())) return;
			Settings current = settingsManager.getSettings(
					nsOf(s.getGroupId()));
			if (e.getSettingsTimestamp()
					<= current.getLong(S_SETTINGS_TIMESTAMP, Long.MIN_VALUE)) {
				return;
			}
			byte[] signed = groupSettingsSignedInput(s.getGroupId(),
					e.getAutoDeleteTimerMs(), e.getSettingsTimestamp());
			if (!verify(e.getRecordSig(), SIGNING_LABEL_GROUP_SETTINGS,
					signed, s.getCreatorPubKey())) {
				return;
			}
			s.setDefaultAutoDeleteTimerMs(e.getAutoDeleteTimerMs());
			persistSettings(s, e.getSettingsTimestamp());
			eventBus.broadcast(new org.zerionproject.app.api.messaging.event
					.GroupTrLocalStateChangedEvent(s.getGroupId(),
					org.zerionproject.app.api.messaging.event
							.GroupTrLocalStateChangedEvent.Kind.UPDATED));
		} catch (DbException | FormatException ex) {
		} finally {
			lock.unlock();
		}
	}

	static boolean timerAllowed(long ms) {
		return ms == 0L || (ms >= MIN_TIMER_MS && ms <= MAX_TIMER_MS);
	}

	private void persistSettings(GroupTrState s, long settingsTimestamp)
			throws DbException, FormatException {
		Settings out = serializeState(s);
		out.putLong(S_SETTINGS_TIMESTAMP, settingsTimestamp);
		settingsManager.mergeSettings(out, nsOf(s.getGroupId()));
	}

	static byte[] groupSettingsSignedInput(byte[] groupId, long timer,
			long timestamp) {
		byte[] out = new byte[32 + 8 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 8; i++) {
			out[32 + i] = (byte) (timer >>> ((7 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[40 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[48] = (byte) 0x08;
		return out;
	}

	private BdfList settingsRecord(GroupTrState s, PrivateKey signingKey)
			throws DbException {
		Settings current = settingsManager.getSettings(nsOf(s.getGroupId()));
		long ts = current.getLong(S_SETTINGS_TIMESTAMP, 0L);
		long timer = s.getDefaultAutoDeleteTimerMs();
		byte[] sig = signOrThrow(SIGNING_LABEL_GROUP_SETTINGS,
				groupSettingsSignedInput(s.getGroupId(), timer, ts),
				signingKey);
		return BdfList.of((long) MessageTypes.GROUP_SETTINGS, s.getGroupId(),
				timer, ts, sig);
	}

	private int sendSettings(Transaction txn, GroupTrState s,
			Collection<byte[]> to, BdfList record, long timestamp)
			throws DbException {
		int skipped = 0;
		for (byte[] pub : to) {
			Contact c = findContactByPubKey(txn, pub);
			if (c == null) continue;
			if (messagingManager.getContactClientMinorVersion(txn, c.getId())
					< MessagingManager.GROUP_PROTOCOL_V2_MIN_VERSION) {
				skipped++;
				continue;
			}
			dispatchToContact(txn, c, timestamp, record);
		}
		return skipped;
	}

	@Override
	public int countMembersOnOlderVersion(byte[] groupId)
			throws DbException {
		GroupTrState s = getGroup(groupId);
		if (s == null) return 0;
		return db.transactionWithResult(true, txn -> {
			byte[] localPub = identityManager.getLocalAuthor(txn)
					.getPublicKey().getEncoded();
			int older = 0;
			for (GroupTrMember m : s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), localPub)) continue;
				Contact c = findContactByPubKey(txn, m.getPubKey());
				if (c == null) continue;
				if (messagingManager.getContactClientMinorVersion(txn,
						c.getId())
						< MessagingManager.GROUP_PROTOCOL_V2_MIN_VERSION) {
					older++;
				}
			}
			return older;
		});
	}

	@Override
	public List<GroupTrMember> getMembersOutOfReach(byte[] groupId)
			throws DbException {
		GroupTrState s = getGroup(groupId);
		if (s == null) return Collections.emptyList();
		return db.transactionWithResult(true, txn -> {
			byte[] localPub = identityManager.getLocalAuthor(txn)
					.getPublicKey().getEncoded();
			List<GroupTrMember> out = new ArrayList<>();
			for (GroupTrMember m : s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), localPub)) continue;
				if (findContactByPubKey(txn, m.getPubKey()) == null) {
					out.add(m);
				}
			}
			return out;
		});
	}

	private boolean verify(byte[] sig, String label, byte[] signed,
			byte[] pubKeyBytes) {
		if (signed.length == 0) return false;
		try {
			byte[] peerMlDsaPub = lookupPeerMlDsaPubKey(pubKeyBytes);
			if (peerMlDsaPub == null) return false;
			if (sig.length != org.zerionproject.core.api.crypto
					.PostQuantumConstants.HYBRID_SIGNATURE_BYTES) {
				return false;
			}
			org.zerionproject.core.api.crypto.HybridSignaturePublicKey
					hybridPub = new org.zerionproject.core.api.crypto
					.HybridSignaturePublicKey(pubKeyBytes, peerMlDsaPub);
			return crypto.verifyHybridSignature(sig, label, signed,
					hybridPub);
		} catch (GeneralSecurityException ex) {
			return false;
		} catch (DbException ex) {
			return false;
		}
	}

	private byte[] buildGroupPostSignedInput(byte[] groupId, int epoch,
			byte[] senderPubKey, String senderName, byte[] ctHash,
			long timestamp, long ttlMs) {
		byte[] nameHash = crypto.hash(
				"org.zerionproject/GROUP_POST_NAME",
				senderName.getBytes(
						java.nio.charset.StandardCharsets.UTF_8));
		byte[] out = new byte[32 + 4 + 32 + nameHash.length
				+ ctHash.length + 8 + 8];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		System.arraycopy(senderPubKey, 0, out, 36, 32);
		System.arraycopy(nameHash, 0, out, 68, nameHash.length);
		int off = 68 + nameHash.length;
		System.arraycopy(ctHash, 0, out, off, ctHash.length);
		off += ctHash.length;
		for (int i = 0; i < 8; i++) {
			out[off + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		off += 8;
		for (int i = 0; i < 8; i++) {
			out[off + i] = (byte) (ttlMs >>> ((7 - i) * 8));
		}
		return out;
	}

	@javax.annotation.Nullable
	private byte[] lookupPeerMlDsaPubKey(byte[] ed25519PubKey)
			throws DbException {
		return mlDsaKeys.lookup(ed25519PubKey);
	}

	@javax.annotation.Nullable
	private byte[] lookupLocalMlDsaPubKey(byte[] ed25519PubKey)
			throws DbException {
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		if (!Arrays.equals(la.getPublicKey().getEncoded(), ed25519PubKey)) {
			return null;
		}
		return identityManager.getLocalMlDsaSigPublicKey();
	}

	@javax.annotation.Nullable
	private byte[] lookupContactMlDsaPubKey(byte[] ed25519PubKey)
			throws DbException {
		return db.transactionWithNullableResult(true, txn -> {
			for (Contact c : contactManager.getContacts(txn)) {
				byte[] p = c.getAuthor().getPublicKey().getEncoded();
				if (Arrays.equals(p, ed25519PubKey)) {
					return c.getMlDsaSigPublicKey();
				}
			}
			return null;
		});
	}

	@javax.annotation.Nullable
	private byte[] lookupMemberMlDsaPubKey(byte[] ed25519PubKey)
			throws DbException {
		for (GroupTrState g : getGroups()) {
			for (GroupTrMember m : g.getMembers()) {
				if (Arrays.equals(m.getPubKey(), ed25519PubKey)) {
					byte[] ml = m.getMlDsaPubKey();
					if (ml != null) return ml;
				}
			}
		}
		return null;
	}

	private Settings serializeState(GroupTrState s) throws FormatException {
		Settings out = new Settings();
		out.put(S_NAME, s.getName());
		out.put(S_SALT, toHexString(s.getSalt()));
		out.put(S_CREATOR_PUBKEY, toHexString(s.getCreatorPubKey()));
		out.put(S_CREATOR_NAME, s.getCreatorName());
		out.putLong(S_CREATED, s.getCreated());
		out.putLong(S_EPOCH, s.getEpoch());
		out.putBoolean(S_DISSOLVED, s.isDissolved());
		out.putBoolean(S_REMOVED, false);
		out.putLong(S_DEFAULT_TTL, s.getDefaultAutoDeleteTimerMs());
		BdfList list = new BdfList();
		for (GroupTrMember m : s.getMembers()) {
			BdfList ml = new BdfList();
			ml.add(m.getPubKey());
			ml.add(m.getName());
			ml.add(m.getJoinedAt());
			ml.add(m.getJoinedAtEpoch());
			ml.add((long) m.getRole().getInt());
			byte[] mldsa = m.getMlDsaPubKey();
			if (mldsa != null) ml.add(mldsa);
			list.add(ml);
		}
		out.put(S_MEMBERS, toHexString(clientHelper.toByteArray(list)));
		return out;
	}

	private void persist(GroupTrState s)
			throws DbException, FormatException {
		settingsManager.mergeSettings(serializeState(s),
				nsOf(s.getGroupId()));
	}

	private void persist(Transaction txn, GroupTrState s)
			throws DbException, FormatException {
		settingsManager.mergeSettings(txn, serializeState(s),
				nsOf(s.getGroupId()));
	}

	private GroupTrState deserialize(byte[] groupId, Settings s)
			throws FormatException {
		String name = s.get(S_NAME);
		String saltHex = s.get(S_SALT);
		String creatorPubHex = s.get(S_CREATOR_PUBKEY);
		String creatorName = s.get(S_CREATOR_NAME);
		long created = s.getLong(S_CREATED, 0L);
		long epoch = s.getLong(S_EPOCH, 0L);
		boolean dissolved = s.getBoolean(S_DISSOLVED, false);
		long defaultTtl = s.getLong(S_DEFAULT_TTL, 0L);
		String membersHex = s.get(S_MEMBERS);
		if (name == null || saltHex == null || creatorPubHex == null
				|| creatorName == null || membersHex == null) {
			throw new FormatException();
		}
		byte[] salt = fromHexString(saltHex);
		byte[] creatorPub = fromHexString(creatorPubHex);
		BdfList memberList = clientHelper.toList(fromHexString(membersHex));
		List<GroupTrMember> members = new ArrayList<>(memberList.size());
		for (int i = 0; i < memberList.size(); i++) {
			BdfList ml = memberList.getList(i);
			MemberRole r = ml.size() >= 5
					? MemberRole.valueOf(ml.getLong(4).intValue())
					: MemberRole.MEMBER;
			byte[] pk = ml.getRaw(0);
			MemberRole effective = Arrays.equals(pk,
					fromHexString(creatorPubHex))
					? MemberRole.CREATOR : r;
			byte[] mldsa = ml.size() >= 6 ? ml.getOptionalRaw(5) : null;
			members.add(new GroupTrMember(pk, ml.getString(1),
					ml.getLong(2), ml.getLong(3), effective, mldsa));
		}
		return new GroupTrState(groupId, name, salt, creatorPub,
				creatorName, created, epoch, dissolved, members,
				defaultTtl);
	}

	private void addToIndex(Transaction txn, byte[] groupId)
			throws DbException {
		synchronized (indexLock) {
			Settings index =
					settingsManager.getSettings(txn, SETTINGS_NS_INDEX);
			String out = mergedIndexOrNull(index, groupId);
			if (out != null) {
				Settings s = new Settings();
				s.put(S_GROUP_IDS, out);
				settingsManager.mergeSettings(txn, s, SETTINGS_NS_INDEX);
			}
		}
	}

	@Nullable
	private String mergedIndexOrNull(Settings index, byte[] groupId) {
		String existing = index.get(S_GROUP_IDS);
		String hex = toHexString(groupId);
		java.util.TreeSet<String> ids = new java.util.TreeSet<>();
		if (existing != null && !existing.isEmpty()) {
			for (String p : existing.split(",")) {
				if (!p.isEmpty()) ids.add(p);
			}
		}
		if (!ids.add(hex)) return null;
		return String.join(",", ids);
	}

	private void removeFromIndex(Transaction txn, byte[] groupId)
			throws DbException {
		synchronized (indexLock) {
			Settings index =
					settingsManager.getSettings(txn, SETTINGS_NS_INDEX);
			String existing = index.get(S_GROUP_IDS);
			if (existing == null || existing.isEmpty()) return;
			String hex = toHexString(groupId);
			java.util.TreeSet<String> ids = new java.util.TreeSet<>();
			for (String p : existing.split(",")) {
				if (p.isEmpty() || p.equals(hex)) continue;
				ids.add(p);
			}
			Settings out = new Settings();
			out.put(S_GROUP_IDS, String.join(",", ids));
			settingsManager.mergeSettings(txn, out, SETTINGS_NS_INDEX);
		}
	}

	@Override
	public void removeFromDevice(byte[] groupId) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			String hex = toHexString(groupId);
			String stateNs = nsOf(groupId);
			String localNs = SETTINGS_NS_LOCAL_PREFIX + hex;
			Settings stateBlank = blankCopy(
					settingsManager.getSettings(stateNs));
			stateBlank.putBoolean(S_REMOVED, true);
			settingsManager.mergeSettings(stateBlank, stateNs);
			Settings localBlank = blankCopy(
					settingsManager.getSettings(localNs));
			if (!localBlank.isEmpty()) {
				settingsManager.mergeSettings(localBlank, localNs);
			}
			clearInvitePendingFor(groupId);
			db.transaction(false, txn -> removeFromIndex(txn, groupId));
			Settings unreadBlank = new Settings();
			unreadBlank.putInt(hex, 0);
			settingsManager.mergeSettings(unreadBlank, UNREAD_NAMESPACE);
			postCache.remove(hex);
			futureBuffer.remove(hex);
			historyLoaded.remove(hex);
			pendingHeld.keySet().removeIf(k -> k.endsWith(":" + hex));
			mlDsaKeys.invalidate();
			DbException sweepFailure = null;
			for (int attempt = 0; attempt < 3; attempt++) {
				try {
					db.transaction(false, txn -> {
						BdfDictionary query = new BdfDictionary();
						query.put("groupId", groupId);
						for (Contact c : contactManager.getContacts(txn)) {
							try {
								org.zerionproject.core.api.sync.GroupId
										contactGid = messagingManager
												.getContactGroup(c).getId();
								Collection<MessageId> msgs = clientHelper
										.getMessageIds(txn, contactGid, query);
								for (MessageId m : msgs) {
									try {
										db.removeMessage(txn, m);
									} catch (org.zerionproject.core.api.db
											.NoSuchMessageException ignored) {
									}
								}
							} catch (FormatException ignored) {
							}
						}
					});
					sweepFailure = null;
					break;
				} catch (DbException e) {
					sweepFailure = e;
				}
			}
			if (sweepFailure != null) {
				throw sweepFailure;
			}
		} finally {
			lock.unlock();
		}
		eventBus.broadcast(new org.zerionproject.app.api.messaging.event
				.GroupTrLocalStateChangedEvent(groupId,
				org.zerionproject.app.api.messaging.event
						.GroupTrLocalStateChangedEvent.Kind.REMOVED));
	}

	private static Settings blankCopy(Settings original) {
		Settings blank = new Settings();
		for (String key : new java.util.ArrayList<>(original.keySet())) {
			blank.put(key, "");
		}
		return blank;
	}

	private void clearInvitePendingFor(byte[] groupId) throws DbException {
		String hex = toHexString(groupId);
		Settings sent = settingsManager.getSettings(SETTINGS_NS_INVITES_SENT);
		Settings sentBlank = new Settings();
		for (String key : new java.util.ArrayList<>(sent.keySet())) {
			if (key.startsWith(hex + ":") || key.equals(hex)) {
				sentBlank.put(key, "");
			}
		}
		if (!sentBlank.isEmpty()) {
			settingsManager.mergeSettings(sentBlank,
					SETTINGS_NS_INVITES_SENT);
		}
		Settings offers = settingsManager.getSettings(
				SETTINGS_NS_OFFERS_PENDING);
		if (offers.get(hex) != null) {
			Settings offersBlank = new Settings();
			offersBlank.put(hex, "");
			settingsManager.mergeSettings(offersBlank,
					SETTINGS_NS_OFFERS_PENDING);
		}
	}

	private byte[] deriveGroupId(String creatorName, byte[] creatorPubKey,
			String name, byte[] salt) throws FormatException {
		BdfList authorList = BdfList.of(FORMAT_VERSION, creatorName,
				creatorPubKey);
		BdfList descriptorList = BdfList.of(authorList, name, salt);
		byte[] descriptor = clientHelper.toByteArray(descriptorList);
		byte[] formatVersionBytes = new byte[]{(byte) FORMAT_VERSION};
		byte[] clientIdBytes = CLIENT_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] majorVersionBytes = new byte[4];
		ByteUtils.writeUint32(MAJOR_VERSION, majorVersionBytes, 0);
		return crypto.hash(GROUP_ID_LABEL, formatVersionBytes,
				clientIdBytes, majorVersionBytes, descriptor);
	}

	private static String nsOf(byte[] groupId) {
		return SETTINGS_NS_PREFIX + toHexString(groupId);
	}

	@Override
	public int sendGroupPost(byte[] groupId, byte[] body,
			long autoDeleteTimerMs) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = getGroup(groupId);
			if (s == null) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.GROUP_NOT_FOUND);
			}
			if (s.isDissolved()) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.GROUP_DISSOLVED);
			}
			long effectiveTtl = effectiveTtl(autoDeleteTimerMs,
					s.getDefaultAutoDeleteTimerMs());
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			byte[] localPub = la.getPublicKey().getEncoded();
			boolean isMember = false;
			for (GroupTrMember m : s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), localPub)) {
					isMember = true;
					break;
				}
			}
			if (!isMember) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_A_MEMBER);
			}
			PrivateKey signingKey = la.getPrivateKey();
			long timestamp = clock.currentTimeMillis();
			int epoch = (int) s.getEpoch();
			String alias = getStealthName(groupId);
			String senderName = alias != null ? alias : la.getName();
			byte[] bodyHash = bodyHash(body);
			byte[] signed = buildGroupPostSignedInput(groupId, epoch,
					localPub, senderName, bodyHash, timestamp, effectiveTtl);
			byte[] sig = signOrThrow(
					"org.zerionproject/GROUP_POST",
					signed, signingKey);
			final String finalSenderName = senderName;
			boolean large = body.length
					> MessagingManager.LEGACY_MAX_GROUP_POST_BODY_LENGTH;
			List<Contact> recipients = new ArrayList<>();
			int skipped = db.transactionWithResult(true, txn -> {
				int passedOver = 0;
				for (GroupTrMember m : s.getMembers()) {
					if (Arrays.equals(m.getPubKey(), localPub)) continue;
					Contact c = findContactByPubKey(txn, m.getPubKey());
					if (c == null) continue;
					if (large && messagingManager.getContactClientMinorVersion(
							txn, c.getId())
							< MessagingManager.GROUP_PROTOCOL_V2_MIN_VERSION) {
						passedOver++;
						continue;
					}
					recipients.add(c);
				}
				return passedOver;
			});
			BdfList msgBody = effectiveTtl > 0
					? BdfList.of(32L, groupId, (long) epoch, localPub,
							finalSenderName, body, sig, effectiveTtl)
					: BdfList.of(32L, groupId, (long) epoch, localPub,
							finalSenderName, body, sig);
			for (Contact c : recipients) {
				BdfDictionary selfMeta = new BdfDictionary();
				selfMeta.put("messageType", 32L);
				selfMeta.put("groupId", groupId);
				selfMeta.put("groupEpoch", (long) epoch);
				selfMeta.put("groupSenderPubKey", localPub);
				selfMeta.put("groupSenderName", finalSenderName);
				selfMeta.put(MessagingManager.MSG_KEY_GROUP_BODY_HASH,
						bodyHash);
				selfMeta.put(MessagingManager.MSG_KEY_GROUP_BODY_LENGTH,
						(long) body.length);
				selfMeta.put(MessagingManager.MSG_KEY_GROUP_POST_STATE,
						(long) MessagingManager.GROUP_POST_STATE_ACCEPTED);
				selfMeta.put("timestamp", timestamp);
				selfMeta.put("autoDeleteTimer", effectiveTtl);
				selfMeta.put("groupRecordSig", sig);
				db.transaction(false, txn -> {
					MessageId copy = dispatchToContact(txn, c, timestamp,
							msgBody, selfMeta);
					if (effectiveTtl > 0) {
						db.setCleanupTimerDuration(txn, copy, effectiveTtl);
					}
				});
			}
			cacheLocalPost(groupId, localPub, finalSenderName, body,
					timestamp, epoch, effectiveTtl);
			eventBus.broadcast(new org.zerionproject.app.api.messaging.event
					.GroupTrPostAcceptedEvent(groupId, true));
			return skipped;
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void setMeshSink(GroupTrMeshSink sink) {
		this.meshSink = sink;
	}

	@Override
	public void setGroupAutoDeleteTimer(byte[] groupId, long ms)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireWritable(groupId);
			requireLocalIsCreator(s);
			if (!timerAllowed(ms)) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.INVALID_TIMER);
			}
			Settings current = settingsManager.getSettings(nsOf(groupId));
			long now = clock.currentTimeMillis();
			long last = current.getLong(S_SETTINGS_TIMESTAMP, 0L);
			long ts = Math.max(now, last + 1);
			s.setDefaultAutoDeleteTimerMs(ms);
			persistSettings(s, ts);
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			BdfList record = settingsRecord(s, la.getPrivateKey());
			List<byte[]> to = new ArrayList<>();
			for (GroupTrMember m : s.getMembers()) {
				if (!Arrays.equals(m.getPubKey(),
						la.getPublicKey().getEncoded())) {
					to.add(m.getPubKey());
				}
			}
			db.transaction(false, txn -> {
				sendSettings(txn, s, to, record, now);
			});
		} catch (FormatException ex) {
			throw new DbException(ex);
		} finally {
			lock.unlock();
		}
	}

	@Nullable
	@Override
	public String getStealthName(byte[] groupId) throws DbException {
		Settings s = settingsManager.getSettings(
				"grouptr.alias." + toHexString(groupId));
		String v = s.get(S_STEALTH_NAME);
		return v == null || v.isEmpty() ? null : v;
	}

	@Override
	public void setStealthName(byte[] groupId, @Nullable String alias)
			throws DbException {
		Settings out = new Settings();
		out.put(S_STEALTH_NAME, alias == null ? "" : alias);
		settingsManager.mergeSettings(out,
				"grouptr.alias." + toHexString(groupId));
	}

	@Override
	public boolean isLocalScreenshotBlocked(byte[] groupId) throws DbException {
		Settings s = settingsManager.getSettings(
				SETTINGS_NS_LOCAL_PREFIX + toHexString(groupId));
		return s.getBoolean(S_SCREENSHOT_BLOCKED, false);
	}

	@Override
	public void setLocalScreenshotBlocked(byte[] groupId, boolean blocked)
			throws DbException {
		Settings out = new Settings();
		out.putBoolean(S_SCREENSHOT_BLOCKED, blocked);
		settingsManager.mergeSettings(out,
				SETTINGS_NS_LOCAL_PREFIX + toHexString(groupId));
	}

	@Override
	public void addMember(byte[] groupId, byte[] addedPubKey,
			String addedName) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireWritable(groupId);
			requireLocalIsCreator(s);
			if (s.getEpoch() >= Integer.MAX_VALUE - 1) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.EPOCH_OVERFLOW);
			}
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			PrivateKey signingKey = la.getPrivateKey();
			long timestamp = clock.currentTimeMillis();
			int newEpoch = (int) s.getEpoch() + 1;
			byte[] signed = membershipSignedInput(groupId, addedPubKey,
					newEpoch, timestamp, (byte) 0x01);
			byte[] sig = signOrThrow(SIGNING_LABEL_GROUP_MEMBERSHIP, signed,
					signingKey);
			BdfList body = BdfList.of(33L, groupId, addedPubKey, addedName,
					(long) newEpoch, timestamp, sig);
			db.transaction(false, txn ->
					fanOutToAllPlusTarget(txn, s, body, timestamp, addedPubKey));
			applyLocalAdd(s, addedPubKey, addedName, timestamp, newEpoch);
			if (s.getDefaultAutoDeleteTimerMs() > 0) {
				BdfList record = settingsRecord(s, signingKey);
				db.transaction(false, txn -> {
					sendSettings(txn, s,
							Collections.singletonList(addedPubKey), record,
							timestamp);
				});
			}
		} finally {
			lock.unlock();
		}
	}

	private void fanOutToAllPlusTarget(Transaction txn, GroupTrState s,
			BdfList body, long timestamp, byte[] targetPubKey)
			throws DbException {
		LocalAuthor la = identityManager.getLocalAuthor(txn);
		byte[] localPub = la.getPublicKey().getEncoded();
		Contact target = findContactByPubKey(txn, targetPubKey);
		if (target != null) {
			dispatchToContact(txn, target, timestamp, body);
		}
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), localPub)) continue;
			if (Arrays.equals(m.getPubKey(), targetPubKey)) continue;
			Contact c = findContactByPubKey(txn, m.getPubKey());
			if (c == null) continue;
			dispatchToContact(txn, c, timestamp, body);
		}
	}

	@Override
	public void removeMember(byte[] groupId, byte[] removedPubKey)
			throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireWritable(groupId);
			requireLocalIsCreator(s);
			if (Arrays.equals(removedPubKey, s.getCreatorPubKey())) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.CANNOT_REMOVE_CREATOR);
			}
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			PrivateKey signingKey = la.getPrivateKey();
			long timestamp = clock.currentTimeMillis();
			int fromEpoch = (int) s.getEpoch();
			int toEpoch = fromEpoch + 1;
			byte[] signedRemoved = removedSignedInput(groupId, removedPubKey,
					fromEpoch, toEpoch, timestamp);
			byte[] sigRemoved = signOrThrow(SIGNING_LABEL_GROUP_MEMBERSHIP,
					signedRemoved, signingKey);
			BdfList removedBody = BdfList.of(34L, groupId, removedPubKey,
					(long) fromEpoch, (long) toEpoch, timestamp, sigRemoved);
			byte[] pqSeed = new byte[32];
			random.nextBytes(pqSeed);
			byte[] signedCommit = epochCommitSignedInput(groupId, fromEpoch,
					toEpoch, pqSeed, timestamp);
			byte[] sigCommit = signOrThrow(SIGNING_LABEL_GROUP_EPOCH_COMMIT,
					signedCommit, signingKey);
			BdfList commitBody = BdfList.of(37L, groupId, (long) fromEpoch,
					(long) toEpoch, pqSeed, sigCommit);
			db.transaction(false, txn -> {
				fanOut(txn, s, removedBody, timestamp, null);
				fanOut(txn, s, commitBody, timestamp, removedPubKey);
			});
			applyLocalRemove(s, removedPubKey, toEpoch);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void leaveGroup(byte[] groupId) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireWritable(groupId);
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			byte[] localPub = la.getPublicKey().getEncoded();
			if (Arrays.equals(localPub, s.getCreatorPubKey())) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.CANNOT_LEAVE_AS_CREATOR);
			}
			PrivateKey signingKey = la.getPrivateKey();
			long timestamp = clock.currentTimeMillis();
			int newEpoch = (int) s.getEpoch() + 1;
			byte[] signed = membershipSignedInput(groupId, localPub, newEpoch,
					timestamp, (byte) 0x03);
			byte[] sig = signOrThrow(SIGNING_LABEL_GROUP_MEMBERSHIP, signed,
					signingKey);
			BdfList body = BdfList.of(35L, groupId, localPub,
					(long) newEpoch, timestamp, sig);
			db.transaction(false, txn -> fanOut(txn, s, body, timestamp,
					localPub));
			applyLocalLeave(s, localPub, newEpoch);
			removeFromDevice(groupId);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void dissolveGroup(byte[] groupId) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireWritable(groupId);
			requireLocalIsCreator(s);
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			PrivateKey signingKey = la.getPrivateKey();
			long timestamp = clock.currentTimeMillis();
			int newEpoch = (int) s.getEpoch() + 1;
			byte[] signed = dissolveSignedInput(groupId, newEpoch, timestamp);
			byte[] sig = signOrThrow(SIGNING_LABEL_GROUP_MEMBERSHIP, signed,
					signingKey);
			BdfList body = BdfList.of(36L, groupId, (long) newEpoch,
					timestamp, sig);
			db.transaction(false, txn -> fanOut(txn, s, body, timestamp,
					null));
			s.setDissolved(true);
			s.setEpoch(newEpoch);
			try {
				persist(s);
			} catch (FormatException ex) {
				throw new DbException(ex);
			}
			removeFromDevice(groupId);
		} finally {
			lock.unlock();
		}
	}

	private GroupTrState requireWritable(byte[] groupId) throws DbException {
		GroupTrState s = getGroup(groupId);
		if (s == null) {
			throw new GroupTrAuthException(
					GroupTrAuthException.Reason.GROUP_NOT_FOUND);
		}
		if (s.isDissolved()) {
			throw new GroupTrAuthException(
					GroupTrAuthException.Reason.GROUP_DISSOLVED);
		}
		return s;
	}

	private void requireLocalIsCreator(GroupTrState s) throws DbException {
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		if (!Arrays.equals(la.getPublicKey().getEncoded(),
				s.getCreatorPubKey())) {
			throw new GroupTrAuthException(
					GroupTrAuthException.Reason.NOT_CREATOR);
		}
	}

	private byte[] signOrThrow(String label, byte[] signed,
			PrivateKey key) throws DbException {
		try {
			byte[] mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey();
			if (mlDsaPriv == null) {
				throw new DbException(new GeneralSecurityException(
						"Local ML-DSA private key missing, "
								+ "refusing classical-only signature"));
			}
			org.zerionproject.core.api.crypto.HybridSignaturePrivateKey
					hybridKey = new org.zerionproject.core.api.crypto
					.HybridSignaturePrivateKey(key.getEncoded(),
					mlDsaPriv);
			return crypto.hybridSign(label, signed, hybridKey);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
	}

	@Nullable
	private Contact findContactByPubKey(Transaction txn, byte[] pubKey)
			throws DbException {
		for (Contact c : contactManager.getContacts(txn)) {
			byte[] p = c.getAuthor().getPublicKey().getEncoded();
			if (Arrays.equals(p, pubKey)) return c;
		}
		return null;
	}

	private void fanOut(Transaction txn, GroupTrState s, BdfList body,
			long timestamp, @Nullable byte[] skipPubKey)
			throws DbException {
		LocalAuthor la = identityManager.getLocalAuthor(txn);
		byte[] localPub = la.getPublicKey().getEncoded();
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), localPub)) continue;
			if (skipPubKey != null
					&& Arrays.equals(m.getPubKey(), skipPubKey)) continue;
			Contact c = findContactByPubKey(txn, m.getPubKey());
			if (c == null) continue;
			dispatchToContact(txn, c, timestamp, body);
		}
	}

	private void dispatchToContact(Transaction txn, Contact c,
			long timestamp, BdfList body) throws DbException {
		MessageId id = dispatchToContact(txn, c, timestamp, body,
				new BdfDictionary());
		db.setCleanupTimerDuration(txn, id, RECORD_KEPT_AFTER_ACK_MS);
	}

	private static final long RECORD_KEPT_AFTER_ACK_MS = 5L * 60L * 1000L;

	private MessageId dispatchToContact(Transaction txn, Contact c,
			long timestamp, BdfList body, BdfDictionary meta)
			throws DbException {
		byte[] contactGroupId =
				messagingManager.getContactGroup(c).getId().getBytes();
		try {
			byte[] bodyBytes = clientHelper.toByteArray(body);
			Message m = clientHelper.createMessage(
					new org.zerionproject.core.api.sync.GroupId(
							contactGroupId),
					timestamp, bodyBytes);
			GroupTrMeshSink sink = meshSink;
			boolean offline = sink != null && sink.isOfflineMode();
			if (offline) {
				meta.put(org.zerionproject.app.api.messaging.MessagingManager
						.MSG_KEY_MESH_GROUP_PENDING, true);
			}
			clientHelper.addLocalMessage(txn, m, meta, !offline, false);
			if (offline) {
				sink.floodRecord(c.getId().getInt(), bodyBytes, timestamp);
			}
			return m.getId();
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	private void applyLocalAdd(GroupTrState s, byte[] pk, String name,
			long timestamp, int newEpoch) throws DbException {
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pk)) return;
		}
		List<GroupTrMember> next = new ArrayList<>(s.getMembers());
		next.add(new GroupTrMember(pk, name, timestamp, newEpoch));
		s.setMembers(next);
		s.setEpoch(newEpoch);
		try {
			persist(s);
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
		eventBus.broadcast(new org.zerionproject.app.api.messaging.event
				.GroupTrLocalStateChangedEvent(s.getGroupId(),
				org.zerionproject.app.api.messaging.event
						.GroupTrLocalStateChangedEvent.Kind.MEMBER_ADDED));
	}

	private void applyLocalRemove(GroupTrState s, byte[] pk, int newEpoch)
			throws DbException {
		List<GroupTrMember> next = new ArrayList<>(s.getMembers().size());
		for (GroupTrMember m : s.getMembers()) {
			if (!Arrays.equals(m.getPubKey(), pk)) next.add(m);
		}
		s.setMembers(next);
		s.setEpoch(newEpoch);
		try {
			persist(s);
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
		eventBus.broadcast(new org.zerionproject.app.api.messaging.event
				.GroupTrLocalStateChangedEvent(s.getGroupId(),
				org.zerionproject.app.api.messaging.event
						.GroupTrLocalStateChangedEvent.Kind.MEMBER_REMOVED));
	}

	private void applyRoleChanged(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] target = e.getTargetPubKey();
		if (target == null) return;
		if (e.getEpoch() <= s.getEpoch()) return;
		if (Arrays.equals(target, s.getCreatorPubKey())) return;
		MemberRole newRole = MemberRole.valueOf(e.getNewRole());
		if (newRole == MemberRole.CREATOR) return;
		List<GroupTrMember> next = new ArrayList<>(s.getMembers().size());
		boolean found = false;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), target)) {
				next.add(m.withRole(newRole));
				found = true;
			} else {
				next.add(m);
			}
		}
		if (!found) return;
		s.setMembers(next);
		s.setEpoch(e.getEpoch());
		persist(s);
		drainFutureBuffer(s.getGroupId(), e.getEpoch());
	}

	@Override
	public void promoteToAdmin(byte[] groupId, byte[] targetPubKey)
			throws DbException {
		changeRole(groupId, targetPubKey, MemberRole.ADMIN);
	}

	@Override
	public void demoteToMember(byte[] groupId, byte[] targetPubKey)
			throws DbException {
		changeRole(groupId, targetPubKey, MemberRole.MEMBER);
	}

	@Override
	public void sendMemberListSnapshot(byte[] groupId) throws DbException {
		GroupTrState s = requireWritable(groupId);
		requireLocalIsCreator(s);
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		PrivateKey signingKey = la.getPrivateKey();
		long timestamp = clock.currentTimeMillis();
		int epoch = (int) s.getEpoch();
		List<GroupTrMember> members = s.getMembers();
		BdfList memberList = new BdfList();
		byte[] memberCanonical = new byte[members.size() * 37];
		int off = 0;
		for (GroupTrMember m : members) {
			BdfList ml = new BdfList();
			ml.add(m.getPubKey());
			ml.add(m.getName());
			ml.add(m.getJoinedAt());
			ml.add(m.getJoinedAtEpoch());
			ml.add((long) m.getRole().getInt());
			memberList.add(ml);
			System.arraycopy(m.getPubKey(), 0, memberCanonical, off, 32);
			memberCanonical[off + 32] = (byte) m.getRole().getInt();
			int je = (int) m.getJoinedAtEpoch();
			for (int j = 0; j < 4; j++) {
				memberCanonical[off + 33 + j] =
						(byte) (je >>> ((3 - j) * 8));
			}
			off += 37;
		}
		byte[] signed = snapshotSignedInput(groupId, epoch, timestamp,
				memberCanonical);
		byte[] sig = signOrThrow(
				"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT",
				signed, signingKey);
		BdfList body = BdfList.of(41L, groupId, (long) epoch, timestamp,
				memberList, sig);
		db.transaction(false, txn -> fanOut(txn, s, body, timestamp, null));
	}

	private byte[] snapshotSignedInput(byte[] groupId, int epoch,
			long timestamp, byte[] memberCanonical) {
		byte[] mlHash = crypto.hash(
				"org.zerionproject/GROUP_MEMBER_LIST",
				memberCanonical);
		byte[] out = new byte[32 + 4 + 8 + mlHash.length + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[36 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		System.arraycopy(mlHash, 0, out, 44, mlHash.length);
		out[44 + mlHash.length] = (byte) 0x07;
		return out;
	}

	private void changeRole(byte[] groupId, byte[] targetPubKey,
			MemberRole newRole) throws DbException {
		java.util.concurrent.locks.ReentrantLock lock = lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireWritable(groupId);
			requireLocalIsCreator(s);
			if (Arrays.equals(targetPubKey, s.getCreatorPubKey())) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_CREATOR);
			}
			boolean isMember = false;
			for (GroupTrMember m : s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), targetPubKey)) {
					isMember = true;
					break;
				}
			}
			if (!isMember) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.CONTACT_NOT_FOUND);
			}
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			PrivateKey signingKey = la.getPrivateKey();
			long timestamp = clock.currentTimeMillis();
			int newEpoch = (int) s.getEpoch() + 1;
			byte[] signed = roleChangedSignedInput(groupId, targetPubKey,
					newRole.getInt(), newEpoch, timestamp);
			byte[] sig = signOrThrow(SIGNING_LABEL_GROUP_MEMBERSHIP, signed,
					signingKey);
			BdfList body = BdfList.of(38L, groupId, targetPubKey,
					(long) newRole.getInt(), (long) newEpoch, timestamp, sig);
			db.transaction(false, txn -> fanOut(txn, s, body, timestamp,
					null));
			applyLocalRoleChange(s, targetPubKey, newRole, newEpoch);
		} finally {
			lock.unlock();
		}
	}

	private void applyLocalRoleChange(GroupTrState s, byte[] target,
			MemberRole newRole, int newEpoch) throws DbException {
		List<GroupTrMember> next = new ArrayList<>(s.getMembers().size());
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), target)) {
				next.add(m.withRole(newRole));
			} else {
				next.add(m);
			}
		}
		s.setMembers(next);
		s.setEpoch(newEpoch);
		try {
			persist(s);
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	private byte[] roleChangedSignedInput(byte[] groupId,
			byte[] targetPubKey, int newRole, int epoch, long timestamp) {
		byte[] out = new byte[32 + 32 + 1 + 4 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		System.arraycopy(targetPubKey, 0, out, 32, 32);
		out[64] = (byte) newRole;
		for (int i = 0; i < 4; i++) {
			out[65 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[69 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[77] = (byte) 0x06;
		return out;
	}

	private void applyLocalLeave(GroupTrState s, byte[] pk, int newEpoch)
			throws DbException {
		s.setDissolved(true);
		s.setEpoch(newEpoch);
		List<GroupTrMember> next = new ArrayList<>(s.getMembers().size());
		for (GroupTrMember m : s.getMembers()) {
			if (!Arrays.equals(m.getPubKey(), pk)) next.add(m);
		}
		s.setMembers(next);
		try {
			persist(s);
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	private byte[] membershipSignedInput(byte[] groupId,
			byte[] targetPubKey, int epoch, long timestamp, byte action) {
		byte[] out = new byte[32 + 32 + 4 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		System.arraycopy(targetPubKey, 0, out, 32, 32);
		for (int i = 0; i < 4; i++) {
			out[64 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[68 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[76] = action;
		return out;
	}

	private byte[] removedSignedInput(byte[] groupId, byte[] removedPubKey,
			int fromEpoch, int toEpoch, long timestamp) {
		byte[] out = new byte[32 + 32 + 4 + 4 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		System.arraycopy(removedPubKey, 0, out, 32, 32);
		for (int i = 0; i < 4; i++) {
			out[64 + i] = (byte) (fromEpoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 4; i++) {
			out[68 + i] = (byte) (toEpoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[72 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[80] = (byte) 0x02;
		return out;
	}

	private byte[] dissolveSignedInput(byte[] groupId, int epoch,
			long timestamp) {
		byte[] out = new byte[32 + 4 + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 8; i++) {
			out[36 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[44] = (byte) 0x04;
		return out;
	}

	private byte[] epochCommitSignedInput(byte[] groupId, int fromEpoch,
			int toEpoch, byte[] pqSeed, long timestamp) {
		byte[] seedHash = crypto.hash(
				"org.zerionproject/GROUP_EPOCH_SEED", pqSeed);
		byte[] out = new byte[32 + 4 + 4 + seedHash.length + 8 + 1];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (fromEpoch >>> ((3 - i) * 8));
		}
		for (int i = 0; i < 4; i++) {
			out[36 + i] = (byte) (toEpoch >>> ((3 - i) * 8));
		}
		System.arraycopy(seedHash, 0, out, 40, seedHash.length);
		int off = 40 + seedHash.length;
		for (int i = 0; i < 8; i++) {
			out[off + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[off + 8] = (byte) 0x05;
		return out;
	}
}
