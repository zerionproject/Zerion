package org.briarproject.briar.grouptr;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SignaturePublicKey;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.util.ByteUtils;
import org.briarproject.briar.api.grouptr.GroupTrAuthException;
import org.briarproject.briar.api.grouptr.GroupTrManager;
import org.briarproject.briar.api.grouptr.GroupTrMember;
import org.briarproject.briar.api.grouptr.GroupTrPost;
import org.briarproject.briar.api.grouptr.GroupTrState;
import org.briarproject.briar.api.grouptr.MemberRole;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.event.GroupEpochCommitEvent;
import org.briarproject.briar.api.messaging.event.GroupMemberListSnapshotEvent;
import org.briarproject.briar.api.messaging.event.GroupMembershipChangedEvent;
import org.briarproject.briar.api.messaging.event.GroupPostReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.toHexString;
import static org.briarproject.briar.grouptr.GroupTrConstants.CLIENT_ID;
import static org.briarproject.briar.grouptr.GroupTrConstants.FORMAT_VERSION;
import static org.briarproject.briar.grouptr.GroupTrConstants.GROUP_ID_LABEL;
import static org.briarproject.briar.grouptr.GroupTrConstants.GROUP_SALT_LENGTH;
import static org.briarproject.briar.grouptr.GroupTrConstants.MAJOR_VERSION;
import static org.briarproject.briar.grouptr.GroupTrConstants.SETTINGS_NS_INDEX;
import static org.briarproject.briar.grouptr.GroupTrConstants.SETTINGS_NS_PREFIX;
import static org.briarproject.briar.grouptr.GroupTrConstants.SIGNING_LABEL_GROUP_EPOCH_COMMIT;
import static org.briarproject.briar.grouptr.GroupTrConstants.SIGNING_LABEL_GROUP_MEMBERSHIP;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_CREATED;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_CREATOR_NAME;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_CREATOR_PUBKEY;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_DEFAULT_TTL;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_DISSOLVED;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_EPOCH;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_GROUP_IDS;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_MEMBERS;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_NAME;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_SALT;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_STEALTH_NAME;

@ThreadSafe
@NotNullByDefault
class GroupTrManagerImpl
		implements GroupTrManager, EventListener, OpenDatabaseHook {

	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final ClientHelper clientHelper;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;
	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final EventBus eventBus;
	private final Clock clock;
	private final SecureRandom random;
	private final java.util.Map<String, java.util.ArrayDeque<GroupTrPost>>
			postCache = new java.util.concurrent.ConcurrentHashMap<>();
	private final java.util.Map<String,
			java.util.TreeMap<Long, java.util.List<GroupTrPost>>>
			futureBuffer = new java.util.concurrent.ConcurrentHashMap<>();
	private static final int MAX_CACHED_POSTS_PER_GROUP = 200;
	private static final int EPOCH_BUFFER_TOLERANCE = 5;
	private static final int MAX_BUFFERED_POSTS_PER_GROUP = 500;

	private final java.util.Map<String, byte[]> mlDsaPubKeyCache =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final byte[] NEGATIVE_CACHE_SENTINEL = new byte[0];

	@Inject
	GroupTrManagerImpl(DatabaseComponent db,
			SettingsManager settingsManager, ClientHelper clientHelper,
			CryptoComponent crypto, IdentityManager identityManager,
			ContactManager contactManager, MessagingManager messagingManager,
			EventBus eventBus, Clock clock) {
		this.db = db;
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
		} else if (e instanceof org.briarproject.bramble.api.contact.event
				.ContactRemovedEvent
				|| e instanceof org.briarproject.bramble.api.contact.event
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
				e.getCiphertext());
		if (!verify(sig, "org.briarproject.zerion/GROUP_POST",
				signedInput, e.getSenderPubKey())) {
			return;
		}
		String key = toHexString(e.getGroupId());
		long postEpoch = e.getEpoch();
		long localEpoch;
		try {
			GroupTrState s = getGroup(e.getGroupId());
			localEpoch = s == null ? 0L : s.getEpoch();
		} catch (DbException ex) {
			localEpoch = 0L;
		}
		GroupTrPost p = new GroupTrPost(e.getGroupId(),
				e.getSenderPubKey(), e.getSenderName(),
				e.getCiphertext(), e.getTimestamp(), postEpoch, false);
		if (postEpoch < localEpoch - 1L) {
			return;
		}
		if (postEpoch > localEpoch + EPOCH_BUFFER_TOLERANCE) {
			bufferFuturePost(key, p);
			return;
		}
		deliverToCache(key, p);
	}

	private void deliverToCache(String key, GroupTrPost p) {
		java.util.ArrayDeque<GroupTrPost> q = postCache.computeIfAbsent(
				key, k -> new java.util.ArrayDeque<>());
		synchronized (q) {
			q.addLast(p);
			while (q.size() > MAX_CACHED_POSTS_PER_GROUP) {
				q.pollFirst();
			}
		}
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
			String senderName, byte[] body, long timestamp, long epoch) {
		String key = toHexString(groupId);
		java.util.ArrayDeque<GroupTrPost> q = postCache.computeIfAbsent(
				key, k -> new java.util.ArrayDeque<>());
		synchronized (q) {
			q.addLast(new GroupTrPost(groupId, senderPub, senderName,
					body, timestamp, epoch, true));
			while (q.size() > MAX_CACHED_POSTS_PER_GROUP) {
				q.pollFirst();
			}
		}
	}

	@Override
	public java.util.List<GroupTrPost> getRecentPosts(byte[] groupId) {
		String key = toHexString(groupId);
		java.util.ArrayDeque<GroupTrPost> q = postCache.get(key);
		if (q == null) {
			try {
				loadHistoryIntoCache(groupId);
				q = postCache.get(key);
				if (q == null) return java.util.Collections.emptyList();
			} catch (DbException ex) {
				return java.util.Collections.emptyList();
			}
		}
		synchronized (q) {
			return new ArrayList<>(q);
		}
	}

	private void loadHistoryIntoCache(byte[] groupId) throws DbException {
		String key = toHexString(groupId);
		java.util.ArrayDeque<GroupTrPost> q =
				postCache.computeIfAbsent(key,
						k -> new java.util.ArrayDeque<>());
		java.util.TreeMap<Long, GroupTrPost> ordered =
				new java.util.TreeMap<>();
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
				org.briarproject.bramble.api.sync.GroupId cg =
						messagingManager.getContactGroup(c).getId();
				try {
					java.util.Map<org.briarproject.bramble.api.sync.MessageId,
							org.briarproject.bramble.api.data.BdfDictionary>
							msgs = clientHelper
									.getMessageMetadataAsDictionary(txn, cg);
					for (java.util.Map.Entry<
							org.briarproject.bramble.api.sync.MessageId,
							org.briarproject.bramble.api.data.BdfDictionary>
							e : msgs.entrySet()) {
						org.briarproject.bramble.api.data.BdfDictionary
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
						boolean local = Arrays.equals(senderPub,
								localPub);
						if (local) {
							String dedupKey = ts + ":" + epoch;
							if (!seenLocalIds.add(dedupKey)) continue;
						}
						ordered.put(ts * 100_000L
										+ (long) ordered.size(),
								new GroupTrPost(groupId, senderPub,
										senderName, body, ts, epoch,
										local));
					}
				} catch (FormatException ex) {
					// skip bad row
				}
			}
		});
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
				byte[] gid = fromHexString(hex);
				GroupTrState gs = getGroup(gid);
				if (gs != null) out.add(gs);
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
			persist(s);
			addToIndex(groupId);
			return s;
		} catch (FormatException ex) {
			throw new DbException(ex);
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
		try {
			GroupTrState s = getGroup(e.getGroupId());
			if (s == null) {
				if (e.getKind() ==
						GroupMembershipChangedEvent.ChangeKind.MEMBER_ADDED) {
					autoApplyMemberAddedOfSelf(e);
				}
				return;
			}
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
					if (e.getToEpoch() != s.getEpoch() + 1) return;
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
					break;
				case ROLE_CHANGED:
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, s.getCreatorPubKey())) return;
					applyRoleChanged(s, e);
					break;
			}
		} catch (DbException | FormatException ex) {
			// drop silently — no logging in production per project policy
		}
	}

	@javax.annotation.Nullable
	private byte[] lookupSenderPubKey(
			org.briarproject.bramble.api.contact.ContactId contactId) {
		try {
			return db.transactionWithNullableResult(true, txn -> {
				try {
					org.briarproject.bramble.api.contact.Contact c =
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

	private void autoApplyMemberAddedOfSelf(GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] targetPub = e.getTargetPubKey();
		if (targetPub == null || targetPub.length != 32) return;
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		byte[] localPub = la.getPublicKey().getEncoded();
		if (!Arrays.equals(targetPub, localPub)) return;
		byte[] senderPub = lookupSenderPubKey(e.getContactId());
		if (senderPub == null) return;
		if (!verify(e.getRecordSig(), SIGNING_LABEL_GROUP_MEMBERSHIP,
				e.getSignedInput(), senderPub)) return;
		Contact senderContact = db.transactionWithNullableResult(true, txn -> {
			try {
				return contactManager.getContact(txn, e.getContactId());
			} catch (DbException ex) {
				return null;
			}
		});
		if (senderContact == null) return;
		String senderName = senderContact.getAuthor().getName();
		String targetName = e.getTargetName() != null
				? e.getTargetName() : la.getName();
		List<GroupTrMember> members = new ArrayList<>(2);
		members.add(new GroupTrMember(senderPub, senderName, e.getTimestamp(),
				0L, MemberRole.CREATOR));
		members.add(new GroupTrMember(localPub, targetName, e.getTimestamp(),
				e.getEpoch()));
		GroupTrState s = new GroupTrState(e.getGroupId(), "",
				new byte[32], senderPub, senderName,
				e.getTimestamp(), e.getEpoch(), false, members);
		persist(s);
		addToIndex(e.getGroupId());
	}

	private void handleEpochCommit(GroupEpochCommitEvent e) {
		try {
			GroupTrState s = getGroup(e.getGroupId());
			if (s == null || s.isDissolved()) return;
			if (e.getFromEpoch() != s.getEpoch()) return;
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
			// drop silently
		}
	}

	private void handleMemberListSnapshot(GroupMemberListSnapshotEvent e) {
		try {
			GroupTrState s = getGroup(e.getGroupId());
			if (s == null || s.isDissolved()) return;
			if (e.getEpoch() < s.getEpoch()) return;
			if (!verify(e.getRecordSig(),
					"org.briarproject.zerion/GROUP_MEMBER_LIST_SNAPSHOT",
					e.getSignedInput(), s.getCreatorPubKey())) return;
			byte[] mc = e.getMemberCanonical();
			if (mc.length % 37 != 0) return;
			int n = mc.length / 37;
			List<GroupTrMember> reconciled = new ArrayList<>(n);
			List<GroupTrMember> prev = s.getMembers();
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
				for (GroupTrMember pm : prev) {
					if (Arrays.equals(pm.getPubKey(), pk)) {
						name = pm.getName();
						joinedAt = pm.getJoinedAt();
						break;
					}
				}
				reconciled.add(new GroupTrMember(pk, name, joinedAt,
						joinedAtEpoch, r));
			}
			s.setMembers(reconciled);
			if (e.getEpoch() > s.getEpoch()) {
				s.setEpoch(e.getEpoch());
			}
			persist(s);
			drainFutureBuffer(s.getGroupId(), s.getEpoch());
		} catch (DbException | FormatException ex) {
			// drop silently
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
		s.setEpoch(e.getEpoch());
		persist(s);
		drainFutureBuffer(s.getGroupId(), s.getEpoch());
	}

	private boolean verify(byte[] sig, String label, byte[] signed,
			byte[] pubKeyBytes) {
		if (signed.length == 0) return false;
		try {
			boolean sigIsHybrid = sig.length ==
					org.briarproject.bramble.api.crypto.PostQuantumConstants
							.HYBRID_SIGNATURE_BYTES;
			byte[] peerMlDsaPub = lookupPeerMlDsaPubKey(pubKeyBytes);
			if (sigIsHybrid && peerMlDsaPub != null) {
				org.briarproject.bramble.api.crypto.HybridSignaturePublicKey
						hybridPub = new org.briarproject.bramble.api.crypto
						.HybridSignaturePublicKey(pubKeyBytes, peerMlDsaPub);
				return crypto.verifySignature(sig, label, signed, hybridPub);
			}
			byte[] ed25519Sig = sig;
			if (sigIsHybrid) {
				ed25519Sig = new byte[64];
				System.arraycopy(sig, 0, ed25519Sig, 0, 64);
			}
			PublicKey p = new SignaturePublicKey(pubKeyBytes);
			return crypto.verifySignature(ed25519Sig, label, signed, p);
		} catch (GeneralSecurityException ex) {
			return false;
		} catch (DbException ex) {
			return false;
		}
	}

	private byte[] buildGroupPostSignedInput(byte[] groupId, int epoch,
			byte[] senderPubKey, String senderName, byte[] ciphertext) {
		byte[] nameHash = crypto.hash(
				"org.briarproject.zerion/GROUP_POST_NAME",
				senderName.getBytes(
						java.nio.charset.StandardCharsets.UTF_8));
		byte[] ctHash = crypto.hash(
				"org.briarproject.zerion/GROUP_POST_CT", ciphertext);
		byte[] out = new byte[32 + 4 + 32 + nameHash.length
				+ ctHash.length];
		System.arraycopy(groupId, 0, out, 0, 32);
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		System.arraycopy(senderPubKey, 0, out, 36, 32);
		System.arraycopy(nameHash, 0, out, 68, nameHash.length);
		System.arraycopy(ctHash, 0, out, 68 + nameHash.length,
				ctHash.length);
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
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		if (Arrays.equals(la.getPublicKey().getEncoded(), ed25519PubKey)) {
			byte[] local = identityManager.getLocalMlDsaSigPublicKey();
			mlDsaPubKeyCache.put(key,
					local != null ? local : NEGATIVE_CACHE_SENTINEL);
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
		mlDsaPubKeyCache.put(key,
				result != null ? result : NEGATIVE_CACHE_SENTINEL);
		return result;
	}

	private void persist(GroupTrState s)
			throws DbException, FormatException {
		Settings out = new Settings();
		out.put(S_NAME, s.getName());
		out.put(S_SALT, toHexString(s.getSalt()));
		out.put(S_CREATOR_PUBKEY, toHexString(s.getCreatorPubKey()));
		out.put(S_CREATOR_NAME, s.getCreatorName());
		out.putLong(S_CREATED, s.getCreated());
		out.putLong(S_EPOCH, s.getEpoch());
		out.putBoolean(S_DISSOLVED, s.isDissolved());
		out.putLong(S_DEFAULT_TTL, s.getDefaultAutoDeleteTimerMs());
		BdfList list = new BdfList();
		for (GroupTrMember m : s.getMembers()) {
			BdfList ml = new BdfList();
			ml.add(m.getPubKey());
			ml.add(m.getName());
			ml.add(m.getJoinedAt());
			ml.add(m.getJoinedAtEpoch());
			ml.add((long) m.getRole().getInt());
			list.add(ml);
		}
		out.put(S_MEMBERS, toHexString(clientHelper.toByteArray(list)));
		settingsManager.mergeSettings(out, nsOf(s.getGroupId()));
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
			members.add(new GroupTrMember(pk, ml.getString(1),
					ml.getLong(2), ml.getLong(3), effective));
		}
		return new GroupTrState(groupId, name, salt, creatorPub,
				creatorName, created, epoch, dissolved, members,
				defaultTtl);
	}

	private void addToIndex(byte[] groupId) throws DbException {
		Settings index = settingsManager.getSettings(SETTINGS_NS_INDEX);
		String existing = index.get(S_GROUP_IDS);
		String hex = toHexString(groupId);
		if (existing == null || existing.isEmpty()) {
			index.put(S_GROUP_IDS, hex);
		} else if (!existing.contains(hex)) {
			index.put(S_GROUP_IDS, existing + "," + hex);
		} else {
			return;
		}
		settingsManager.mergeSettings(index, SETTINGS_NS_INDEX);
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
		PrivateKey signingKey = la.getPrivateKey();
		long timestamp = clock.currentTimeMillis();
		int epoch = (int) s.getEpoch();
		String alias = getStealthName(groupId);
		String senderName = alias != null ? alias : la.getName();
		byte[] nameHash = crypto.hash(
				"org.briarproject.zerion/GROUP_POST_NAME",
				senderName.getBytes(
						java.nio.charset.StandardCharsets.UTF_8));
		byte[] ctHash = crypto.hash(
				"org.briarproject.zerion/GROUP_POST_CT", body);
		byte[] signed = new byte[32 + 4 + 32 + nameHash.length
				+ ctHash.length];
		System.arraycopy(groupId, 0, signed, 0, 32);
		for (int i = 0; i < 4; i++) {
			signed[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		System.arraycopy(localPub, 0, signed, 36, 32);
		System.arraycopy(nameHash, 0, signed, 68, nameHash.length);
		System.arraycopy(ctHash, 0, signed, 68 + nameHash.length,
				ctHash.length);
		byte[] sig = signOrThrow(
				"org.briarproject.zerion/GROUP_POST",
				signed, signingKey);
		final String finalSenderName = senderName;
		db.transaction(false, txn -> {
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
				dispatchToContact(txn, c, timestamp, msgBody);
			}
		});
		cacheLocalPost(groupId, localPub, finalSenderName, body,
				timestamp, epoch);
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
	public void addMember(byte[] groupId, byte[] addedPubKey,
			String addedName) throws DbException {
		GroupTrState s = requireWritable(groupId);
		requireLocalIsCreator(s);
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
			fanOut(txn, s, removedBody, timestamp, removedPubKey);
			fanOut(txn, s, commitBody, timestamp, removedPubKey);
		});
		applyLocalRemove(s, removedPubKey, toEpoch);
	}

	@Override
	public void leaveGroup(byte[] groupId) throws DbException {
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
	}

	@Override
	public void dissolveGroup(byte[] groupId) throws DbException {
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
			if (mlDsaPriv != null) {
				org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey
						hybridKey = new org.briarproject.bramble.api.crypto
						.HybridSignaturePrivateKey(key.getEncoded(),
						mlDsaPriv);
				return crypto.hybridSign(label, signed, hybridKey);
			}
			return crypto.sign(label, signed, key);
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
		byte[] contactGroupId =
				messagingManager.getContactGroup(c).getId().getBytes();
		try {
			byte[] bodyBytes = clientHelper.toByteArray(body);
			Message m = clientHelper.createMessage(
					new org.briarproject.bramble.api.sync.GroupId(
							contactGroupId),
					timestamp, bodyBytes);
			clientHelper.addLocalMessage(txn, m, new BdfDictionary(),
					true, false);
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
				"org.briarproject.zerion/GROUP_MEMBER_LIST_SNAPSHOT",
				signed, signingKey);
		BdfList body = BdfList.of(41L, groupId, (long) epoch, timestamp,
				memberList, sig);
		db.transaction(false, txn -> fanOut(txn, s, body, timestamp, null));
	}

	private byte[] snapshotSignedInput(byte[] groupId, int epoch,
			long timestamp, byte[] memberCanonical) {
		byte[] mlHash = crypto.hash(
				"org.briarproject.zerion/GROUP_MEMBER_LIST",
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
				"org.briarproject.zerion/GROUP_EPOCH_SEED", pqSeed);
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
