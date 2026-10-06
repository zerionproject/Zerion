package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupEpochCommitEvent;
import org.zerionproject.app.api.messaging.event.GroupMemberListSnapshotEvent;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent.ChangeKind;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.NullableDbCallable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

final class GroupTrTestNode {

	static final byte[] ML_DSA_PUB =
			new byte[PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES];
	static final byte[] SIG =
			new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES];
	static final byte[] SIGNED = new byte[] {1};

	static final byte[] FORGED_SIG = forgedSig();

	private static final AtomicInteger nextContact = new AtomicInteger(1);

	static final class Sent {

		final ContactId to;
		final BdfList body;

		private Sent(ContactId to, BdfList body) {
			this.to = to;
			this.body = body;
		}
	}

	private interface Handler {
		@Nullable
		Object handle(String name, Object[] args) throws Exception;
	}

	final LocalAuthor local;
	final GroupTrManagerImpl manager;
	final List<Sent> sent = new ArrayList<>();
	final List<Object> broadcasts = new ArrayList<>();
	long now = 1_000_000L;

	private final Map<ContactId, Contact> contacts = new LinkedHashMap<>();
	private final Map<ContactId, Group> contactGroups = new HashMap<>();
	private final Map<GroupId, Map<MessageId, BdfDictionary>> store =
			new HashMap<>();
	private final Set<MessageId> read = new HashSet<>();
	private long largestBodyRead = 0;
	private long bodyBytesRead = 0;
	private int metadataReads = 0;
	private final Map<MessageId, Integer> timesRead = new HashMap<>();
	private int signatureChecks = 0;
	private final Set<String> withoutMlDsa = new HashSet<>();
	private final Map<String, Settings> settings = new HashMap<>();
	private final Map<String, BdfList> lists = new HashMap<>();
	private final Map<MessageId, Message> messages = new HashMap<>();
	private final Map<MessageId, Long> deadlines = new HashMap<>();
	private final Map<MessageId, Long> durations = new HashMap<>();
	private final Map<ContactId, Integer> minorVersions = new HashMap<>();
	private final Set<MessageId> removeOnRead = new HashSet<>();
	private final Set<MessageId> deletedBodies = new HashSet<>();
	private final Set<MessageId> unparseableMetadata = new HashSet<>();
	private final List<Runnable> deferred = new ArrayList<>();
	private final boolean deferIo;
	private int txnDepth = 0;
	private boolean writeTxn = false;
	private int writeTxns = 0;
	private int copiesInWriteTxn = 0;
	private int maxCopiesPerWriteTxn = 0;
	private long bodyBytesUnderWriteLock = 0;
	private int queries = 0;
	private long tokens = 0;

	GroupTrTestNode(byte[] localPub) {
		this(localPub, false);
	}

	GroupTrTestNode(byte[] localPub, boolean deferIo) {
		this.deferIo = deferIo;
		local = new LocalAuthor(new AuthorId(getRandomId()), 1, "Local",
				new SignaturePublicKey(localPub),
				new SignaturePrivateKey(new byte[32]));
		java.util.concurrent.Executor io = deferIo
				? deferred::add : Runnable::run;
		manager = new GroupTrManagerImpl(
				fake(DatabaseComponent.class, this::db), io,
				fake(SettingsManager.class, this::settings),
				fake(ClientHelper.class, this::client),
				fake(CryptoComponent.class, this::crypto),
				fake(IdentityManager.class, this::identity),
				fake(ContactManager.class, this::contacts),
				fake(MessagingManager.class, this::messaging),
				fake(EventBus.class, (name, args) -> {
					if (name.equals("broadcast")) broadcasts.add(args[0]);
					return null;
				}),
				fake(Clock.class, (name, args) -> now));
	}

	ContactId addContact(byte[] pub) {
		ContactId id = new ContactId(nextContact.getAndIncrement());
		Author a = new Author(new AuthorId(getRandomId()), 1, "c" + id.getInt(),
				new SignaturePublicKey(pub));
		contacts.put(id, new Contact(id, a, local.getId(), null, null, true,
				true, false, ML_DSA_PUB));
		return id;
	}

	void announceMinorVersion(ContactId c, int minor) {
		minorVersions.put(c, minor);
	}

	@Nullable
	Long cleanupDeadline(MessageId id) {
		return deadlines.get(id);
	}

	@Nullable
	Long cleanupTimerDuration(MessageId id) {
		return durations.get(id);
	}

	void runDeferred() {
		List<Runnable> run = new ArrayList<>(deferred);
		deferred.clear();
		for (Runnable r : run) r.run();
	}

	long bodyBytesUnderWriteLock() {
		return bodyBytesUnderWriteLock;
	}

	int maxCopiesPerWriteTxn() {
		return maxCopiesPerWriteTxn;
	}

	int writeTxns() {
		return writeTxns;
	}

	int queries() {
		return queries;
	}

	void deleteBody(MessageId id) {
		deletedBodies.add(id);
	}

	void makeMetadataUnparseable(MessageId id) {
		unparseableMetadata.add(id);
	}

	void putSentInvite(byte[] groupId, ContactId to, byte[] contactPub,
			@Nullable Long sent) {
		BdfList list = sent == null
				? BdfList.of(contactPub, "Invitee")
				: BdfList.of(contactPub, "Invitee", sent);
		settings.computeIfAbsent(GroupTrConstants.SETTINGS_NS_INVITES_SENT,
				k -> new Settings()).put(toHexString(groupId) + ":"
				+ to.getInt(), toHexString(token(list)));
	}

	@Nullable
	Long settingsTimestamp(byte[] groupId) {
		Settings s = settings.get(GroupTrConstants.SETTINGS_NS_PREFIX
				+ toHexString(groupId));
		if (s == null || s.get(GroupTrConstants.S_SETTINGS_TIMESTAMP) == null) {
			return null;
		}
		return s.getLong(GroupTrConstants.S_SETTINGS_TIMESTAMP, 0L);
	}

	void versionUpdated(ContactId c, int minor) {
		minorVersions.put(c, minor);
		manager.eventOccurred(new org.zerionproject.core.api.versioning.event
				.ClientVersionUpdatedEvent(c,
				new org.zerionproject.core.api.versioning.ClientVersion(
						MessagingManager.CLIENT_ID,
						MessagingManager.MAJOR_VERSION, minor)));
	}

	@Nullable
	BdfDictionary metadata(MessageId id) {
		return stored(id);
	}

	void putGroup(byte[] groupId, byte[] creator, long epoch,
			GroupTrMember... members) {
		Settings out = new Settings();
		out.put(GroupTrConstants.S_NAME, "group");
		out.put(GroupTrConstants.S_SALT, toHexString(new byte[32]));
		out.put(GroupTrConstants.S_CREATOR_PUBKEY, toHexString(creator));
		out.put(GroupTrConstants.S_CREATOR_NAME, "Creator");
		out.putLong(GroupTrConstants.S_CREATED, 0L);
		out.putLong(GroupTrConstants.S_EPOCH, epoch);
		out.putBoolean(GroupTrConstants.S_DISSOLVED, false);
		out.putBoolean(GroupTrConstants.S_REMOVED, false);
		out.putLong(GroupTrConstants.S_DEFAULT_TTL, 0L);
		BdfList list = new BdfList();
		for (GroupTrMember m : members) {
			if (withoutMlDsa.contains(toHexString(m.getPubKey()))) {
				list.add(BdfList.of(m.getPubKey(), m.getName(),
						m.getJoinedAt(), m.getJoinedAtEpoch(),
						(long) m.getRole().getInt()));
				continue;
			}
			list.add(BdfList.of(m.getPubKey(), m.getName(), m.getJoinedAt(),
					m.getJoinedAtEpoch(), (long) m.getRole().getInt(),
					ML_DSA_PUB));
		}
		out.put(GroupTrConstants.S_MEMBERS, toHexString(token(list)));
		settings.put(GroupTrConstants.SETTINGS_NS_PREFIX
				+ toHexString(groupId), out);
		Settings index = settings.computeIfAbsent(
				GroupTrConstants.SETTINGS_NS_INDEX, k -> new Settings());
		String ids = index.get(GroupTrConstants.S_GROUP_IDS);
		index.put(GroupTrConstants.S_GROUP_IDS, ids == null || ids.isEmpty()
				? toHexString(groupId) : ids + "," + toHexString(groupId));
	}

	void putPendingOffer(byte[] groupId, ContactId from, byte[] creatorPub,
			long inviteTimestamp) {
		BdfList list = BdfList.of("group", new byte[32], "Creator",
				creatorPub, (long) from.getInt(), inviteTimestamp);
		settings.computeIfAbsent(GroupTrConstants.SETTINGS_NS_OFFERS_PENDING,
				k -> new Settings()).put(toHexString(groupId),
				toHexString(token(list)));
	}

	void setGroupTimer(byte[] groupId, long ms) {
		settings.get(GroupTrConstants.SETTINGS_NS_PREFIX
				+ toHexString(groupId)).putLong(GroupTrConstants.S_DEFAULT_TTL,
				ms);
	}

	void removeOnFirstRead(MessageId id) {
		removeOnRead.add(id);
	}

	void withoutMlDsaKey(byte[] pub) {
		withoutMlDsa.add(toHexString(pub));
	}

	static GroupTrMember creator(byte[] pub) {
		return new GroupTrMember(pub, "Creator", 0L, 0L, MemberRole.CREATOR);
	}

	static GroupTrMember member(byte[] pub, long joinedAtEpoch) {
		return new GroupTrMember(pub, "Member", 0L, joinedAtEpoch);
	}

	GroupTrState group(byte[] groupId) throws Exception {
		GroupTrState s = manager.getGroup(groupId);
		if (s == null) throw new AssertionError("group not held");
		return s;
	}

	boolean isMember(byte[] groupId, byte[] pub) throws Exception {
		return manager.isMember(groupId, pub);
	}

	void removed(ContactId from, byte[] groupId, byte[] target,
			long fromEpoch) {
		manager.eventOccurred(new GroupMembershipChangedEvent(from,
				ChangeKind.MEMBER_REMOVED, groupId, fromEpoch + 1, now, target,
				null, fromEpoch, fromEpoch + 1, SIG, SIGNED));
	}

	void commit(ContactId from, byte[] groupId, long fromEpoch) {
		manager.eventOccurred(new GroupEpochCommitEvent(from, groupId,
				fromEpoch, fromEpoch + 1, new byte[32], SIG, SIGNED, now));
	}

	void left(ContactId from, byte[] groupId, byte[] leaver, long epoch) {
		manager.eventOccurred(new GroupMembershipChangedEvent(from,
				ChangeKind.MEMBER_LEFT, groupId, epoch, now, leaver, null, 0L,
				0L, SIG, SIGNED));
	}

	void added(ContactId from, byte[] groupId, byte[] target, long epoch) {
		manager.eventOccurred(new GroupMembershipChangedEvent(from,
				ChangeKind.MEMBER_ADDED, groupId, epoch, now, target, "Added",
				0L, 0L, SIG, SIGNED));
	}

	void snapshot(ContactId from, byte[] groupId, long epoch,
			byte[] memberCanonical) {
		manager.eventOccurred(new GroupMemberListSnapshotEvent(from, groupId,
				epoch, now, memberCanonical, SIG, SIGNED));
	}

	void post(ContactId from, byte[] groupId, byte[] sender, long epoch,
			byte[] body, long timestamp) {
		post(from, groupId, sender, epoch, body, timestamp, 0L);
	}

	void post(ContactId from, byte[] groupId, byte[] sender, long epoch,
			byte[] body, long timestamp, long autoDeleteTimer) {
		post(from, new MessageId(getRandomId()), groupId, sender, epoch,
				body, timestamp, autoDeleteTimer, SIG);
	}

	void post(ContactId from, MessageId id, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp, long autoDeleteTimer,
			byte[] sig) {
		manager.eventOccurred(new GroupPostReceivedEvent(from, id, groupId,
				epoch, sender, "Name", body, timestamp, autoDeleteTimer, sig));
	}

	MessageId storeAndPost(ContactId from, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp) {
		return storeAndPost(from, groupId, sender, epoch, body, timestamp,
				0L);
	}

	MessageId storeAndPost(ContactId from, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp, long autoDeleteTimer) {
		MessageId id = store(from, groupId, sender, epoch, body, timestamp,
				autoDeleteTimer);
		post(from, id, groupId, sender, epoch, body, timestamp,
				autoDeleteTimer, SIG);
		return id;
	}

	MessageId receive(ContactId from, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp, long autoDeleteTimer,
			byte[] sig) {
		MessageId id = storeCurrent(from, groupId, sender, epoch, body,
				timestamp, autoDeleteTimer, sig);
		post(from, id, groupId, sender, epoch, body, timestamp,
				autoDeleteTimer, sig);
		return id;
	}

	MessageId receiveLegacy(ContactId from, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp, long autoDeleteTimer,
			byte[] sig) {
		MessageId id = store(from, groupId, sender, epoch, body, timestamp,
				autoDeleteTimer);
		BdfDictionary m = stored(id);
		if (m == null) throw new AssertionError("post not stored");
		m.put("groupRecordSig", sig);
		post(from, id, groupId, sender, epoch, body, timestamp,
				autoDeleteTimer, sig);
		return id;
	}

	MessageId storeCurrent(ContactId from, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp, long autoDeleteTimer,
			byte[] sig) {
		BdfDictionary m = new BdfDictionary();
		m.put("messageType", 32);
		m.put("groupId", groupId);
		m.put("groupEpoch", epoch);
		m.put("groupSenderPubKey", sender);
		m.put("groupSenderName", "Name");
		m.put("groupBodyHash", sha256(
				"org.zerionproject/GROUP_POST_CT", body));
		m.put("groupBodyLength",
				(long) body.length);
		m.put("groupPostState",
				0L);
		m.put("timestamp", timestamp);
		if (autoDeleteTimer != 0L) m.put("autoDeleteTimer", autoDeleteTimer);
		m.put("groupRecordSig", sig);
		MessageId id = new MessageId(getRandomId());
		GroupId g = contactGroup(from).getId();
		store.computeIfAbsent(g, k -> new LinkedHashMap<>()).put(id, m);
		keepRecord(id, g, timestamp, groupId, epoch, sender, body, sig,
				autoDeleteTimer);
		return id;
	}

	private void keepRecord(MessageId id, GroupId g, long timestamp,
			byte[] groupId, long epoch, byte[] sender, byte[] body,
			byte[] sig, long autoDeleteTimer) {
		BdfList record = autoDeleteTimer != 0L
				? BdfList.of(32L, groupId, epoch, sender, "Name", body, sig,
						autoDeleteTimer)
				: BdfList.of(32L, groupId, epoch, sender, "Name", body, sig);
		byte[] token = token(record);
		messages.put(id, new Message(id, g, timestamp, token));
	}

	private static byte[] sha256(String label, byte[]... inputs) {
		try {
			MessageDigest d = MessageDigest.getInstance("SHA-256");
			d.update(label.getBytes(StandardCharsets.UTF_8));
			for (byte[] in : inputs) d.update(in);
			return d.digest();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	MessageId store(ContactId from, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp, long autoDeleteTimer) {
		BdfDictionary m = new BdfDictionary();
		m.put("messageType", 32);
		m.put("groupId", groupId);
		m.put("groupEpoch", epoch);
		m.put("groupSenderPubKey", sender);
		m.put("groupSenderName", "Name");
		m.put("groupCiphertext", body);
		m.put("timestamp", timestamp);
		if (autoDeleteTimer != 0L) m.put("autoDeleteTimer", autoDeleteTimer);
		m.put("groupRecordSig", SIG);
		MessageId id = new MessageId(getRandomId());
		GroupId g = contactGroup(from).getId();
		store.computeIfAbsent(g, k -> new LinkedHashMap<>()).put(id, m);
		keepRecord(id, g, timestamp, groupId, epoch, sender, body, SIG,
				autoDeleteTimer);
		return id;
	}

	MessageId storeWithSig(ContactId from, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp, byte[] sig) {
		MessageId id = store(from, groupId, sender, epoch, body, timestamp,
				0L);
		BdfDictionary m = stored(id);
		if (m == null) throw new AssertionError("post not stored");
		m.put("groupRecordSig", sig);
		return id;
	}

	List<MessageId> storedIds() {
		List<MessageId> out = new ArrayList<>();
		for (Map<MessageId, BdfDictionary> h : store.values()) {
			out.addAll(h.keySet());
		}
		return out;
	}

	boolean isStored(MessageId id) {
		return stored(id) != null || messages.containsKey(id);
	}

	boolean wasRead(MessageId id) {
		return read.contains(id);
	}

	long largestBodyRead() {
		return largestBodyRead;
	}

	int metadataReads() {
		return metadataReads;
	}

	long bodyBytesRead() {
		return bodyBytesRead;
	}

	int timesRead(MessageId id) {
		Integer n = timesRead.get(id);
		return n == null ? 0 : n;
	}

	int signatureChecks() {
		return signatureChecks;
	}

	void forgetReads() {
		read.clear();
		timesRead.clear();
		largestBodyRead = 0;
		bodyBytesRead = 0;
		metadataReads = 0;
	}

	void dropFromMemory(byte[] groupId) throws Exception {
		String hex = toHexString(groupId);
		((Map<?, ?>) field("postCache")).remove(hex);
		((Set<?>) field("historyLoaded")).remove(hex);
	}

	long heldBytes() throws Exception {
		long total = 0;
		for (Object q : ((Map<?, ?>) field("postCache")).values()) {
			synchronized (q) {
				for (Object p : (ArrayDeque<?>) q) {
					total += ((GroupTrPost) p).getBody().length;
				}
			}
		}
		return total;
	}

	static byte[] canonical(Object... pubRoleJoinedAtEpoch) {
		int n = pubRoleJoinedAtEpoch.length / 3;
		ByteBuffer b = ByteBuffer.allocate(n * 37);
		for (int i = 0; i < n; i++) {
			b.put((byte[]) pubRoleJoinedAtEpoch[i * 3]);
			b.put((byte) ((MemberRole) pubRoleJoinedAtEpoch[i * 3 + 1])
					.getInt());
			b.putInt(((Number) pubRoleJoinedAtEpoch[i * 3 + 2]).intValue());
		}
		return b.array();
	}

	static long bytesFrom(List<GroupTrPost> posts, @Nullable byte[] sender) {
		long total = 0;
		for (GroupTrPost p : posts) {
			if (sender == null || Arrays.equals(p.getSenderPubKey(), sender)) {
				total += p.getBody().length;
			}
		}
		return total;
	}

	static int countFrom(List<GroupTrPost> posts, byte[] sender) {
		int n = 0;
		for (GroupTrPost p : posts) {
			if (Arrays.equals(p.getSenderPubKey(), sender)) n++;
		}
		return n;
	}

	List<GroupTrPost> buffered() throws Exception {
		List<GroupTrPost> out = new ArrayList<>();
		for (Object bucket : ((Map<?, ?>) field("futureBuffer")).values()) {
			for (Object list : ((Map<?, ?>) bucket).values()) {
				for (Object item : (List<?>) list) out.add(asPost(item));
			}
		}
		return out;
	}

	int retainedBufferIdentities() throws Exception {
		Object identities = fieldOrNull("bufferedIdentity");
		return identities == null ? 0 : ((Map<?, ?>) identities).size();
	}

	int groupLockCount() throws Exception {
		Object locks = field("groupLocks");
		return locks instanceof Map ? ((Map<?, ?>) locks).size()
				: Array.getLength(locks);
	}

	private static GroupTrPost asPost(Object item) throws Exception {
		if (item instanceof GroupTrPost) return (GroupTrPost) item;
		for (Field f : item.getClass().getDeclaredFields()) {
			if (f.getType() == GroupTrPost.class) {
				f.setAccessible(true);
				return (GroupTrPost) f.get(item);
			}
		}
		throw new AssertionError("no post in " + item.getClass());
	}

	private Object field(String name) throws Exception {
		Object v = fieldOrNull(name);
		if (v == null) throw new AssertionError("no field " + name);
		return v;
	}

	@Nullable
	private Object fieldOrNull(String name) throws Exception {
		try {
			Field f = GroupTrManagerImpl.class.getDeclaredField(name);
			f.setAccessible(true);
			return f.get(manager);
		} catch (NoSuchFieldException e) {
			return null;
		}
	}

	private byte[] token(BdfList list) {
		byte[] t = ByteBuffer.allocate(8).putLong(++tokens).array();
		lists.put(toHexString(t), list);
		return t;
	}

	private Transaction begin(Object readOnly) {
		boolean ro = Boolean.TRUE.equals(readOnly);
		if (txnDepth++ == 0) {
			writeTxn = !ro;
			if (writeTxn) {
				writeTxns++;
				copiesInWriteTxn = 0;
			}
		}
		return new Transaction(null, ro);
	}

	private void end() {
		if (--txnDepth == 0) writeTxn = false;
	}

	@Nullable
	private Object db(String name, Object[] a) throws Exception {
		switch (name) {
			case "transaction": {
				Transaction txn = begin(a[0]);
				try {
					((DbRunnable<?>) a[1]).run(txn);
				} finally {
					end();
				}
				return null;
			}
			case "transactionWithResult": {
				Transaction txn = begin(a[0]);
				try {
					return ((DbCallable<?, ?>) a[1]).call(txn);
				} finally {
					end();
				}
			}
			case "transactionWithNullableResult": {
				Transaction txn = begin(a[0]);
				try {
					return ((NullableDbCallable<?, ?>) a[1]).call(txn);
				} finally {
					end();
				}
			}
			case "removeMessage": {
				boolean found = false;
				for (Map<MessageId, BdfDictionary> h : store.values()) {
					if (h.remove((MessageId) a[1]) != null) found = true;
				}
				if (messages.remove((MessageId) a[1]) != null) found = true;
				deadlines.remove((MessageId) a[1]);
				if (!found) throw new NoSuchMessageException();
				return null;
			}
			case "setCleanupDeadline": {
				MessageId id = (MessageId) a[1];
				if (stored(id) == null && !messages.containsKey(id)) {
					throw new NoSuchMessageException();
				}
				deadlines.merge(id, (Long) a[2], Math::min);
				return null;
			}
			case "setCleanupTimerDuration": {
				MessageId id = (MessageId) a[1];
				if (stored(id) == null && !messages.containsKey(id)) {
					throw new NoSuchMessageException();
				}
				durations.putIfAbsent(id, (Long) a[2]);
				return null;
			}
			case "startCleanupTimer": {
				MessageId id = (MessageId) a[1];
				Long d = durations.get(id);
				if (d == null || deadlines.containsKey(id)) return -1L;
				deadlines.put(id, now + d);
				return now + d;
			}
			default:
				throw new UnsupportedOperationException(name);
		}
	}

	@Nullable
	private Object settings(String name, Object[] a) {
		String ns = (String) a[a.length - 1];
		switch (name) {
			case "getSettings": {
				Settings copy = new Settings();
				Settings s = settings.get(ns);
				if (s != null) copy.putAll(s);
				return copy;
			}
			case "mergeSettings":
				settings.computeIfAbsent(ns, k -> new Settings())
						.putAll((Settings) a[a.length - 2]);
				return null;
			default:
				throw new UnsupportedOperationException(name);
		}
	}

	@Nullable
	private Object client(String name, Object[] a) throws Exception {
		switch (name) {
			case "toByteArray":
				if (a[0] instanceof BdfList) return token((BdfList) a[0]);
				break;
			case "toList":
				if (a.length == 1 && a[0] instanceof byte[]) {
					BdfList l = lists.get(toHexString((byte[]) a[0]));
					if (l == null) throw new FormatException();
					return l;
				}
				if (a.length == 1 && a[0] instanceof Message) {
					BdfList l = lists.get(toHexString(
							((Message) a[0]).getBody()));
					if (l == null) throw new FormatException();
					return l;
				}
				break;
			case "createMessage":
				if (a[2] instanceof byte[]) {
					return new Message(new MessageId(getRandomId()),
							(GroupId) a[0], (Long) a[1], (byte[]) a[2]);
				}
				break;
			case "addLocalMessage": {
				Message m = (Message) a[a.length == 5 ? 1 : 0];
				if (writeTxn) {
					copiesInWriteTxn++;
					maxCopiesPerWriteTxn = Math.max(maxCopiesPerWriteTxn,
							copiesInWriteTxn);
				}
				sent.add(new Sent(contactOf(m.getGroupId()),
						lists.get(toHexString(m.getBody()))));
				messages.put(m.getId(), m);
				BdfDictionary meta = (BdfDictionary) a[a.length == 5 ? 2 : 1];
				if (!meta.isEmpty()) {
					store.computeIfAbsent(m.getGroupId(),
							k -> new LinkedHashMap<>()).put(m.getId(), meta);
				}
				return null;
			}
			case "getMessageMetadataAsDictionary":
				if (a.length == 2 && a[1] instanceof MessageId) {
					MessageId id = (MessageId) a[1];
					BdfDictionary d = stored(id);
					if (d == null) throw new NoSuchMessageException();
					if (unparseableMetadata.contains(id)) {
						throw new FormatException();
					}
					noteRead(id, d);
					if (writeTxn) {
						Object body = d.get("groupCiphertext");
						if (body instanceof byte[]) {
							bodyBytesUnderWriteLock += ((byte[]) body).length;
						}
					}
					if (removeOnRead.remove(id)) {
						for (Map<MessageId, BdfDictionary> h : store.values()) {
							h.remove(id);
						}
						messages.remove(id);
					}
					return d;
				}
				if (a.length == 2 && a[1] instanceof GroupId) {
					Map<MessageId, BdfDictionary> out = new HashMap<>();
					Map<MessageId, BdfDictionary> h = store.get(a[1]);
					if (h != null) out.putAll(h);
					noteRead(out);
					return out;
				}
				break;
			case "getMessageIds":
				queries++;
				return matching((GroupId) a[1], (BdfDictionary) a[2]);
			case "mergeMessageMetadata": {
				BdfDictionary d = stored((MessageId) a[1]);
				if (d == null) throw new NoSuchMessageException();
				for (Map.Entry<String, Object> e
						: ((BdfDictionary) a[2]).entrySet()) {
					if (e.getValue() == BdfDictionary.NULL_VALUE) {
						d.remove(e.getKey());
					} else {
						d.put(e.getKey(), e.getValue());
					}
				}
				return null;
			}
			case "getMessage": {
				MessageId id = (MessageId) a[1];
				Message m = messages.get(id);
				if (m == null) throw new NoSuchMessageException();
				if (deletedBodies.contains(id)) {
					throw new org.zerionproject.core.api.db
							.MessageDeletedException();
				}
				BdfList l = lists.get(toHexString(m.getBody()));
				if (l != null && l.size() > 5 && l.getLong(0) == 32L) {
					noteBodyRead(id, l.getRaw(5).length);
					if (writeTxn) bodyBytesUnderWriteLock += l.getRaw(5).length;
				}
				return m;
			}
			case "getMessageAsList": {
				MessageId id = (MessageId) a[1];
				Message m = messages.get(id);
				if (m == null) throw new NoSuchMessageException();
				if (deletedBodies.contains(id)) {
					throw new org.zerionproject.core.api.db
							.MessageDeletedException();
				}
				BdfList l = lists.get(toHexString(m.getBody()));
				noteBodyRead(id, l.getRaw(5).length);
				if (writeTxn) bodyBytesUnderWriteLock += l.getRaw(5).length;
				return l;
			}
			default:
				break;
		}
		throw new UnsupportedOperationException(name);
	}

	@Nullable
	private BdfDictionary stored(MessageId id) {
		for (Map<MessageId, BdfDictionary> h : store.values()) {
			BdfDictionary d = h.get(id);
			if (d != null) return d;
		}
		return null;
	}

	private void noteRead(MessageId id, BdfDictionary d) {
		Map<MessageId, BdfDictionary> one = new HashMap<>();
		one.put(id, d);
		noteRead(one);
	}

	private void noteBodyRead(MessageId id, long bytes) {
		read.add(id);
		timesRead.merge(id, 1, Integer::sum);
		largestBodyRead = Math.max(largestBodyRead, bytes);
		bodyBytesRead += bytes;
	}

	private void noteRead(Map<MessageId, BdfDictionary> handed) {
		metadataReads++;
		long bytes = 0;
		for (Map.Entry<MessageId, BdfDictionary> e : handed.entrySet()) {
			read.add(e.getKey());
			timesRead.merge(e.getKey(), 1, Integer::sum);
			Object body = e.getValue().get("groupCiphertext");
			if (body instanceof byte[]) bytes += ((byte[]) body).length;
		}
		largestBodyRead = Math.max(largestBodyRead, bytes);
		bodyBytesRead += bytes;
	}

	private Collection<MessageId> matching(GroupId g, BdfDictionary query) {
		List<MessageId> out = new ArrayList<>();
		Map<MessageId, BdfDictionary> h = store.get(g);
		if (h == null) return out;
		for (Map.Entry<MessageId, BdfDictionary> e : h.entrySet()) {
			boolean all = true;
			for (Map.Entry<String, Object> q : query.entrySet()) {
				if (!sameValue(e.getValue().get(q.getKey()), q.getValue())) {
					all = false;
					break;
				}
			}
			if (all) out.add(e.getKey());
		}
		return out;
	}

	private static boolean sameValue(@Nullable Object stored, Object query) {
		if (stored instanceof byte[] && query instanceof byte[]) {
			return Arrays.equals((byte[]) stored, (byte[]) query);
		}
		if (stored instanceof Number && query instanceof Number) {
			return ((Number) stored).longValue()
					== ((Number) query).longValue();
		}
		return query.equals(stored);
	}

	private Group contactGroup(ContactId c) {
		return contactGroups.computeIfAbsent(c,
				k -> new Group(new GroupId(getRandomId()),
						new ClientId("test"), 0, new byte[1]));
	}

	private ContactId contactOf(GroupId g) {
		for (Map.Entry<ContactId, Group> e : contactGroups.entrySet()) {
			if (e.getValue().getId().equals(g)) return e.getKey();
		}
		throw new AssertionError("unknown contact group");
	}

	private static byte[] forgedSig() {
		byte[] sig = new byte[PostQuantumConstants.HYBRID_SIGNATURE_BYTES];
		sig[sig.length - 1] = 1;
		return sig;
	}

	@Nullable
	private Object crypto(String name, Object[] a) throws Exception {
		switch (name) {
			case "hash": {
				MessageDigest d = MessageDigest.getInstance("SHA-256");
				d.update(((String) a[0]).getBytes(StandardCharsets.UTF_8));
				for (byte[] in : (byte[][]) a[1]) d.update(in);
				return d.digest();
			}
			case "verifyHybridSignature":
				signatureChecks++;
				return !Arrays.equals((byte[]) a[0], FORGED_SIG);
			case "hybridSign":
				return SIG.clone();
			default:
				throw new UnsupportedOperationException(name);
		}
	}

	@Nullable
	private Object identity(String name, Object[] a) {
		switch (name) {
			case "getLocalAuthor":
				return local;
			case "getLocalMlDsaSigPublicKey":
				return ML_DSA_PUB;
			case "getLocalMlDsaSigPrivateKey":
				return new byte[PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES];
			default:
				throw new UnsupportedOperationException(name);
		}
	}

	@Nullable
	private Object contacts(String name, Object[] a) throws Exception {
		switch (name) {
			case "getContact": {
				Contact c = contacts.get((ContactId) a[a.length - 1]);
				if (c == null) throw new NoSuchContactException();
				return c;
			}
			case "getContacts":
				return new ArrayList<>(contacts.values());
			default:
				throw new UnsupportedOperationException(name);
		}
	}

	@Nullable
	private Object messaging(String name, Object[] a) {
		if (name.equals("getContactGroup")) {
			return contactGroup(((Contact) a[0]).getId());
		}
		if (name.equals("getContactClientMinorVersion")) {
			Integer v = minorVersions.get((ContactId) a[1]);
			return v == null ? MessagingManager.MINOR_VERSION : v;
		}
		throw new UnsupportedOperationException(name);
	}

	private static <T> T fake(Class<T> type, Handler h) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (proxy, method, args) -> {
					Object[] a = args == null ? new Object[0] : args;
					switch (method.getName()) {
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == a[0];
						case "toString":
							return type.getSimpleName();
						default:
							return h.handle(method.getName(), a);
					}
				}));
	}
}
