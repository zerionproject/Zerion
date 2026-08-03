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
			java.util.TreeMap<Long, java.util.List<GroupTrPost>>>
			futureBuffer = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Set<String> historyLoaded =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final int MAX_CACHED_POSTS_PER_GROUP = 200;
	private static final long MAX_CACHED_BYTES_PER_GROUP = 24L * 1024L * 1024L;
	private static final int EPOCH_BUFFER_TOLERANCE = 5;
	private static final int MAX_BUFFERED_POSTS_PER_GROUP = 500;

	private final java.util.Map<String, byte[]> mlDsaPubKeyCache =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final byte[] NEGATIVE_CACHE_SENTINEL = new byte[0];

	private final java.util.Map<String,
			java.util.concurrent.locks.ReentrantLock> groupLocks =
			new java.util.concurrent.ConcurrentHashMap<>();

	private final Object indexLock = new Object();

	private java.util.concurrent.locks.ReentrantLock lockFor(byte[] gid) {
		return groupLocks.computeIfAbsent(toHexString(gid),
				k -> new java.util.concurrent.locks.ReentrantLock());
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
		eventBus.addListener(this);
		purgeExpiredPosts();
		ioExecutor.execute(this::purgeExpiredFromDbSafely);
	}

	private void purgeExpiredFromDbSafely() {
		try {
			db.transaction(false, this::purgeExpiredFromDb);
		} catch (DbException | RuntimeException ex) {
		}
	}

	private void purgeExpiredFromDb(Transaction txn) throws DbException {
		long now = clock.currentTimeMillis();
		for (Contact c : contactManager.getContacts(txn)) {
			org.zerionproject.core.api.sync.GroupId cg =
					messagingManager.getContactGroup(c).getId();
			java.util.Map<org.zerionproject.core.api.sync.MessageId,
					org.zerionproject.core.api.data.BdfDictionary> msgs;
			try {
				msgs = clientHelper.getMessageMetadataAsDictionary(txn, cg);
			} catch (DbException | FormatException ex) {
				continue;
			}
			for (java.util.Map.Entry<
					org.zerionproject.core.api.sync.MessageId,
					org.zerionproject.core.api.data.BdfDictionary>
					e : msgs.entrySet()) {
				org.zerionproject.core.api.data.BdfDictionary meta =
						e.getValue();
				try {
					Integer mt = meta.getOptionalInt("messageType");
					if (mt == null || mt != 32) continue;
					long ttl = meta.getLong("autoDeleteTimer", 0L);
					if (ttl <= 0L) continue;
					long ts = meta.getLong("timestamp", 0L);
					if (ts + ttl <= now) {
						try {
							db.removeMessage(txn, e.getKey());
						} catch (org.zerionproject.core.api.db
								.NoSuchMessageException nsm) {
						}
					}
				} catch (FormatException fe) {
				}
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
					GroupTrPost p = it.next();
					long ttl = p.getAutoDeleteTimerMs();
					if (ttl > 0 && p.getTimestamp() + ttl <= now) {
						it.remove();
					}
				}
			}
		}
		for (java.util.Map.Entry<String,
				java.util.TreeMap<Long, java.util.List<GroupTrPost>>> e
				: futureBuffer.entrySet()) {
			java.util.TreeMap<Long, java.util.List<GroupTrPost>> tm =
					e.getValue();
			synchronized (tm) {
				java.util.Iterator<java.util.Map.Entry<Long,
						java.util.List<GroupTrPost>>> entries =
						tm.entrySet().iterator();
				while (entries.hasNext()) {
					java.util.List<GroupTrPost> list =
							entries.next().getValue();
					list.removeIf(p -> {
						long ttl = p.getAutoDeleteTimerMs();
						return ttl > 0 && p.getTimestamp() + ttl <= now;
					});
					if (list.isEmpty()) entries.remove();
				}
			}
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof GroupMembershipChangedEvent) {
			handleMembershipEvent((GroupMembershipChangedEvent) e);
		} else if (e instanceof GroupEpochCommitEvent) {
			handleEpochCommit((GroupEpochCommitEvent) e);
		} else if (e instanceof GroupPostReceivedEvent) {
			cachePost((GroupPostReceivedEvent) e);
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
		} else if (e instanceof org.zerionproject.core.api.contact.event
				.ContactRemovedEvent
				|| e instanceof org.zerionproject.core.api.contact.event
				.ContactAddedEvent) {
			mlDsaPubKeyCache.clear();
		}
	}

	private void cachePost(GroupPostReceivedEvent e) {
		byte[] sig = e.getRecordSig();
		if (sig == null || sig.length == 0) return;
		byte[] signedInput = buildGroupPostSignedInput(
				e.getGroupId(), (int) e.getEpoch(),
				e.getSenderPubKey(), e.getSenderName(),
				e.getCiphertext(), e.getTimestamp(),
				e.getAutoDeleteTimerMs());
		if (!verify(sig, "org.zerionproject/GROUP_POST",
				signedInput, e.getSenderPubKey())) {
			return;
		}
		String key = toHexString(e.getGroupId());
		long postEpoch = e.getEpoch();
		long localEpoch;
		GroupTrState s;
		try {
			s = getGroup(e.getGroupId());
			localEpoch = s == null ? 0L : s.getEpoch();
		} catch (DbException ex) {
			return;
		}
		if (s == null || s.isDissolved()) return;
		boolean senderIsMember = false;
		byte[] senderPub = e.getSenderPubKey();
		if (Arrays.equals(senderPub, s.getCreatorPubKey())) {
			senderIsMember = true;
		} else {
			for (GroupTrMember m : s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), senderPub)) {
					senderIsMember = true;
					break;
				}
			}
		}
		if (!senderIsMember) return;
		GroupTrPost p = new GroupTrPost(e.getGroupId(),
				senderPub, e.getSenderName(),
				e.getCiphertext(), e.getTimestamp(), postEpoch, false,
				e.getAutoDeleteTimerMs());
		if (postEpoch < localEpoch - 1L) {
			return;
		}
		if (postEpoch > localEpoch + EPOCH_BUFFER_TOLERANCE) {
			bufferFuturePost(key, p);
			return;
		}
		deliverToCache(key, p);
		try {
			byte[] localPub =
					identityManager.getLocalAuthor().getId().getBytes();
			if (!java.util.Arrays.equals(senderPub, localPub)) {
				incrementUnread(e.getGroupId());
			}
		} catch (DbException ignored) {
		}
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
	}

	private static boolean isSamePost(GroupTrPost a, GroupTrPost b) {
		return a.getEpoch() == b.getEpoch()
				&& a.getTimestamp() == b.getTimestamp()
				&& Arrays.equals(a.getSenderPubKey(), b.getSenderPubKey())
				&& Arrays.equals(a.getBody(), b.getBody());
	}

	private void bufferFuturePost(String key, GroupTrPost p) {
		java.util.TreeMap<Long, java.util.List<GroupTrPost>> bucket =
				futureBuffer.computeIfAbsent(key,
						k -> new java.util.TreeMap<>());
		synchronized (bucket) {
			int total = 0;
			for (java.util.List<GroupTrPost> v : bucket.values()) {
				total += v.size();
			}
			if (total >= MAX_BUFFERED_POSTS_PER_GROUP) {
				java.util.Map.Entry<Long, java.util.List<GroupTrPost>>
						first = bucket.firstEntry();
				if (first != null) {
					java.util.List<GroupTrPost> oldest = first.getValue();
					if (!oldest.isEmpty()) oldest.remove(0);
					if (oldest.isEmpty()) bucket.remove(first.getKey());
				}
			}
			bucket.computeIfAbsent(p.getEpoch(),
					k -> new ArrayList<>()).add(p);
		}
	}

	private void drainFutureBuffer(byte[] groupId, long newLocalEpoch) {
		String key = toHexString(groupId);
		java.util.TreeMap<Long, java.util.List<GroupTrPost>> bucket =
				futureBuffer.get(key);
		if (bucket == null) return;
		java.util.List<GroupTrPost> released = new ArrayList<>();
		synchronized (bucket) {
			java.util.Iterator<java.util.Map.Entry<Long,
					java.util.List<GroupTrPost>>> it =
					bucket.entrySet().iterator();
			while (it.hasNext()) {
				java.util.Map.Entry<Long, java.util.List<GroupTrPost>>
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
		for (GroupTrPost p : released) deliverToCache(key, p);
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
	}

	private static void capCache(java.util.ArrayDeque<GroupTrPost> q) {
		long bytes = 0;
		for (GroupTrPost p : q) {
			byte[] b = p.getBody();
			if (b != null) bytes += b.length;
		}
		while (q.size() > 1 && (q.size() > MAX_CACHED_POSTS_PER_GROUP
				|| bytes > MAX_CACHED_BYTES_PER_GROUP)) {
			GroupTrPost removed = q.pollFirst();
			if (removed != null && removed.getBody() != null) {
				bytes -= removed.getBody().length;
			}
		}
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
			synchronized (q) {
				return new ArrayList<>(q);
			}
		} finally {
			lock.unlock();
		}
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
		java.util.List<GroupTrPost> preexistingLocal =
				new java.util.ArrayList<>();
		synchronized (q) {
			for (GroupTrPost p : q) {
				if (p.isLocal()) preexistingLocal.add(p);
			}
		}
		java.util.List<GroupTrPost> collected = new java.util.ArrayList<>();
		java.util.List<byte[]> collectedSigs = new java.util.ArrayList<>();
		java.util.HashSet<String> seenLocalIds = new java.util.HashSet<>();
		db.transaction(true, txn -> {
			byte[] localPub;
			try {
				localPub = identityManager.getLocalAuthor(txn)
						.getPublicKey().getEncoded();
			} catch (DbException ex) {
				localPub = new byte[0];
			}
			for (Contact c : contactManager.getContacts(txn)) {
				org.zerionproject.core.api.sync.GroupId cg =
						messagingManager.getContactGroup(c).getId();
				try {
					java.util.Map<org.zerionproject.core.api.sync.MessageId,
							org.zerionproject.core.api.data.BdfDictionary>
							msgs = clientHelper
									.getMessageMetadataAsDictionary(txn, cg);
					for (java.util.Map.Entry<
							org.zerionproject.core.api.sync.MessageId,
							org.zerionproject.core.api.data.BdfDictionary>
							e : msgs.entrySet()) {
						org.zerionproject.core.api.data.BdfDictionary
								meta = e.getValue();
						Integer mt = meta.getOptionalInt(
								"messageType");
						if (mt == null || mt != 32) continue;
						byte[] gid = meta.getOptionalRaw("groupId");
						if (gid == null || !Arrays.equals(gid, groupId))
							continue;
						long epoch = meta.getLong("groupEpoch", 0L);
						byte[] senderPub = meta.getRaw(
								"groupSenderPubKey");
						String senderName = meta.getOptionalString(
								"groupSenderName");
						if (senderName == null) senderName = "";
						byte[] body = meta.getRaw(
								"groupCiphertext");
						long ts = meta.getLong("timestamp",
								0L);
						long ttl = meta.getLong("autoDeleteTimer",
								0L);
						byte[] recordSig = meta.getOptionalRaw(
								"groupRecordSig");
						boolean local = Arrays.equals(senderPub,
								localPub);
						if (local) {
							String dedupKey = ts + ":" + epoch + ":"
									+ (body == null ? 0 : body.length);
							if (!seenLocalIds.add(dedupKey)) continue;
						}
						collected.add(new GroupTrPost(groupId, senderPub,
								senderName, body, ts, epoch, local, ttl));
						collectedSigs.add(recordSig);
					}
				} catch (FormatException ex) {

				}
			}
		});
		GroupTrState state = getGroup(groupId);
		java.util.TreeMap<Long, GroupTrPost> ordered =
				new java.util.TreeMap<>();
		for (int idx = 0; idx < collected.size(); idx++) {
			GroupTrPost p = collected.get(idx);
			if (!p.isLocal()) {
				byte[] sig = collectedSigs.get(idx);
				if (sig == null || sig.length == 0) continue;
				byte[] signedInput = buildGroupPostSignedInput(
						p.getGroupId(), (int) p.getEpoch(),
						p.getSenderPubKey(), p.getSenderName(), p.getBody(),
						p.getTimestamp(), p.getAutoDeleteTimerMs());
				if (!verify(sig, "org.zerionproject/GROUP_POST",
						signedInput, p.getSenderPubKey())) {
					continue;
				}
				if (!isMemberOrCreator(state, p.getSenderPubKey())) continue;
			}
			ordered.put(p.getTimestamp() * 100_000L
					+ (long) ordered.size(), p);
		}
		for (GroupTrPost lp : preexistingLocal) {
			boolean present = false;
			for (GroupTrPost o : ordered.values()) {
				if (isSamePost(o, lp)) {
					present = true;
					break;
				}
			}
			if (!present) {
				ordered.put(lp.getTimestamp() * 100_000L
						+ (long) ordered.size(), lp);
			}
		}
		synchronized (q) {
			q.clear();
			int skip = Math.max(0, ordered.size()
					- MAX_CACHED_POSTS_PER_GROUP);
			int i = 0;
			for (GroupTrPost p : ordered.values()) {
				if (i++ < skip) continue;
				q.addLast(p);
			}
		}
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
					contactName);
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
				if (amCurrentMember) {
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
			ContactId contactId, byte[] contactPubKey, String contactName)
			throws DbException {
		try {
			BdfList list = BdfList.of(contactPubKey, contactName);
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
			BdfList list = clientHelper.toList(fromHexString(hex));
			if (list.size() < 2) return null;
			byte[] contactPubKey = list.getRaw(0);
			String contactName = list.getString(1);
			return new PendingInviteSent(contactPubKey, contactName);
		} catch (DbException | FormatException ex) {
			return null;
		}
	}

	private void removeInviteSent(byte[] grouptrGroupId, ContactId contactId)
			throws DbException {
		Settings out = new Settings();
		out.put(toHexString(grouptrGroupId) + ":" + contactId.getInt(), "");
		settingsManager.mergeSettings(out, SETTINGS_NS_INVITES_SENT);
	}

	private void persistInviteReceived(byte[] grouptrGroupId,
			String groupName, byte[] salt, String creatorName,
			byte[] creatorPubKey, ContactId contactId, long inviteTimestamp)
			throws DbException {
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
				result.add(new GroupTrPendingInvite(fromHexString(key),
						groupName, creatorName, inviteTs));
			} catch (FormatException ex) {
				continue;
			}
		}
		return result;
	}

	private static final class PendingInviteSent {
		final byte[] contactPubKey;
		final String contactName;

		PendingInviteSent(byte[] contactPubKey, String contactName) {
			this.contactPubKey = contactPubKey;
			this.contactName = contactName;
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

	private static final long INVITE_OFFER_MAX_AGE_MS =
			7L * 24L * 60L * 60L * 1000L;
	private static final long INVITE_OFFER_FUTURE_SKEW_MS =
			5L * 60L * 1000L;

	private void handleGrouptrInviteOffer(
			org.zerionproject.app.api.messaging.event
					.GroupTrInviteOfferReceivedEvent ev) {
		byte[] grouptrGid = ev.getGrouptrGroupId();
		byte[] creatorPub = ev.getCreatorPubKey();
		try {
			long now = clock.currentTimeMillis();
			long inviteTs = ev.getInviteTimestamp();
			if (inviteTs > now + INVITE_OFFER_FUTURE_SKEW_MS) return;
			if (now - inviteTs > INVITE_OFFER_MAX_AGE_MS) return;
			byte[] senderPub = lookupSenderPubKey(ev.getContactId());
			if (senderPub == null
					|| !Arrays.equals(senderPub, creatorPub)) return;
			GroupTrState existing = getGroup(grouptrGid);
			if (existing != null && !existing.isDissolved()) {
				LocalAuthor laCheck = db.transactionWithResult(true,
						identityManager::getLocalAuthor);
				byte[] selfPub = laCheck.getPublicKey().getEncoded();
				for (GroupTrMember m : existing.getMembers()) {
					if (Arrays.equals(m.getPubKey(), selfPub)) return;
				}
			}
			if (loadInviteReceived(grouptrGid) != null) return;
			LocalAuthor la = db.transactionWithResult(true,
					identityManager::getLocalAuthor);
			byte[] localPub = la.getPublicKey().getEncoded();
			byte[] derived = deriveGroupId(ev.getCreatorName(), creatorPub,
					ev.getGroupName(), ev.getSalt());
			if (!Arrays.equals(derived, grouptrGid)) return;
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
		if (responderPub == null
				|| !Arrays.equals(responderPub, pis.contactPubKey)) return;
		GroupTrState s;
		try {
			s = getGroup(grouptrGid);
		} catch (DbException ex) {
			return;
		}
		if (s == null) return;
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
			if (s.isDissolved()) return;
			byte[] sig = e.getRecordSig();
			byte[] signedInput = e.getSignedInput();
			byte[] senderPubKey = lookupSenderPubKey(e.getContactId());
			switch (e.getKind()) {
				case MEMBER_ADDED:
					if (senderPubKey == null
							|| !Arrays.equals(senderPubKey,
									s.getCreatorPubKey())) return;
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, senderPubKey)) return;
					applyMemberAdded(s, e);
					break;
				case MEMBER_REMOVED:
					if (Arrays.equals(e.getTargetPubKey(),
							s.getCreatorPubKey())) return;
					if (e.getToEpoch() <= s.getEpoch()) return;
					if (senderPubKey == null
							|| !Arrays.equals(senderPubKey,
									s.getCreatorPubKey())) return;
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, senderPubKey)) return;
					applyMemberRemoved(s, e);
					break;
				case MEMBER_LEFT:
					if (Arrays.equals(e.getTargetPubKey(),
							s.getCreatorPubKey())) return;
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, e.getTargetPubKey())) return;
					applyMemberLeft(s, e);
					break;
				case GROUP_DISSOLVED:
					if (e.getEpoch() <= s.getEpoch()) return;
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, s.getCreatorPubKey())) return;
					s.setDissolved(true);
					s.setEpoch(e.getEpoch());
					persist(s);
					removeFromDevice(s.getGroupId());
					break;
				case ROLE_CHANGED:
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, s.getCreatorPubKey())) return;
					applyRoleChanged(s, e);
					break;
			}
		} catch (DbException | FormatException ex) {
		}
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
			if (s == null || s.isDissolved()) return;
			if (e.getFromEpoch() != s.getEpoch()) return;
			if (e.getToEpoch() != e.getFromEpoch() + 1) return;
			byte[] senderPubKey = lookupSenderPubKey(e.getContactId());
			if (senderPubKey == null
					|| !Arrays.equals(senderPubKey, s.getCreatorPubKey()))
				return;
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
			if (s == null || s.isDissolved()) return;
			if (e.getEpoch() <= s.getEpoch()) return;
			if (!verify(e.getRecordSig(),
					"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT",
					e.getSignedInput(), s.getCreatorPubKey())) return;
			byte[] mc = e.getMemberCanonical();
			if (mc.length % 37 != 0) return;
			int n = mc.length / 37;
			if (n > org.zerionproject.app.grouptr.GroupTrConstants
					.MAX_GROUP_MEMBERS) return;
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
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pk)) {
				s.setEpoch(e.getEpoch());
				persist(s);
				drainFutureBuffer(s.getGroupId(), e.getEpoch());
				return;
			}
		}
		List<GroupTrMember> next = new ArrayList<>(s.getMembers());
		next.add(new GroupTrMember(pk, mname, e.getTimestamp(),
				e.getEpoch()));
		s.setMembers(next);
		s.setEpoch(e.getEpoch());
		persist(s);
		drainFutureBuffer(s.getGroupId(), e.getEpoch());
	}

	private void applyMemberRemoved(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] pk = e.getTargetPubKey();
		if (pk == null) return;
		if (e.getToEpoch() <= s.getEpoch()) return;
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
		for (GroupTrMember m : s.getMembers()) {
			if (!Arrays.equals(m.getPubKey(), pk)) next.add(m);
		}
		s.setMembers(next);
		s.setEpoch(e.getToEpoch());
		persist(s);
		drainFutureBuffer(s.getGroupId(), e.getToEpoch());
	}

	private void applyMemberLeft(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] pk = e.getTargetPubKey();
		if (pk == null) return;
		if (e.getEpoch() <= s.getEpoch()) return;
		List<GroupTrMember> next = new ArrayList<>(s.getMembers().size());
		boolean found = false;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pk)) {
				found = true;
			} else {
				next.add(m);
			}
		}
		if (!found) return;
		s.setMembers(next);
		s.setEpoch(Math.min(e.getEpoch(), s.getEpoch() + 1));
		persist(s);
		drainFutureBuffer(s.getGroupId(), s.getEpoch());
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
			byte[] senderPubKey, String senderName, byte[] ciphertext,
			long timestamp, long ttlMs) {
		byte[] nameHash = crypto.hash(
				"org.zerionproject/GROUP_POST_NAME",
				senderName.getBytes(
						java.nio.charset.StandardCharsets.UTF_8));
		byte[] ctHash = crypto.hash(
				"org.zerionproject/GROUP_POST_CT", ciphertext);
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
		String key = toHexString(ed25519PubKey);
		byte[] cached = mlDsaPubKeyCache.get(key);
		if (cached != null) {
			return cached == NEGATIVE_CACHE_SENTINEL ? null : cached;
		}
		byte[] fromMember = lookupMemberMlDsaPubKey(ed25519PubKey);
		if (fromMember != null) {
			byte[] existing = mlDsaPubKeyCache.putIfAbsent(key, fromMember);
			if (existing != null
					&& existing != NEGATIVE_CACHE_SENTINEL) {
				return existing;
			}
			return fromMember;
		}
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		if (Arrays.equals(la.getPublicKey().getEncoded(), ed25519PubKey)) {
			byte[] local = identityManager.getLocalMlDsaSigPublicKey();
			byte[] existing = mlDsaPubKeyCache.putIfAbsent(key,
					local != null ? local : NEGATIVE_CACHE_SENTINEL);
			if (existing != null && existing != NEGATIVE_CACHE_SENTINEL) {
				return existing;
			}
			return local;
		}
		byte[] result = db.transactionWithNullableResult(true, txn -> {
			for (Contact c : contactManager.getContacts(txn)) {
				byte[] p = c.getAuthor().getPublicKey().getEncoded();
				if (Arrays.equals(p, ed25519PubKey)) {
					return c.getMlDsaSigPublicKey();
				}
			}
			return null;
		});
		byte[] existing = mlDsaPubKeyCache.putIfAbsent(key,
				result != null ? result : NEGATIVE_CACHE_SENTINEL);
		if (existing != null && existing != NEGATIVE_CACHE_SENTINEL) {
			return existing;
		}
		return result;
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
			mlDsaPubKeyCache.clear();
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
	public void sendGroupPost(byte[] groupId, byte[] body,
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
			long effectiveTtl = autoDeleteTimerMs > 0
					? autoDeleteTimerMs
					: s.getDefaultAutoDeleteTimerMs();
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
			byte[] signed = buildGroupPostSignedInput(groupId, epoch,
					localPub, senderName, body, timestamp, effectiveTtl);
			byte[] sig = signOrThrow(
					"org.zerionproject/GROUP_POST",
					signed, signingKey);
			final String finalSenderName = senderName;
			db.transaction(false, txn -> {
				BdfDictionary selfMeta = new BdfDictionary();
				selfMeta.put("messageType", 32L);
				selfMeta.put("groupId", groupId);
				selfMeta.put("groupEpoch", (long) epoch);
				selfMeta.put("groupSenderPubKey", localPub);
				selfMeta.put("groupSenderName", finalSenderName);
				selfMeta.put("groupCiphertext", body);
				selfMeta.put("timestamp", timestamp);
				selfMeta.put("autoDeleteTimer", effectiveTtl);
				selfMeta.put("groupRecordSig", sig);
				for (GroupTrMember m : s.getMembers()) {
					if (Arrays.equals(m.getPubKey(), localPub)) continue;
					Contact c = findContactByPubKey(txn, m.getPubKey());
					if (c == null) continue;
					BdfList msgBody = effectiveTtl > 0
							? BdfList.of(32L, groupId, (long) epoch,
									localPub, finalSenderName, body, sig,
									effectiveTtl)
							: BdfList.of(32L, groupId, (long) epoch,
									localPub, finalSenderName, body, sig);
					dispatchToContact(txn, c, timestamp, msgBody, selfMeta);
				}
			});
			cacheLocalPost(groupId, localPub, finalSenderName, body,
					timestamp, epoch, effectiveTtl);
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
		GroupTrState s = requireWritable(groupId);
		requireLocalIsCreator(s);
		s.setDefaultAutoDeleteTimerMs(ms < 0 ? 0 : ms);
		try {
			persist(s);
		} catch (FormatException ex) {
			throw new DbException(ex);
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
						"Local ML-DSA private key missing — "
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
		dispatchToContact(txn, c, timestamp, body, new BdfDictionary());
	}

	private void dispatchToContact(Transaction txn, Contact c,
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
