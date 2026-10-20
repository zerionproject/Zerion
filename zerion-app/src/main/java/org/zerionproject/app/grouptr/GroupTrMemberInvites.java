package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrIncognitoNames;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberInviteAvailability;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupMemberListSnapshotEvent;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrMemberInviteRecordEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.app.api.grouptr.GroupTrPermissions.GRANT_MAY_ADD_MEMBERS;
import static org.zerionproject.app.api.grouptr.GroupTrPermissions.MEMBERS_MAY_INVITE;
import static org.zerionproject.app.grouptr.GroupTrConstants.MAX_GROUP_MEMBERS;
import static org.zerionproject.app.grouptr.GroupTrConstants.SETTINGS_NS_PREFIX;
import static org.zerionproject.app.grouptr.GroupTrManagerImpl.INVITE_ANSWER_GRACE_MS;
import static org.zerionproject.app.grouptr.GroupTrManagerImpl.INVITE_OFFER_FUTURE_SKEW_MS;
import static org.zerionproject.app.grouptr.GroupTrManagerImpl.INVITE_OFFER_MAX_AGE_MS;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
class GroupTrMemberInvites {

	interface Host {

		@Nullable
		GroupTrState getGroup(byte[] groupId) throws DbException;

		Collection<GroupTrState> getGroups() throws DbException;

		ReentrantLock lockFor(byte[] groupId);

		@Nullable
		byte[] senderPubKey(GroupTrState s, ContactId contactId);

		@Nullable
		byte[] identityOf(ContactId contactId);

		@Nullable
		Contact findContact(Transaction txn, GroupTrState s, byte[] pubKey)
				throws DbException;

		@Nullable
		byte[] keyOf(Transaction txn, GroupTrState s, Contact c)
				throws DbException;

		byte[] localKey(GroupTrState s) throws DbException;

		LocalAuthor localAuthor() throws DbException;

		GroupTrKeys.Binding bind(byte[] groupId, GroupTrKeys.Own own,
				long timestamp) throws DbException;

		byte[] signAsIdentity(String label, byte[] signed) throws DbException;

		GroupTrKeys keys();

		void dispatch(Transaction txn, Contact c, long timestamp, BdfList body)
				throws DbException;

		byte[] sign(GroupTrState s, String label, byte[] signed)
				throws DbException;

		boolean verify(GroupTrState s, byte[] sig, String label, byte[] signed,
				byte[] pubKey);

		@Nullable
		byte[] knownMlDsaKey(byte[] pubKey) throws DbException;

		@Nullable
		byte[] knownMlDsaKey(GroupTrState s, byte[] pubKey) throws DbException;

		byte[] deriveGroupId(String creatorName, byte[] creatorPubKey,
				String name, byte[] salt) throws FormatException;

		byte[] deriveKeyedGroupId(String creatorName, byte[] creatorPubKey,
				byte[] creatorMlDsa, String name, byte[] salt)
				throws FormatException;

		void materialize(GroupTrState s, Settings extra) throws DbException;

		void materializeKeyed(GroupTrState s, Settings extra,
				GroupTrKeys.Own own, ContactId inviter, byte[] inviterIdentity,
				byte[] inviterKey, byte[] inviterMlDsa, long bindingTimestamp)
				throws DbException;

		void storeBinding(byte[] groupId, ContactId contactId,
				byte[] identity, GroupTrKeys.Binding b) throws DbException;

		void removeFromDevice(byte[] groupId) throws DbException;

		void addMember(byte[] groupId, byte[] pubKey, String name,
				byte[] mlDsaPubKey) throws DbException;

		void sendMemberListSnapshot(byte[] groupId) throws DbException;

		void setIncognitoName(byte[] groupId, String name) throws DbException;
	}

	static final String LABEL_PERMISSIONS =
			"org.zerionproject/GROUP_PERMISSIONS";
	static final String LABEL_INVITER_GRANT =
			"org.zerionproject/GROUP_INVITER_GRANT";
	static final String LABEL_OFFER =
			"org.zerionproject/GROUPTR_MEMBER_INVITE_OFFER";
	static final String LABEL_GRANTED_OFFER =
			"org.zerionproject/GROUPTR_GRANTED_INVITE_OFFER";
	static final String LABEL_ACCEPT =
			"org.zerionproject/GROUPTR_MEMBER_INVITE_ACCEPT";
	static final String LABEL_DECLINE =
			"org.zerionproject/GROUPTR_MEMBER_INVITE_DECLINE";
	static final String LABEL_JOIN_REQUEST =
			"org.zerionproject/GROUPTR_JOIN_REQUEST";
	private static final String LABEL_SNAPSHOT =
			"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT";

	static final String SETTINGS_NS_MEMBER_INVITES_PREFIX = "grouptr.mi.";
	static final String SETTINGS_NS_MEMBER_OFFERS_PENDING =
			"grouptr.member_offers_pending";
	static final String S_JOINING_VIA = "memberInviteJoiningVia";
	static final String S_JOINED_THROUGH_MEMBER = "memberInviteJoined";

	static final String K_PERM_FLAGS = "permFlags";
	static final String K_PERM_TIMESTAMP = "permTimestamp";
	static final String K_PERM_SIG = "permSig";
	static final String K_SENT_PREFIX = "sent.";
	static final String K_JOINS_SEEN = "joinsSeen";
	static final String K_JOIN_RATE = "joinRate";
	static final String K_ADMITTED_PREFIX = "admitted.";
	static final String K_FORWARDED_EPOCH = "forwardedEpoch";
	static final String K_FORWARDED_SECRET_PREFIX = "forwardedSecret.";
	static final String K_FORWARDED_SECRET_ID_PREFIX = "forwardedSecretId.";
	static final String K_REMOVED = "removed";
	static final String K_REMOVED_TAGS = "removedTags";
	static final String K_MEMBER_TAG_PREFIX = "memberTag.";
	static final String K_INVITER_PREFIX = "inviter.";
	static final String K_GRANT_PREFIX = "grant.";

	static final int MAX_PENDING_OFFERS = 64;
	static final int MAX_OPEN_INVITES_PER_GROUP = 64;
	static final int MAX_JOINS_PER_INVITER_PER_DAY = 20;
	static final int MAX_JOINS_REMEMBERED = 512;
	static final int MAX_ADMITTED_PER_GROUP = 256;
	static final int MAX_REMOVED_REMEMBERED = 256;
	static final long DAY_MS = 24L * 60L * 60L * 1000L;
	static final long JOIN_WINDOW_MS =
			INVITE_OFFER_MAX_AGE_MS + INVITE_ANSWER_GRACE_MS;
	static final long PERMISSIONS_MAX_AGE_MS = JOIN_WINDOW_MS;
	static final long PERMISSIONS_REFRESH_MS = INVITE_OFFER_MAX_AGE_MS;
	private static final byte[] NO_INVITEE = new byte[32];
	private static final byte[] NO_KEY = new byte[0];

	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final ClientHelper clientHelper;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;
	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final EventBus eventBus;
	private final Clock clock;
	private final Host host;
	private final Object offersLock = new Object();

	GroupTrMemberInvites(DatabaseComponent db,
			SettingsManager settingsManager, ClientHelper clientHelper,
			CryptoComponent crypto, IdentityManager identityManager,
			ContactManager contactManager, MessagingManager messagingManager,
			EventBus eventBus, Clock clock, Host host) {
		this.db = db;
		this.settingsManager = settingsManager;
		this.clientHelper = clientHelper;
		this.crypto = crypto;
		this.identityManager = identityManager;
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
		this.eventBus = eventBus;
		this.clock = clock;
		this.host = host;
	}

	private static final class Permissions {

		private final long flags;
		private final long timestamp;
		private final byte[] sig;

		private Permissions(long flags, long timestamp, byte[] sig) {
			this.flags = flags;
			this.timestamp = timestamp;
			this.sig = sig;
		}

		private boolean membersMayInvite() {
			return (flags & MEMBERS_MAY_INVITE) != 0L;
		}

		private boolean mayAddMembers() {
			return (flags & GRANT_MAY_ADD_MEMBERS) != 0L;
		}
	}

	private static final class SentInvite {

		private final int contactId;
		private final long offered;
		private final long accepted;

		private SentInvite(int contactId, long offered, long accepted) {
			this.contactId = contactId;
			this.offered = offered;
			this.accepted = accepted;
		}
	}

	private static final class PendingOffer {

		private final String groupName;
		private final byte[] salt;
		private final String creatorName;
		private final byte[] creatorPubKey;
		private final byte[] creatorMlDsaPubKey;
		private final byte[] inviterPubKey;
		private final ContactId contactId;
		private final long timestamp;
		@Nullable
		private final byte[] inviterMlDsaPubKey;
		@Nullable
		private final byte[] inviterIdentity;
		private final long bindingTimestamp;

		private PendingOffer(String groupName, byte[] salt,
				String creatorName, byte[] creatorPubKey,
				byte[] creatorMlDsaPubKey, byte[] inviterPubKey,
				ContactId contactId, long timestamp,
				@Nullable byte[] inviterMlDsaPubKey,
				@Nullable byte[] inviterIdentity, long bindingTimestamp) {
			this.groupName = groupName;
			this.salt = salt;
			this.creatorName = creatorName;
			this.creatorPubKey = creatorPubKey;
			this.creatorMlDsaPubKey = creatorMlDsaPubKey;
			this.inviterPubKey = inviterPubKey;
			this.contactId = contactId;
			this.timestamp = timestamp;
			this.inviterMlDsaPubKey = inviterMlDsaPubKey;
			this.inviterIdentity = inviterIdentity;
			this.bindingTimestamp = bindingTimestamp;
		}

		private boolean keyed() {
			return inviterMlDsaPubKey != null && inviterIdentity != null;
		}
	}

	static byte[] permissionsSignedInput(byte[] groupId, long flags,
			long timestamp) {
		ByteBuffer b = ByteBuffer.allocate(32 + 8 + 8 + 1);
		b.put(groupId);
		b.putLong(flags);
		b.putLong(timestamp);
		b.put((byte) 0x09);
		return b.array();
	}

	static byte[] grantSignedInput(byte[] groupId, byte[] memberPubKey,
			long flags, long timestamp) {
		ByteBuffer b = ByteBuffer.allocate(32 + 32 + 8 + 8 + 1);
		b.put(groupId);
		b.put(memberPubKey);
		b.putLong(flags);
		b.putLong(timestamp);
		b.put((byte) 0x0A);
		return b.array();
	}

	static byte[] offerSignedInput(byte[] groupId, byte[] inviterPubKey,
			byte[] inviteePubKey, byte[] creatorPubKey, long timestamp,
			String groupName, byte[] salt, String creatorName,
			byte[] creatorMlDsaPubKey, long permFlags, long permTimestamp,
			byte[] permSig) {
		byte[] gn = groupName.getBytes(StandardCharsets.UTF_8);
		byte[] cn = creatorName.getBytes(StandardCharsets.UTF_8);
		ByteBuffer b = ByteBuffer.allocate(32 * 4 + 8
				+ 4 + gn.length + 4 + salt.length + 4 + cn.length
				+ 4 + creatorMlDsaPubKey.length + 8 + 8 + 4 + permSig.length);
		b.put(groupId);
		b.put(inviterPubKey);
		b.put(inviteePubKey);
		b.put(creatorPubKey);
		b.putLong(timestamp);
		putPrefixed(b, gn);
		putPrefixed(b, salt);
		putPrefixed(b, cn);
		putPrefixed(b, creatorMlDsaPubKey);
		b.putLong(permFlags);
		b.putLong(permTimestamp);
		putPrefixed(b, permSig);
		return b.array();
	}

	static byte[] responseSignedInput(byte[] groupId, byte[] inviteePubKey,
			byte[] inviterPubKey, byte[] creatorPubKey, long timestamp,
			byte[] inviteeMlDsaPubKey) {
		ByteBuffer b = ByteBuffer.allocate(32 * 4 + 8 + 4
				+ inviteeMlDsaPubKey.length);
		b.put(groupId);
		b.put(inviteePubKey);
		b.put(inviterPubKey);
		b.put(creatorPubKey);
		b.putLong(timestamp);
		putPrefixed(b, inviteeMlDsaPubKey);
		return b.array();
	}

	static byte[] responseSignedInput(byte[] groupId, byte[] inviteePubKey,
			byte[] inviterPubKey, byte[] creatorPubKey, long timestamp,
			byte[] inviteeMlDsaPubKey, @Nullable byte[] tag) {
		byte[] base = responseSignedInput(groupId, inviteePubKey,
				inviterPubKey, creatorPubKey, timestamp, inviteeMlDsaPubKey);
		if (tag == null) return base;
		ByteBuffer b = ByteBuffer.allocate(base.length + 4 + tag.length);
		b.put(base);
		putPrefixed(b, tag);
		return b.array();
	}

	static byte[] joinRequestSignedInput(byte[] groupId, byte[] inviterPubKey,
			byte[] inviteePubKey, long acceptTimestamp, long timestamp,
			String inviteeName, byte[] inviteeMlDsaPubKey, byte[] acceptSig,
			@Nullable byte[] tag) {
		byte[] base = joinRequestSignedInput(groupId, inviterPubKey,
				inviteePubKey, acceptTimestamp, timestamp, inviteeName,
				inviteeMlDsaPubKey, acceptSig);
		if (tag == null) return base;
		ByteBuffer b = ByteBuffer.allocate(base.length + 4 + tag.length);
		b.put(base);
		putPrefixed(b, tag);
		return b.array();
	}

	static byte[] joinRequestSignedInput(byte[] groupId, byte[] inviterPubKey,
			byte[] inviteePubKey, long acceptTimestamp, long timestamp,
			String inviteeName, byte[] inviteeMlDsaPubKey, byte[] acceptSig) {
		byte[] name = inviteeName.getBytes(StandardCharsets.UTF_8);
		ByteBuffer b = ByteBuffer.allocate(32 * 3 + 8 + 8 + 4 + name.length
				+ 4 + inviteeMlDsaPubKey.length + 4 + acceptSig.length);
		b.put(groupId);
		b.put(inviterPubKey);
		b.put(inviteePubKey);
		b.putLong(acceptTimestamp);
		b.putLong(timestamp);
		putPrefixed(b, name);
		putPrefixed(b, inviteeMlDsaPubKey);
		putPrefixed(b, acceptSig);
		return b.array();
	}

	private static void putPrefixed(ByteBuffer b, byte[] field) {
		b.putInt(field.length);
		b.put(field);
	}

	static boolean joinRequestTimely(long now, long timestamp) {
		if (timestamp < 0) return false;
		if (timestamp > now + INVITE_OFFER_FUTURE_SKEW_MS) return false;
		return timestamp >= now - JOIN_WINDOW_MS;
	}

	static boolean permissionsTimely(long now, long timestamp) {
		if (timestamp < 0) return false;
		if (timestamp > now + INVITE_OFFER_FUTURE_SKEW_MS) return false;
		return timestamp >= now - PERMISSIONS_MAX_AGE_MS;
	}

	void onRecord(GroupTrMemberInviteRecordEvent e) {
		try {
			switch (e.getMessageType()) {
				case MessageTypes.GROUP_PERMISSIONS:
					handlePermissions(e);
					break;
				case MessageTypes.GROUP_INVITER_GRANT:
					handleGrant(e);
					break;
				case MessageTypes.GROUPTR_MEMBER_INVITE_OFFER:
				case MessageTypes.GROUPTR_GRANTED_INVITE_OFFER:
					handleOffer(e);
					break;
				case MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT:
				case MessageTypes.GROUPTR_MEMBER_INVITE_DECLINE:
				case MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO:
					handleResponse(e);
					break;
				case MessageTypes.GROUPTR_JOIN_REQUEST:
					handleJoinRequest(e);
					break;
				default:
					break;
			}
		} catch (DbException | FormatException ex) {
		}
	}

	long getPermissions(byte[] groupId) throws DbException {
		Permissions p = loadPermissions(groupId);
		return p == null ? 0L : p.flags;
	}

	int setMembersMayInvite(byte[] groupId, boolean allowed)
			throws DbException {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireLive(groupId);
			byte[] localPub = host.localKey(s);
			if (!Arrays.equals(localPub, s.getCreatorPubKey())) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_CREATOR);
			}
			return publishPermissions(s, allowed ? MEMBERS_MAY_INVITE : 0L);
		} finally {
			lock.unlock();
		}
	}

	private int publishPermissions(GroupTrState s, long flags)
			throws DbException {
		byte[] groupId = s.getGroupId();
		byte[] localPub = host.localKey(s);
		Permissions current = loadPermissions(groupId);
		long now = clock.currentTimeMillis();
		long ts = current == null ? now
				: Math.max(now, current.timestamp + 1);
		byte[] sig = host.sign(s, LABEL_PERMISSIONS,
				permissionsSignedInput(groupId, flags, ts));
		Permissions next = new Permissions(flags, ts, sig);
		List<byte[]> to = new ArrayList<>();
		for (GroupTrMember m : s.getMembers()) {
			if (!Arrays.equals(m.getPubKey(), localPub)) {
				to.add(m.getPubKey());
			}
		}
		int skipped = db.transactionWithResult(false, txn -> {
			storePermissions(txn, groupId, next);
			return sendPermissions(txn, s, next, to, now);
		});
		announceUpdated(groupId);
		return skipped;
	}

	boolean hasGrant(byte[] groupId, byte[] memberPubKey) throws DbException {
		Permissions g = loadGrant(groupId, memberPubKey);
		return g != null && g.mayAddMembers();
	}

	boolean canReceiveGrant(byte[] groupId, byte[] memberPubKey)
			throws DbException {
		GroupTrState s = host.getGroup(groupId);
		if (s == null || s.isDissolved()) return false;
		if (Arrays.equals(memberPubKey, s.getCreatorPubKey())) return false;
		if (!listed(s, memberPubKey)) return false;
		return db.transactionWithResult(true, txn -> {
			Contact c = host.findContact(txn, s, memberPubKey);
			return c != null && supportsGrants(txn, c);
		});
	}

	int setGrant(byte[] groupId, byte[] memberPubKey, boolean granted)
			throws DbException {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireLive(groupId);
			byte[] localPub = host.localKey(s);
			if (!Arrays.equals(localPub, s.getCreatorPubKey())) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_CREATOR);
			}
			if (Arrays.equals(memberPubKey, localPub)
					|| !listed(s, memberPubKey)) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.CONTACT_NOT_FOUND);
			}
			if (!granted && loadGrant(groupId, memberPubKey) == null) {
				return 0;
			}
			return publishGrant(s, memberPubKey,
					granted ? GRANT_MAY_ADD_MEMBERS : 0L);
		} finally {
			lock.unlock();
		}
	}

	private int publishGrant(GroupTrState s, byte[] memberPubKey, long flags)
			throws DbException {
		byte[] groupId = s.getGroupId();
		Permissions current = loadGrant(groupId, memberPubKey);
		long now = clock.currentTimeMillis();
		long ts = current == null ? now
				: Math.max(now, current.timestamp + 1);
		byte[] sig = host.sign(s, LABEL_INVITER_GRANT,
				grantSignedInput(groupId, memberPubKey, flags, ts));
		Permissions next = new Permissions(flags, ts, sig);
		int skipped = db.transactionWithResult(false, txn -> {
			storeGrant(txn, groupId, memberPubKey, next);
			return sendGrant(txn, s, memberPubKey, next, now) ? 0 : 1;
		});
		announceUpdated(groupId);
		return skipped;
	}

	private boolean sendGrant(Transaction txn, GroupTrState s,
			byte[] memberPubKey, Permissions g, long timestamp)
			throws DbException {
		byte[] groupId = s.getGroupId();
		Contact c = host.findContact(txn, s, memberPubKey);
		if (c == null || !supportsGrants(txn, c)) return false;
		host.dispatch(txn, c, timestamp, BdfList.of(
				(long) MessageTypes.GROUP_INVITER_GRANT, groupId, memberPubKey,
				g.flags, g.timestamp, g.sig));
		return true;
	}

	private void refreshGrantsIfDue(GroupTrState s) throws DbException {
		long now = clock.currentTimeMillis();
		for (Map.Entry<String, Permissions> e
				: loadGrants(s.getGroupId()).entrySet()) {
			Permissions g = e.getValue();
			if (!g.mayAddMembers()) continue;
			if (g.timestamp > now || now - g.timestamp < PERMISSIONS_REFRESH_MS) {
				continue;
			}
			byte[] memberPub;
			try {
				memberPub = fromHexString(e.getKey());
			} catch (FormatException ex) {
				continue;
			}
			if (!listed(s, memberPub)) continue;
			publishGrant(s, memberPub, g.flags);
		}
	}

	void forgetGrant(byte[] groupId, byte[] memberPubKey) throws DbException {
		settingsManager.deleteSettings(nsOf(groupId), Collections
				.singletonList(K_GRANT_PREFIX + toHexString(memberPubKey)));
	}

	private void handleGrant(GroupTrMemberInviteRecordEvent e)
			throws DbException, FormatException {
		BdfList r = e.getRecord();
		byte[] groupId = r.getRaw(1);
		byte[] memberPub = r.getRaw(2);
		long flags = r.getLong(3);
		long ts = r.getLong(4);
		byte[] sig = r.getRaw(5);
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.getGroup(groupId);
			if (s == null || s.isDissolved()) return;
			byte[] sender = host.senderPubKey(s, e.getContactId());
			if (sender == null
					|| !Arrays.equals(sender, s.getCreatorPubKey())) {
				return;
			}
			byte[] localPub = host.localKey(s);
			if (!Arrays.equals(memberPub, localPub)
					|| Arrays.equals(localPub, s.getCreatorPubKey())) {
				return;
			}
			Permissions current = loadGrant(groupId, memberPub);
			if (current != null && ts <= current.timestamp) return;
			if (!host.verify(s, sig, LABEL_INVITER_GRANT,
					grantSignedInput(groupId, memberPub, flags, ts),
					s.getCreatorPubKey())) {
				return;
			}
			Permissions next = new Permissions(flags, ts, sig);
			db.transaction(false, txn ->
					storeGrant(txn, groupId, memberPub, next));
			announceUpdated(groupId);
		} finally {
			lock.unlock();
		}
	}

	private boolean mayInvite(byte[] groupId, byte[] memberPubKey)
			throws DbException {
		Permissions p = loadPermissions(groupId);
		if (p != null && p.membersMayInvite()) return true;
		return hasGrant(groupId, memberPubKey);
	}

	void refreshPermissions(GroupTrState s) throws DbException {
		if (s.isDissolved()) return;
		ReentrantLock lock = host.lockFor(s.getGroupId());
		lock.lock();
		try {
			refreshPermissionsIfDue(s);
			if (Arrays.equals(host.localKey(s), s.getCreatorPubKey())) {
				refreshGrantsIfDue(s);
			}
		} finally {
			lock.unlock();
		}
	}

	void refreshDuePermissions() {
		try {
			for (GroupTrState s : host.getGroups()) refreshPermissions(s);
		} catch (DbException ex) {
		}
	}

	private boolean refreshPermissionsIfDue(GroupTrState s)
			throws DbException {
		Permissions p = loadPermissions(s.getGroupId());
		if (p == null || !p.membersMayInvite()) return false;
		long now = clock.currentTimeMillis();
		if (p.timestamp > now || now - p.timestamp < PERMISSIONS_REFRESH_MS) {
			return false;
		}
		if (!Arrays.equals(host.localKey(s), s.getCreatorPubKey())) {
			return false;
		}
		publishPermissions(s, p.flags);
		return true;
	}

	void onMemberAdded(GroupTrState s, byte[] addedPubKey)
			throws DbException {
		forgetRemoved(s.getGroupId(), addedPubKey);
		if (Arrays.equals(host.localKey(s), s.getCreatorPubKey())) {
			refreshGrantsIfDue(s);
		}
		if (refreshPermissionsIfDue(s)) return;
		Permissions p = loadPermissions(s.getGroupId());
		if (p == null) return;
		long now = clock.currentTimeMillis();
		db.transaction(false, txn -> sendPermissions(txn, s, p,
				Collections.singletonList(addedPubKey), now));
	}

	void onClientVersionUpdated(ContactId contactId, int minorVersion) {
		if (minorVersion < MessagingManager.GROUP_ACTIONS_MIN_VERSION) return;
		try {
			for (GroupTrState s : host.getGroups()) {
				if (s.isDissolved()) continue;
				byte[] localPub = host.localKey(s);
				if (!Arrays.equals(s.getCreatorPubKey(), localPub)) continue;
				byte[] memberPub = host.senderPubKey(s, contactId);
				if (memberPub == null || !listed(s, memberPub)) continue;
				ReentrantLock lock = host.lockFor(s.getGroupId());
				lock.lock();
				try {
					long now = clock.currentTimeMillis();
					Permissions g = loadGrant(s.getGroupId(), memberPub);
					if (g != null && minorVersion
							>= MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION) {
						db.transaction(false, txn -> sendGrant(txn, s,
								memberPub, g, now));
					}
					Permissions p = loadPermissions(s.getGroupId());
					if (p == null) continue;
					db.transaction(false, txn -> sendPermissions(txn, s, p,
							Collections.singletonList(memberPub), now));
				} finally {
					lock.unlock();
				}
			}
		} catch (DbException ex) {
		}
	}

	void onGroupRemoved(byte[] groupId) {
		try {
			settingsManager.deleteNamespaces(
					Collections.singletonList(nsOf(groupId)));
		} catch (DbException ex) {
		}
	}

	MemberInviteAvailability getAvailability(byte[] groupId)
			throws DbException {
		GroupTrState s = host.getGroup(groupId);
		if (s == null) return MemberInviteAvailability.UNAVAILABLE;
		return availability(s, host.localKey(s));
	}

	private MemberInviteAvailability availability(GroupTrState s,
			byte[] localPub) throws DbException {
		if (s.isDissolved()) return MemberInviteAvailability.UNAVAILABLE;
		if (Arrays.equals(localPub, s.getCreatorPubKey())) {
			return MemberInviteAvailability.UNAVAILABLE;
		}
		if (!listed(s, localPub) || isWaiting(s, localPub)) {
			return MemberInviteAvailability.UNAVAILABLE;
		}
		if (!mayInvite(s.getGroupId(), localPub)) {
			return MemberInviteAvailability.NOT_ENABLED;
		}
		return db.transactionWithResult(true, txn -> {
			Contact creator = host.findContact(txn, s, s.getCreatorPubKey());
			if (creator == null) {
				return MemberInviteAvailability.CREATOR_NOT_A_CONTACT;
			}
			if (!supports(txn, creator)) {
				return MemberInviteAvailability.CREATOR_ON_OLDER_VERSION;
			}
			return MemberInviteAvailability.ALLOWED;
		});
	}

	boolean canReceive(ContactId contactId) throws DbException {
		return db.transactionWithResult(true, txn ->
				messagingManager.getContactClientMinorVersion(txn, contactId)
						>= MessagingManager.GROUP_ACTIONS_MIN_VERSION);
	}

	boolean canReceive(byte[] groupId, ContactId contactId)
			throws DbException {
		int proven = groupWideProof(groupId, clock.currentTimeMillis()) != null
				? MessagingManager.GROUP_ACTIONS_MIN_VERSION
				: MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION;
		GroupTrState s = host.getGroup(groupId);
		int needed = s != null && s.usesGroupKeys()
				? Math.max(proven,
				MessagingManager.GROUP_MEMBER_KEYS_MIN_VERSION) : proven;
		return db.transactionWithResult(true, txn ->
				messagingManager.getContactClientMinorVersion(txn, contactId)
						>= needed);
	}

	@Nullable
	private Permissions groupWideProof(byte[] groupId, long now)
			throws DbException {
		Permissions p = loadPermissions(groupId);
		if (p == null || !p.membersMayInvite()) return null;
		return permissionsTimely(now, p.timestamp) ? p : null;
	}

	@Nullable
	private Permissions grantProof(byte[] groupId, byte[] memberPubKey,
			long now) throws DbException {
		Permissions g = loadGrant(groupId, memberPubKey);
		if (g == null || !g.mayAddMembers()) return null;
		return permissionsTimely(now, g.timestamp) ? g : null;
	}

	boolean canAcceptIncognito(byte[] groupId) throws DbException {
		PendingOffer o = loadPendingOffer(groupId);
		return o != null && o.keyed();
	}

	boolean isWaitingForAdmin(byte[] groupId) throws DbException {
		GroupTrState s = host.getGroup(groupId);
		if (s == null) return false;
		if (expired(s)) {
			host.removeFromDevice(groupId);
			return false;
		}
		return isWaiting(s, host.localKey(s));
	}

	void expireUnconfirmedJoins() {
		try {
			for (GroupTrState s : host.getGroups()) {
				if (expired(s)) host.removeFromDevice(s.getGroupId());
			}
		} catch (DbException ex) {
		}
	}

	private boolean expired(GroupTrState s) throws DbException {
		return isWaiting(s, host.localKey(s))
				&& clock.currentTimeMillis() - s.getCreated() > JOIN_WINDOW_MS;
	}

	boolean isWaiting(GroupTrState s, byte[] localPub) throws DbException {
		if (joiningVia(s.getGroupId()) == null) return false;
		return s.getEpoch() == 0L || !listed(s, localPub);
	}

	boolean isConfirmedMember(GroupTrState s, byte[] localPub)
			throws DbException {
		return s.getEpoch() > 0L && listed(s, localPub)
				&& !isWaiting(s, localPub);
	}

	void endJoining(byte[] groupId) throws DbException {
		if (joiningVia(groupId) == null) return;
		settingsManager.deleteSettings(SETTINGS_NS_PREFIX
				+ toHexString(groupId), Collections.singletonList(S_JOINING_VIA));
		announceUpdated(groupId);
	}

	boolean joinedThroughMember(byte[] groupId) throws DbException {
		Settings state = settingsManager.getSettings(
				SETTINGS_NS_PREFIX + toHexString(groupId));
		String via = state.get(S_JOINING_VIA);
		if (via != null && !via.isEmpty()) return true;
		return state.getBoolean(S_JOINED_THROUGH_MEMBER, false);
	}

	private boolean unconfirmedJoining(GroupTrState s) throws DbException {
		return joiningVia(s.getGroupId()) != null && s.getEpoch() == 0L;
	}

	@Nullable
	private String joiningVia(byte[] groupId) throws DbException {
		String via = settingsManager.getSettings(
				SETTINGS_NS_PREFIX + toHexString(groupId)).get(S_JOINING_VIA);
		return via == null || via.isEmpty() ? null : via;
	}

	void invite(byte[] groupId, ContactId contactId) throws DbException {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = requireLive(groupId);
			byte[] localPub = host.localKey(s);
			if (availability(s, localPub)
					!= MemberInviteAvailability.ALLOWED) {
				throw unavailable();
			}
			long now = clock.currentTimeMillis();
			Permissions groupWide = groupWideProof(groupId, now);
			Permissions p = groupWide != null ? groupWide
					: grantProof(groupId, localPub, now);
			if (p == null) throw unavailable();
			boolean granted = groupWide == null;
			if (s.getMembers().size() >= MAX_GROUP_MEMBERS) {
				throw unavailable();
			}
			Contact invitee;
			try {
				invitee = db.transactionWithResult(true,
						txn -> contactManager.getContact(txn, contactId));
			} catch (NoSuchContactException ex) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.CONTACT_NOT_FOUND);
			}
			byte[] identity = invitee.getAuthor().getPublicKey().getEncoded();
			boolean keyed = s.usesGroupKeys();
			if (keyed && db.transactionWithResult(true, txn ->
					messagingManager.getContactClientMinorVersion(txn,
							contactId))
					< MessagingManager.GROUP_MEMBER_KEYS_MIN_VERSION) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.CONTACT_MUST_UPDATE);
			}
			byte[] inviteePub = keyed ? db.transactionWithNullableResult(true,
					txn -> host.keyOf(txn, s, invitee)) : identity;
			if (inviteePub != null && (Arrays.equals(inviteePub, localPub)
					|| Arrays.equals(inviteePub, s.getCreatorPubKey())
					|| listed(s, inviteePub))) {
				return;
			}
			if (keyed && ((inviteePub != null
					&& removedByCreator(groupId, inviteePub))
					|| removedTag(groupId, host.keys().removalTag(groupId,
					identity)))) {
				throw unavailable();
			}
			if (!canReceive(groupId, contactId)) throw unavailable();
			byte[] creatorMlDsa = keyed
					? host.knownMlDsaKey(s, s.getCreatorPubKey())
					: contactMlDsaKey(s, s.getCreatorPubKey());
			if (creatorMlDsa == null) throw unavailable();
			GroupTrKeys.Binding offered = null;
			if (keyed) {
				GroupTrKeys.Own own = host.keys().own(groupId);
				if (own == null) throw unavailable();
				try {
					offered = host.bind(groupId, own, now);
				} finally {
					own.clear();
				}
			}
			Map<String, SentInvite> sent = loadSent(groupId);
			List<String> expired = new ArrayList<>();
			int open = 0;
			for (Map.Entry<String, SentInvite> e : sent.entrySet()) {
				if (GroupTrManagerImpl.sentInviteOpen(now,
						e.getValue().offered)) {
					open++;
				} else {
					expired.add(K_SENT_PREFIX + e.getKey());
				}
			}
			String inviteeHex = toHexString(identity);
			if (open >= MAX_OPEN_INVITES_PER_GROUP
					&& !sent.containsKey(inviteeHex)) {
				throw unavailable();
			}
			byte[] signed = offerSignedInput(groupId, localPub,
					keyed ? NO_INVITEE : identity, s.getCreatorPubKey(), now,
					s.getName(), s.getSalt(), s.getCreatorName(), creatorMlDsa,
					p.flags, p.timestamp, p.sig);
			byte[] sig = host.sign(s, granted ? LABEL_GRANTED_OFFER
					: LABEL_OFFER, signed);
			BdfList body = BdfList.of((long) (granted
							? MessageTypes.GROUPTR_GRANTED_INVITE_OFFER
							: MessageTypes.GROUPTR_MEMBER_INVITE_OFFER), groupId,
					s.getName(), s.getSalt(), s.getCreatorName(),
					s.getCreatorPubKey(), creatorMlDsa, localPub, p.flags,
					p.timestamp, p.sig, now, sig);
			if (offered != null) body.add(offered.toList());
			db.transaction(false, txn -> {
				host.dispatch(txn, invitee, now, body);
				settingsManager.deleteSettings(txn, nsOf(groupId), expired);
				storeSent(txn, groupId, inviteeHex,
						new SentInvite(contactId.getInt(), now, -1L));
			});
		} finally {
			lock.unlock();
		}
	}

	private void handlePermissions(GroupTrMemberInviteRecordEvent e)
			throws DbException, FormatException {
		BdfList r = e.getRecord();
		byte[] groupId = r.getRaw(1);
		long flags = r.getLong(2);
		long ts = r.getLong(3);
		byte[] sig = r.getRaw(4);
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.getGroup(groupId);
			if (s == null || s.isDissolved()) return;
			byte[] sender = host.senderPubKey(s, e.getContactId());
			if (sender == null
					|| !Arrays.equals(sender, s.getCreatorPubKey())) {
				return;
			}
			Permissions current = loadPermissions(groupId);
			if (current != null && ts <= current.timestamp) return;
			if (!host.verify(s, sig, LABEL_PERMISSIONS,
					permissionsSignedInput(groupId, flags, ts),
					s.getCreatorPubKey())) {
				return;
			}
			Permissions next = new Permissions(flags, ts, sig);
			db.transaction(false, txn -> storePermissions(txn, groupId, next));
			announceUpdated(groupId);
		} finally {
			lock.unlock();
		}
	}

	private void handleOffer(GroupTrMemberInviteRecordEvent e)
			throws DbException, FormatException {
		BdfList r = e.getRecord();
		byte[] groupId = r.getRaw(1);
		String groupName = r.getString(2);
		byte[] salt = r.getRaw(3);
		String creatorName = r.getString(4);
		byte[] creatorPub = r.getRaw(5);
		byte[] creatorMlDsa = r.getRaw(6);
		byte[] inviterPub = r.getRaw(7);
		long permFlags = r.getLong(8);
		long permTs = r.getLong(9);
		byte[] permSig = r.getRaw(10);
		long ts = r.getLong(11);
		byte[] sig = r.getRaw(12);
		GroupTrKeys.Binding binding = r.size() > 13
				? GroupTrKeys.Binding.parse(r.getList(13)) : null;
		boolean keyed = binding != null;
		boolean granted = e.getMessageType()
				== MessageTypes.GROUPTR_GRANTED_INVITE_OFFER;
		long now = clock.currentTimeMillis();
		if (!GroupTrManagerImpl.inviteOfferTimely(now, ts)) return;
		Contact inviter = contactOrNull(e.getContactId());
		if (inviter == null) return;
		byte[] inviterIdentity =
				inviter.getAuthor().getPublicKey().getEncoded();
		byte[] inviterMlDsa;
		if (binding != null) {
			if (!Arrays.equals(binding.pub, inviterPub)) return;
			if (!host.keys().verify(groupId, binding, inviterIdentity,
					inviter.getMlDsaSigPublicKey())) {
				return;
			}
			inviterMlDsa = binding.mlDsa;
		} else {
			if (!Arrays.equals(inviterIdentity, inviterPub)) return;
			inviterMlDsa = host.knownMlDsaKey(inviterPub);
			if (inviterMlDsa == null) return;
		}
		if (Arrays.equals(inviterPub, creatorPub)) return;
		long needed = granted ? GRANT_MAY_ADD_MEMBERS : MEMBERS_MAY_INVITE;
		if ((permFlags & needed) == 0L) return;
		if (!permissionsTimely(now, permTs)) return;
		byte[] derived = keyed
				? host.deriveKeyedGroupId(creatorName, creatorPub,
				creatorMlDsa, groupName, salt)
				: host.deriveGroupId(creatorName, creatorPub, groupName, salt);
		if (!Arrays.equals(derived, groupId)) return;
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState existing = host.getGroup(groupId);
			if (existing != null && existing.usesGroupKeys() != keyed) return;
			byte[] localPub = keyed
					? (existing == null ? NO_KEY : host.localKey(existing))
					: host.localAuthor().getPublicKey().getEncoded();
			if (Arrays.equals(localPub, creatorPub)
					|| Arrays.equals(localPub, inviterPub)) {
				return;
			}
			byte[] known = keyed
					? (existing == null ? null
					: host.knownMlDsaKey(existing, creatorPub))
					: host.knownMlDsaKey(creatorPub);
			if (known != null && !Arrays.equals(known, creatorMlDsa)) return;
			if (!admissibleOver(existing, localPub)) return;
			if (loadPendingOffer(groupId) != null) return;
			if (!verifyWith(sig, granted ? LABEL_GRANTED_OFFER : LABEL_OFFER,
					offerSignedInput(groupId, inviterPub,
							keyed ? NO_INVITEE : localPub, creatorPub, ts,
							groupName, salt, creatorName, creatorMlDsa,
							permFlags, permTs, permSig), inviterPub,
					inviterMlDsa)) {
				return;
			}
			boolean proven = granted
					? verifyWith(permSig, LABEL_INVITER_GRANT,
							grantSignedInput(groupId, inviterPub, permFlags,
									permTs), creatorPub, creatorMlDsa)
					: verifyWith(permSig, LABEL_PERMISSIONS,
							permissionsSignedInput(groupId, permFlags, permTs),
							creatorPub, creatorMlDsa);
			if (!proven) return;
			storePendingOffer(groupId, new PendingOffer(groupName, salt,
					creatorName, creatorPub, creatorMlDsa, inviterPub,
					e.getContactId(), ts, keyed ? inviterMlDsa : null,
					keyed ? inviterIdentity : null,
					binding == null ? 0L : binding.timestamp));
		} finally {
			lock.unlock();
		}
	}

	private boolean admissibleOver(@Nullable GroupTrState existing,
			byte[] localPub) throws DbException {
		if (existing == null || existing.isDissolved()) return true;
		if (!listed(existing, localPub)) return true;
		return GroupTrManagerImpl.inBootstrapState(existing, localPub)
				|| unconfirmedJoining(existing);
	}

	void accept(byte[] groupId, ContactId from) throws DbException {
		accept(groupId, from, null);
	}

	void accept(byte[] groupId, ContactId from,
			@Nullable String incognitoName) throws DbException {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			PendingOffer o = loadPendingOffer(groupId);
			if (o == null || !o.contactId.equals(from)) throw unavailable();
			long now = clock.currentTimeMillis();
			if (!GroupTrManagerImpl.inviteOfferTimely(now, o.timestamp)) {
				removePendingOffer(groupId);
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.INVITE_EXPIRED);
			}
			LocalAuthor la = localAuthor();
			GroupTrState existing = host.getGroup(groupId);
			byte[] localPub = o.keyed()
					? (existing == null ? NO_KEY : host.localKey(existing))
					: la.getPublicKey().getEncoded();
			if (!admissibleOver(existing, localPub)) {
				removePendingOffer(groupId);
				return;
			}
			Contact inviter = contactOrNull(o.contactId);
			byte[] expected = o.keyed() ? o.inviterIdentity : o.inviterPubKey;
			if (inviter == null || expected == null || !Arrays.equals(
					inviter.getAuthor().getPublicKey().getEncoded(),
					expected)) {
				removePendingOffer(groupId);
				throw unavailable();
			}
			if (!canReceive(inviter.getId())) throw unavailable();
			if (incognitoName != null) {
				if (!GroupTrIncognitoNames.isIncognitoName(incognitoName)) {
					throw new IllegalArgumentException();
				}
				if (!canAcceptIncognito(groupId)) {
					throw new GroupTrAuthException(GroupTrAuthException
							.Reason.INCOGNITO_NOT_SUPPORTED);
				}
			}
			if (o.keyed()) {
				acceptWithGroupKey(groupId, o, la, inviter, existing,
						incognitoName, now);
				removePendingOffer(groupId);
				return;
			}
			byte[] localMlDsa = identityManager.getLocalMlDsaSigPublicKey();
			if (localMlDsa == null) throw unavailable();
			byte[] sig = host.signAsIdentity(LABEL_ACCEPT,
					responseSignedInput(groupId, localPub, o.inviterPubKey,
							o.creatorPubKey, now, localMlDsa));
			BdfList body = incognitoName == null
					? BdfList.of((long) MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT,
							groupId, o.inviterPubKey, now, sig)
					: BdfList.of((long) MessageTypes
									.GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO,
							groupId, o.inviterPubKey, now, incognitoName, sig);
			if (existing != null) host.removeFromDevice(groupId);
			db.transaction(false, txn -> host.dispatch(txn, inviter, now,
					body));
			List<GroupTrMember> members = new ArrayList<>(3);
			members.add(new GroupTrMember(o.creatorPubKey, o.creatorName,
					now, 0L, MemberRole.CREATOR, o.creatorMlDsaPubKey));
			members.add(new GroupTrMember(o.inviterPubKey,
					inviter.getAuthor().getName(), now, 0L, MemberRole.MEMBER,
					inviter.getMlDsaSigPublicKey()));
			members.add(new GroupTrMember(localPub, incognitoName == null
					? la.getName() : incognitoName, now, 0L, MemberRole.MEMBER,
					localMlDsa));
			GroupTrState joining = new GroupTrState(groupId, o.groupName,
					o.salt, o.creatorPubKey, o.creatorName, now, 0L, false,
					members);
			Settings extra = new Settings();
			extra.put(S_JOINING_VIA, toHexString(o.inviterPubKey));
			extra.putBoolean(S_JOINED_THROUGH_MEMBER, true);
			host.materialize(joining, extra);
			if (incognitoName != null) {
				host.setIncognitoName(groupId, incognitoName);
			}
			removePendingOffer(groupId);
		} finally {
			lock.unlock();
		}
	}

	private void acceptWithGroupKey(byte[] groupId, PendingOffer o,
			LocalAuthor la, Contact inviter, @Nullable GroupTrState existing,
			@Nullable String incognitoName, long now) throws DbException {
		byte[] inviterMlDsa = o.inviterMlDsaPubKey;
		byte[] inviterIdentity = o.inviterIdentity;
		if (inviterMlDsa == null || inviterIdentity == null) {
			throw unavailable();
		}
		GroupTrKeys keys = host.keys();
		GroupTrKeys.Own own = keys.generate(now);
		try {
			acceptKeyed(groupId, o, la, inviter, existing, incognitoName, now,
					inviterMlDsa, inviterIdentity, own);
		} finally {
			own.clear();
		}
	}

	private void acceptKeyed(byte[] groupId, PendingOffer o, LocalAuthor la,
			Contact inviter, @Nullable GroupTrState existing,
			@Nullable String incognitoName, long now, byte[] inviterMlDsa,
			byte[] inviterIdentity, GroupTrKeys.Own own) throws DbException {
		GroupTrKeys keys = host.keys();
		GroupTrKeys.Binding binding = host.bind(groupId, own, now);
		byte[] tag = keys.removalTag(groupId, la.getPublicKey().getEncoded());
		byte[] sig = keys.sign(own, LABEL_ACCEPT, responseSignedInput(groupId,
				own.pub, o.inviterPubKey, o.creatorPubKey, now, own.mlDsa,
				tag));
		BdfList body = incognitoName == null
				? BdfList.of((long) MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT,
						groupId, o.inviterPubKey, now, sig, binding.toList(),
						tag)
				: BdfList.of((long) MessageTypes
								.GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO,
						groupId, o.inviterPubKey, now, incognitoName, sig,
						binding.toList(), tag);
		if (existing != null) host.removeFromDevice(groupId);
		db.transaction(false, txn -> host.dispatch(txn, inviter, now, body));
		List<GroupTrMember> members = new ArrayList<>(3);
		members.add(new GroupTrMember(o.creatorPubKey, o.creatorName,
				now, 0L, MemberRole.CREATOR, o.creatorMlDsaPubKey));
		members.add(new GroupTrMember(o.inviterPubKey,
				inviter.getAuthor().getName(), now, 0L, MemberRole.MEMBER,
				inviterMlDsa));
		members.add(new GroupTrMember(own.pub, incognitoName == null
				? la.getName() : incognitoName, now, 0L, MemberRole.MEMBER,
				own.mlDsa));
		GroupTrState joining = new GroupTrState(groupId, o.groupName,
				o.salt, o.creatorPubKey, o.creatorName, now, 0L, false,
				members, 0L, GroupTrState.SCHEME_GROUP_KEYS);
		Settings extra = new Settings();
		extra.put(S_JOINING_VIA, toHexString(o.inviterPubKey));
		extra.putBoolean(S_JOINED_THROUGH_MEMBER, true);
		if (incognitoName != null) {
			host.setIncognitoName(groupId, incognitoName);
		}
		host.materializeKeyed(joining, extra, own, inviter.getId(),
				inviterIdentity, o.inviterPubKey, inviterMlDsa,
				o.bindingTimestamp);
	}

	void decline(byte[] groupId, ContactId from) throws DbException {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			PendingOffer o = loadPendingOffer(groupId);
			if (o == null || !o.contactId.equals(from)) throw unavailable();
			removePendingOffer(groupId);
			Contact inviter = contactOrNull(o.contactId);
			byte[] expected = o.keyed() ? o.inviterIdentity : o.inviterPubKey;
			if (inviter == null || expected == null || !Arrays.equals(
					inviter.getAuthor().getPublicKey().getEncoded(),
					expected)) {
				return;
			}
			if (!canReceive(inviter.getId())) return;
			byte[] localMlDsa = identityManager.getLocalMlDsaSigPublicKey();
			if (localMlDsa == null) return;
			LocalAuthor la = localAuthor();
			long now = clock.currentTimeMillis();
			byte[] sig = host.signAsIdentity(LABEL_DECLINE, responseSignedInput(
					groupId, la.getPublicKey().getEncoded(), o.inviterPubKey,
					o.creatorPubKey, now, localMlDsa));
			BdfList body = BdfList.of(
					(long) MessageTypes.GROUPTR_MEMBER_INVITE_DECLINE, groupId,
					o.inviterPubKey, now, sig);
			db.transaction(false, txn -> host.dispatch(txn, inviter, now,
					body));
		} finally {
			lock.unlock();
		}
	}

	private void handleResponse(GroupTrMemberInviteRecordEvent e)
			throws DbException, FormatException {
		BdfList r = e.getRecord();
		byte[] groupId = r.getRaw(1);
		byte[] inviterPub = r.getRaw(2);
		long answered = r.getLong(3);
		boolean incognito = e.getMessageType()
				== MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO;
		String joinerName = incognito ? r.getString(4) : null;
		int sigAt = incognito ? 5 : 4;
		byte[] sig = r.getRaw(sigAt);
		boolean accepted = incognito || e.getMessageType()
				== MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT;
		GroupTrKeys.Binding binding = accepted && r.size() > sigAt + 1
				? GroupTrKeys.Binding.parse(r.getList(sigAt + 1)) : null;
		byte[] tag = binding != null && r.size() > sigAt + 2
				? r.getRaw(sigAt + 2) : null;
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.getGroup(groupId);
			if (s == null || s.isDissolved()) return;
			boolean keyed = s.usesGroupKeys();
			byte[] localPub = host.localKey(s);
			if (!Arrays.equals(inviterPub, localPub)) return;
			byte[] identity = host.identityOf(e.getContactId());
			if (identity == null) return;
			String identityHex = toHexString(identity);
			SentInvite sent = loadSent(groupId).get(identityHex);
			if (sent == null || sent.accepted >= 0L) return;
			long now = clock.currentTimeMillis();
			if (!GroupTrManagerImpl.inviteResponseTimely(now, sent.offered,
					answered)) {
				if (!GroupTrManagerImpl.sentInviteOpen(now, sent.offered)) {
					removeSent(groupId, identityHex);
				}
				return;
			}
			Contact invitee = contactOrNull(e.getContactId());
			if (invitee == null) return;
			byte[] inviteePub;
			byte[] inviteeMlDsa;
			if (keyed && accepted) {
				if (binding == null || tag == null) return;
				if (!Arrays.equals(tag, host.keys().removalTag(groupId,
						identity))) {
					return;
				}
				if (!host.keys().verify(groupId, binding, identity,
						invitee.getMlDsaSigPublicKey())) {
					return;
				}
				inviteePub = binding.pub;
				inviteeMlDsa = binding.mlDsa;
			} else if (keyed) {
				inviteePub = identity;
				inviteeMlDsa = invitee.getMlDsaSigPublicKey();
			} else {
				if (binding != null) return;
				inviteePub = identity;
				inviteeMlDsa = contactMlDsaKey(s, identity);
			}
			if (inviteeMlDsa == null) return;
			byte[] signedTag = keyed && accepted ? tag : null;
			if (!verifyWith(sig, accepted ? LABEL_ACCEPT : LABEL_DECLINE,
					responseSignedInput(groupId, inviteePub, localPub,
							s.getCreatorPubKey(), answered, inviteeMlDsa,
							signedTag), inviteePub, inviteeMlDsa)) {
				return;
			}
			if (keyed && accepted && binding != null) {
				host.storeBinding(groupId, e.getContactId(), identity,
						binding);
			}
			if (!accepted
					|| availability(s, localPub)
					!= MemberInviteAvailability.ALLOWED
					|| (signedTag != null && removedTag(groupId, signedTag))) {
				removeSent(groupId, identityHex);
				return;
			}
			String inviteeName = joinerName != null ? joinerName
					: invitee.getAuthor().getName();
			byte[] reqSig = host.sign(s, LABEL_JOIN_REQUEST,
					joinRequestSignedInput(groupId, localPub, inviteePub,
							answered, now, inviteeName, inviteeMlDsa, sig,
							signedTag));
			BdfList body = BdfList.of(
					(long) MessageTypes.GROUPTR_JOIN_REQUEST, groupId,
					inviteePub, inviteeName, inviteeMlDsa, answered, sig, now,
					reqSig);
			if (signedTag != null) body.add(signedTag);
			String inviteeHex = toHexString(inviteePub);
			db.transaction(false, txn -> {
				Contact creator = host.findContact(txn, s,
						s.getCreatorPubKey());
				if (creator == null) return;
				host.dispatch(txn, creator, now, body);
				if (!inviteeHex.equals(identityHex)) {
					settingsManager.deleteSettings(txn, nsOf(groupId),
							Collections.singletonList(
									K_SENT_PREFIX + identityHex));
				}
				storeSent(txn, groupId, inviteeHex, new SentInvite(
						e.getContactId().getInt(), sent.offered, now));
			});
		} finally {
			lock.unlock();
		}
	}

	private void handleJoinRequest(GroupTrMemberInviteRecordEvent e)
			throws DbException, FormatException {
		BdfList r = e.getRecord();
		byte[] groupId = r.getRaw(1);
		byte[] inviteePub = r.getRaw(2);
		String inviteeName = r.getString(3);
		byte[] inviteeMlDsa = r.getRaw(4);
		long acceptTs = r.getLong(5);
		byte[] acceptSig = r.getRaw(6);
		long requestTs = r.getLong(7);
		byte[] requestSig = r.getRaw(8);
		byte[] tag = r.size() > 9 ? r.getRaw(9) : null;
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.getGroup(groupId);
			if (s == null || s.isDissolved()) return;
			byte[] localPub = host.localKey(s);
			if (!Arrays.equals(localPub, s.getCreatorPubKey())) return;
			byte[] inviterPub = host.senderPubKey(s, e.getContactId());
			if (inviterPub == null || Arrays.equals(inviterPub, localPub)) {
				return;
			}
			if (!listed(s, inviterPub)) return;
			if (Arrays.equals(inviteePub, localPub)
					|| Arrays.equals(inviteePub, inviterPub)
					|| listed(s, inviteePub)) {
				return;
			}
			if (s.getMembers().size() >= MAX_GROUP_MEMBERS) return;
			if (!mayInvite(groupId, inviterPub)) return;
			if (removedByCreator(groupId, inviteePub)) return;
			if (s.usesGroupKeys() != (tag != null)) return;
			long now = clock.currentTimeMillis();
			if (!joinRequestTimely(now, acceptTs)
					|| !joinRequestTimely(now, requestTs)) {
				return;
			}
			byte[] known = s.usesGroupKeys()
					? host.knownMlDsaKey(s, inviteePub)
					: contactMlDsaKey(s, inviteePub);
			if (known != null && !Arrays.equals(known, inviteeMlDsa)) return;
			if (!host.verify(s, requestSig, LABEL_JOIN_REQUEST,
					joinRequestSignedInput(groupId, inviterPub, inviteePub,
							acceptTs, requestTs, inviteeName, inviteeMlDsa,
							acceptSig, tag), inviterPub)) {
				return;
			}
			if (!verifyWith(acceptSig, LABEL_ACCEPT, responseSignedInput(
					groupId, inviteePub, inviterPub, localPub, acceptTs,
					inviteeMlDsa, tag), inviteePub, inviteeMlDsa)) {
				return;
			}
			if (tag != null && removedTag(groupId, tag)) return;
			if (!admitJoin(groupId, inviterPub, inviteePub, acceptTs, now)) {
				return;
			}
			host.addMember(groupId, inviteePub, inviteeName, inviteeMlDsa);
			if (tag != null) {
				storeMemberTag(groupId, inviteePub, tag);
				storeInviter(groupId, inviteePub, inviterPub);
			}
			host.sendMemberListSnapshot(groupId);
		} finally {
			lock.unlock();
		}
	}

	void noteRemovedByCreator(byte[] groupId, byte[] pubKey) {
		try {
			rememberRemoved(groupId, pubKey);
		} catch (DbException ex) {
		}
	}

	boolean removedByCreator(byte[] groupId, byte[] pubKey)
			throws DbException {
		return loadRemoved(groupId).contains(toHexString(pubKey));
	}

	private List<String> loadList(byte[] groupId, String key)
			throws DbException {
		String stored = settingsManager.getSettings(nsOf(groupId)).get(key);
		List<String> out = new ArrayList<>();
		if (stored == null || stored.isEmpty()) return out;
		for (String item : stored.split(",")) {
			if (!item.isEmpty() && !out.contains(item)) out.add(item);
		}
		return out;
	}

	private void storeList(byte[] groupId, String key, List<String> items)
			throws DbException {
		Settings out = new Settings();
		out.put(key, String.join(",", items));
		settingsManager.mergeSettings(out, nsOf(groupId));
	}

	boolean removedTag(byte[] groupId, byte[] tag) throws DbException {
		return loadList(groupId, K_REMOVED_TAGS).contains(toHexString(tag));
	}

	void noteRemovedTag(byte[] groupId, byte[] tag) throws DbException {
		List<String> tags = loadList(groupId, K_REMOVED_TAGS);
		String hex = toHexString(tag);
		tags.remove(hex);
		tags.add(hex);
		while (tags.size() > MAX_REMOVED_REMEMBERED) tags.remove(0);
		storeList(groupId, K_REMOVED_TAGS, tags);
	}

	void forgetRemovedTag(byte[] groupId, byte[] tag) throws DbException {
		List<String> tags = loadList(groupId, K_REMOVED_TAGS);
		if (tags.remove(toHexString(tag))) {
			storeList(groupId, K_REMOVED_TAGS, tags);
		}
	}

	void noteRemovedContact(byte[] groupId, byte[] pubKey, byte[] tag)
			throws DbException {
		rememberRemoved(groupId, pubKey);
		noteRemovedTag(groupId, tag);
	}

	void storeMemberTag(byte[] groupId, byte[] pubKey, byte[] tag)
			throws DbException {
		Settings stored = settingsManager.getSettings(nsOf(groupId));
		int held = 0;
		for (Map.Entry<String, String> e : stored.entrySet()) {
			if (e.getKey().startsWith(K_MEMBER_TAG_PREFIX)
					&& e.getValue() != null && !e.getValue().isEmpty()) {
				held++;
			}
		}
		String key = K_MEMBER_TAG_PREFIX + toHexString(pubKey);
		if (stored.get(key) == null && held >= MAX_GROUP_MEMBERS) return;
		Settings out = new Settings();
		out.put(key, toHexString(tag));
		settingsManager.mergeSettings(out, nsOf(groupId));
	}

	@Nullable
	byte[] memberTag(byte[] groupId, byte[] pubKey) throws DbException {
		String v = settingsManager.getSettings(nsOf(groupId))
				.get(K_MEMBER_TAG_PREFIX + toHexString(pubKey));
		if (v == null || v.isEmpty()) return null;
		try {
			return fromHexString(v);
		} catch (FormatException ex) {
			return null;
		}
	}

	void dropMemberTag(byte[] groupId, byte[] pubKey) throws DbException {
		String hex = toHexString(pubKey);
		settingsManager.deleteSettings(nsOf(groupId), Arrays.asList(
				K_MEMBER_TAG_PREFIX + hex, K_INVITER_PREFIX + hex));
	}

	private void storeInviter(byte[] groupId, byte[] inviteePub,
			byte[] inviterPub) throws DbException {
		Settings stored = settingsManager.getSettings(nsOf(groupId));
		int held = 0;
		for (Map.Entry<String, String> e : stored.entrySet()) {
			if (e.getKey().startsWith(K_INVITER_PREFIX)
					&& e.getValue() != null && !e.getValue().isEmpty()) {
				held++;
			}
		}
		String key = K_INVITER_PREFIX + toHexString(inviteePub);
		if (stored.get(key) == null && held >= MAX_GROUP_MEMBERS) return;
		Settings out = new Settings();
		out.put(key, toHexString(inviterPub));
		settingsManager.mergeSettings(out, nsOf(groupId));
	}

	boolean invitedBy(byte[] groupId, byte[] inviteePub, byte[] inviterPub)
			throws DbException {
		String v = settingsManager.getSettings(nsOf(groupId))
				.get(K_INVITER_PREFIX + toHexString(inviteePub));
		return v != null && v.equals(toHexString(inviterPub));
	}

	private List<String> loadRemoved(byte[] groupId) throws DbException {
		String stored = settingsManager.getSettings(nsOf(groupId))
				.get(K_REMOVED);
		List<String> out = new ArrayList<>();
		if (stored == null || stored.isEmpty()) return out;
		for (String hex : stored.split(",")) {
			if (!hex.isEmpty() && !out.contains(hex)) out.add(hex);
		}
		return out;
	}

	private void storeRemoved(byte[] groupId, List<String> removed)
			throws DbException {
		Settings out = new Settings();
		out.put(K_REMOVED, String.join(",", removed));
		settingsManager.mergeSettings(out, nsOf(groupId));
	}

	private void rememberRemoved(byte[] groupId, byte[] pubKey)
			throws DbException {
		List<String> removed = loadRemoved(groupId);
		String hex = toHexString(pubKey);
		removed.remove(hex);
		removed.add(hex);
		while (removed.size() > MAX_REMOVED_REMEMBERED) removed.remove(0);
		storeRemoved(groupId, removed);
	}

	private void forgetRemoved(byte[] groupId, byte[] pubKey)
			throws DbException {
		List<String> removed = loadRemoved(groupId);
		if (removed.remove(toHexString(pubKey))) storeRemoved(groupId, removed);
	}

	private boolean admitJoin(byte[] groupId, byte[] inviterPub,
			byte[] inviteePub, long acceptTs, long now)
			throws DbException, FormatException {
		Settings stored = settingsManager.getSettings(nsOf(groupId));
		Map<String, Long> seen = new LinkedHashMap<>();
		for (BdfList entry : entries(stored.get(K_JOINS_SEEN))) {
			long ts = entry.getLong(1);
			if (ts < now - JOIN_WINDOW_MS) continue;
			seen.put(toHexString(entry.getRaw(0)), ts);
		}
		String inviteeHex = toHexString(inviteePub);
		Long previous = seen.get(inviteeHex);
		if (previous != null && acceptTs <= previous) return false;
		if (previous == null && seen.size() >= MAX_JOINS_REMEMBERED) {
			return false;
		}
		BdfList rate = new BdfList();
		boolean allowed = true;
		boolean counted = false;
		for (BdfList entry : entries(stored.get(K_JOIN_RATE))) {
			byte[] who = entry.getRaw(0);
			long windowStart = entry.getLong(1);
			long count = entry.getLong(2);
			if (now - windowStart >= DAY_MS || windowStart > now) {
				if (!Arrays.equals(who, inviterPub)) continue;
				windowStart = now;
				count = 0L;
			}
			if (Arrays.equals(who, inviterPub)) {
				counted = true;
				if (count >= MAX_JOINS_PER_INVITER_PER_DAY) allowed = false;
				else count++;
			}
			rate.add(BdfList.of(who, windowStart, count));
		}
		if (!counted) rate.add(BdfList.of(inviterPub, now, 1L));
		if (!allowed) return false;
		seen.put(inviteeHex, acceptTs);
		BdfList seenList = new BdfList();
		for (Map.Entry<String, Long> en : seen.entrySet()) {
			seenList.add(BdfList.of(fromHexString(en.getKey()), en.getValue()));
		}
		Settings out = new Settings();
		out.put(K_JOINS_SEEN, toHexString(clientHelper.toByteArray(seenList)));
		out.put(K_JOIN_RATE, toHexString(clientHelper.toByteArray(rate)));
		settingsManager.mergeSettings(out, nsOf(groupId));
		return true;
	}

	void onSnapshot(GroupMemberListSnapshotEvent e) {
		byte[] groupId = e.getGroupId();
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			if (joiningVia(groupId) != null) announceUpdated(groupId);
			byte[] record = e.getRecord();
			if (record.length == 0) return;
			Map<String, SentInvite> sent = loadSent(groupId);
			Map<String, Integer> admitted = loadAdmitted(groupId);
			long forwarded = settingsManager.getSettings(nsOf(groupId))
					.getLong(K_FORWARDED_EPOCH, -1L);
			List<String> waiting = new ArrayList<>();
			byte[] canonical = e.getMemberCanonical();
			for (Map.Entry<String, SentInvite> en : sent.entrySet()) {
				if (en.getValue().accepted < 0L) continue;
				if (lists(canonical, fromHexString(en.getKey()))) {
					waiting.add(en.getKey());
				}
			}
			boolean newer = !admitted.isEmpty() && e.getEpoch() > forwarded;
			BdfList full = clientHelper.toList(record);
			long claimed = full.size() > 6 ? full.getList(6).getLong(0) : -1L;
			String claimedId = full.size() > 6
					? toHexString(full.getList(6).getRaw(1)) : "";
			Settings stored = settingsManager.getSettings(nsOf(groupId));
			boolean fresher = false;
			for (String inviteeHex : admitted.keySet()) {
				if (secretDue(stored, inviteeHex, claimed, claimedId)) {
					fresher = true;
				}
			}
			if (waiting.isEmpty() && !newer && !fresher) return;
			GroupTrState s = host.getGroup(groupId);
			if (s == null || s.isDissolved()) return;
			if (!host.verify(s, e.getRecordSig(), LABEL_SNAPSHOT,
					e.getSignedInput(), s.getCreatorPubKey())) {
				return;
			}
			BdfList withoutSecret = full.size() > 6
					? new BdfList(full.subList(0, 6)) : full;
			long secretSeq = claimed >= 0L
					&& secretValid(s, e.getEpoch(), full.getList(6))
					? claimed : -1L;
			BdfList body = secretSeq >= 0L ? full : withoutSecret;
			long now = clock.currentTimeMillis();
			for (String inviteeHex : waiting) {
				SentInvite si = sent.get(inviteeHex);
				Contact invitee = contactOrNull(new ContactId(si.contactId));
				if (invitee == null || !inviteeHex.equals(keyHex(s, invitee))) {
					removeSent(groupId, inviteeHex);
					continue;
				}
				boolean room = admitted.size() < MAX_ADMITTED_PER_GROUP;
				db.transaction(false, txn -> {
					host.dispatch(txn, invitee, now, body);
					settingsManager.deleteSettings(txn, nsOf(groupId),
							Collections.singletonList(
									K_SENT_PREFIX + inviteeHex));
					if (room) {
						Settings out = new Settings();
						out.put(K_ADMITTED_PREFIX + inviteeHex,
								Integer.toString(si.contactId));
						if (secretSeq >= 0L) {
							out.putLong(K_FORWARDED_SECRET_PREFIX + inviteeHex,
									secretSeq);
							out.put(K_FORWARDED_SECRET_ID_PREFIX + inviteeHex,
									claimedId);
						}
						settingsManager.mergeSettings(txn, out,
								nsOf(groupId));
					}
				});
				if (room) admitted.put(inviteeHex, si.contactId);
			}
			for (Map.Entry<String, Integer> en : admitted.entrySet()) {
				String inviteeHex = en.getKey();
				if (waiting.contains(inviteeHex)) continue;
				boolean listed = lists(canonical, fromHexString(inviteeHex));
				boolean secretDue = listed && secretSeq >= 0L
						&& secretDue(stored, inviteeHex, secretSeq, claimedId);
				if (!newer && !secretDue) continue;
				Contact invitee = contactOrNull(new ContactId(en.getValue()));
				boolean same = invitee != null
						&& inviteeHex.equals(keyHex(s, invitee));
				db.transaction(false, txn -> {
					if (invitee != null && same) {
						host.dispatch(txn, invitee, now,
								listed ? body : withoutSecret);
						if (listed && secretSeq >= 0L) {
							Settings out = new Settings();
							out.putLong(K_FORWARDED_SECRET_PREFIX + inviteeHex,
									secretSeq);
							out.put(K_FORWARDED_SECRET_ID_PREFIX + inviteeHex,
									claimedId);
							settingsManager.mergeSettings(txn, out,
									nsOf(groupId));
						}
					}
					if (invitee == null || !listed) {
						settingsManager.deleteSettings(txn, nsOf(groupId),
								Arrays.asList(K_ADMITTED_PREFIX + inviteeHex,
										K_FORWARDED_SECRET_PREFIX + inviteeHex,
										K_FORWARDED_SECRET_ID_PREFIX
												+ inviteeHex));
					}
				});
			}
			if (e.getEpoch() > forwarded) {
				Settings out = new Settings();
				out.putLong(K_FORWARDED_EPOCH, e.getEpoch());
				settingsManager.mergeSettings(out, nsOf(groupId));
			}
		} catch (DbException | FormatException ex) {
		} finally {
			lock.unlock();
		}
	}

	private static boolean secretDue(Settings stored, String inviteeHex,
			long seq, String id) {
		if (seq < 0L) return false;
		long sent = stored.getLong(K_FORWARDED_SECRET_PREFIX + inviteeHex, -1L);
		if (seq != sent) return seq > sent;
		return !id.equals(stored.get(K_FORWARDED_SECRET_ID_PREFIX + inviteeHex));
	}

	private boolean secretValid(GroupTrState s, long epoch, BdfList blob) {
		try {
			GroupTrKeys.Secret secret = GroupTrKeys.parseSecret(blob);
			return host.verify(s, blob.getRaw(3), GroupTrKeys.LABEL_SECRET,
					host.keys().secretInput(s.getGroupId(), epoch, secret),
					s.getCreatorPubKey());
		} catch (FormatException ex) {
			return false;
		}
	}

	void forwardDissolved(GroupTrState s, long epoch, long timestamp,
			byte[] sig) {
		byte[] groupId = s.getGroupId();
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			Map<String, Integer> admitted = loadAdmitted(groupId);
			if (admitted.isEmpty()) return;
			BdfList body = BdfList.of((long) MessageTypes.GROUP_DISSOLVED,
					groupId, epoch, timestamp, sig);
			long now = clock.currentTimeMillis();
			for (Map.Entry<String, Integer> en : admitted.entrySet()) {
				Contact invitee = contactOrNull(new ContactId(en.getValue()));
				if (invitee == null
						|| !en.getKey().equals(keyHex(s, invitee))) {
					continue;
				}
				db.transaction(false, txn ->
						host.dispatch(txn, invitee, now, body));
			}
		} catch (DbException ex) {
		} finally {
			lock.unlock();
		}
	}

	void forwardLeave(GroupTrState s, ContactId from, byte[] leaverPubKey,
			long epoch, long timestamp, byte[] sig) {
		byte[] groupId = s.getGroupId();
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			Integer invitee = loadAdmitted(groupId)
					.get(toHexString(leaverPubKey));
			if (invitee == null || invitee != from.getInt()) return;
			BdfList body = BdfList.of((long) MessageTypes.GROUP_MEMBER_LEFT,
					groupId, leaverPubKey, epoch, timestamp, sig);
			long now = clock.currentTimeMillis();
			db.transaction(false, txn -> {
				Contact creator = host.findContact(txn, s,
						s.getCreatorPubKey());
				if (creator != null) host.dispatch(txn, creator, now, body);
			});
		} catch (DbException ex) {
		} finally {
			lock.unlock();
		}
	}

	void onMemberRemoved(byte[] groupId, byte[] removedPubKey) {
		try {
			boolean configured = settingsManager.getSettings(nsOf(groupId))
					.get(K_PERM_TIMESTAMP) != null
					|| !loadGrants(groupId).isEmpty();
			forgetGrant(groupId, removedPubKey);
			rememberRemoved(groupId, removedPubKey);
			if (!configured) return;
			host.sendMemberListSnapshot(groupId);
		} catch (DbException ex) {
		}
	}

	private Map<String, Integer> loadAdmitted(byte[] groupId)
			throws DbException {
		Map<String, Integer> out = new LinkedHashMap<>();
		Settings s = settingsManager.getSettings(nsOf(groupId));
		for (Map.Entry<String, String> e : s.entrySet()) {
			if (!e.getKey().startsWith(K_ADMITTED_PREFIX)) continue;
			String value = e.getValue();
			if (value == null || value.isEmpty()) continue;
			try {
				out.put(e.getKey().substring(K_ADMITTED_PREFIX.length()),
						Integer.parseInt(value));
			} catch (NumberFormatException ex) {
			}
		}
		return out;
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

	private boolean verifyWith(byte[] sig, String label, byte[] signed,
			byte[] ed25519PubKey, byte[] mlDsaPubKey) {
		if (signed.length == 0) return false;
		if (ed25519PubKey.length != 32) return false;
		if (mlDsaPubKey.length
				!= PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES) {
			return false;
		}
		if (sig.length != PostQuantumConstants.HYBRID_SIGNATURE_BYTES) {
			return false;
		}
		try {
			return crypto.verifyHybridSignature(sig, label, signed,
					new HybridSignaturePublicKey(ed25519PubKey, mlDsaPubKey));
		} catch (GeneralSecurityException ex) {
			return false;
		}
	}

	private int sendPermissions(Transaction txn, GroupTrState s,
			Permissions p, Collection<byte[]> to, long timestamp)
			throws DbException {
		BdfList record = BdfList.of((long) MessageTypes.GROUP_PERMISSIONS,
				s.getGroupId(), p.flags, p.timestamp, p.sig);
		int skipped = 0;
		for (byte[] pub : to) {
			Contact c = host.findContact(txn, s, pub);
			if (c == null) continue;
			if (!supports(txn, c)) {
				skipped++;
				continue;
			}
			host.dispatch(txn, c, timestamp, record);
		}
		return skipped;
	}

	private boolean supports(Transaction txn, Contact c) throws DbException {
		return messagingManager.getContactClientMinorVersion(txn, c.getId())
				>= MessagingManager.GROUP_ACTIONS_MIN_VERSION;
	}

	@Nullable
	private Permissions loadPermissions(byte[] groupId) throws DbException {
		Settings s = settingsManager.getSettings(nsOf(groupId));
		String sig = s.get(K_PERM_SIG);
		if (sig == null || sig.isEmpty()) return null;
		try {
			return new Permissions(s.getLong(K_PERM_FLAGS, 0L),
					s.getLong(K_PERM_TIMESTAMP, 0L), fromHexString(sig));
		} catch (FormatException ex) {
			return null;
		}
	}

	@Nullable
	private Permissions loadGrant(byte[] groupId, byte[] memberPubKey)
			throws DbException {
		return parseGrant(settingsManager.getSettings(nsOf(groupId))
				.get(K_GRANT_PREFIX + toHexString(memberPubKey)));
	}

	private Map<String, Permissions> loadGrants(byte[] groupId)
			throws DbException {
		Map<String, Permissions> out = new LinkedHashMap<>();
		Settings s = settingsManager.getSettings(nsOf(groupId));
		for (Map.Entry<String, String> e : s.entrySet()) {
			if (!e.getKey().startsWith(K_GRANT_PREFIX)) continue;
			Permissions g = parseGrant(e.getValue());
			if (g != null) {
				out.put(e.getKey().substring(K_GRANT_PREFIX.length()), g);
			}
		}
		return out;
	}

	@Nullable
	private Permissions parseGrant(@Nullable String value) {
		if (value == null || value.isEmpty()) return null;
		try {
			BdfList l = clientHelper.toList(fromHexString(value));
			return new Permissions(l.getLong(0), l.getLong(1), l.getRaw(2));
		} catch (FormatException ex) {
			return null;
		}
	}

	private void storeGrant(Transaction txn, byte[] groupId,
			byte[] memberPubKey, Permissions g) throws DbException {
		try {
			Settings out = new Settings();
			out.put(K_GRANT_PREFIX + toHexString(memberPubKey), toHexString(
					clientHelper.toByteArray(BdfList.of(g.flags, g.timestamp,
							g.sig))));
			settingsManager.mergeSettings(txn, out, nsOf(groupId));
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	private boolean supportsGrants(Transaction txn, Contact c)
			throws DbException {
		return messagingManager.getContactClientMinorVersion(txn, c.getId())
				>= MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION;
	}

	private void storePermissions(Transaction txn, byte[] groupId,
			Permissions p) throws DbException {
		Settings out = new Settings();
		out.putLong(K_PERM_FLAGS, p.flags);
		out.putLong(K_PERM_TIMESTAMP, p.timestamp);
		out.put(K_PERM_SIG, toHexString(p.sig));
		settingsManager.mergeSettings(txn, out, nsOf(groupId));
	}

	private Map<String, SentInvite> loadSent(byte[] groupId)
			throws DbException {
		Map<String, SentInvite> out = new LinkedHashMap<>();
		Settings s = settingsManager.getSettings(nsOf(groupId));
		for (Map.Entry<String, String> e : s.entrySet()) {
			if (!e.getKey().startsWith(K_SENT_PREFIX)) continue;
			String value = e.getValue();
			if (value == null || value.isEmpty()) continue;
			try {
				BdfList l = clientHelper.toList(fromHexString(value));
				out.put(e.getKey().substring(K_SENT_PREFIX.length()),
						new SentInvite(l.getLong(0).intValue(), l.getLong(1),
								l.getLong(2)));
			} catch (FormatException ex) {
			}
		}
		return out;
	}

	private void storeSent(Transaction txn, byte[] groupId, String inviteeHex,
			SentInvite si) throws DbException {
		try {
			Settings out = new Settings();
			out.put(K_SENT_PREFIX + inviteeHex, toHexString(
					clientHelper.toByteArray(BdfList.of((long) si.contactId,
							si.offered, si.accepted))));
			settingsManager.mergeSettings(txn, out, nsOf(groupId));
		} catch (FormatException ex) {
			throw new DbException(ex);
		}
	}

	private void removeSent(byte[] groupId, String inviteeHex)
			throws DbException {
		settingsManager.deleteSettings(nsOf(groupId),
				Collections.singletonList(K_SENT_PREFIX + inviteeHex));
	}

	@Nullable
	private PendingOffer loadPendingOffer(byte[] groupId) throws DbException {
		String value = settingsManager.getSettings(
				SETTINGS_NS_MEMBER_OFFERS_PENDING).get(toHexString(groupId));
		PendingOffer o = parseOffer(value);
		if (o == null) return null;
		if (!GroupTrManagerImpl.inviteOfferTimely(clock.currentTimeMillis(),
				o.timestamp)) {
			return null;
		}
		return o;
	}

	@Nullable
	private PendingOffer parseOffer(@Nullable String value) {
		if (value == null || value.isEmpty()) return null;
		try {
			BdfList l = clientHelper.toList(fromHexString(value));
			boolean keyed = l.size() >= 11;
			return new PendingOffer(l.getString(0), l.getRaw(1),
					l.getString(2), l.getRaw(3), l.getRaw(4), l.getRaw(5),
					new ContactId(l.getLong(6).intValue()), l.getLong(7),
					keyed ? l.getRaw(8) : null, keyed ? l.getRaw(9) : null,
					keyed ? l.getLong(10) : 0L);
		} catch (FormatException ex) {
			return null;
		}
	}

	private void storePendingOffer(byte[] groupId, PendingOffer o)
			throws DbException, FormatException {
		synchronized (offersLock) {
			Settings all = settingsManager.getSettings(
					SETTINGS_NS_MEMBER_OFFERS_PENDING);
			long now = clock.currentTimeMillis();
			int live = 0;
			List<String> stale = new ArrayList<>();
			for (Map.Entry<String, String> e : all.entrySet()) {
				PendingOffer p = parseOffer(e.getValue());
				if (p == null || !GroupTrManagerImpl.inviteOfferTimely(now,
						p.timestamp)) {
					if (e.getValue() != null && !e.getValue().isEmpty()) {
						stale.add(e.getKey());
					}
				} else {
					live++;
				}
			}
			settingsManager.deleteSettings(SETTINGS_NS_MEMBER_OFFERS_PENDING,
					stale);
			if (live >= MAX_PENDING_OFFERS) return;
			BdfList stored = BdfList.of(o.groupName, o.salt, o.creatorName,
					o.creatorPubKey, o.creatorMlDsaPubKey, o.inviterPubKey,
					(long) o.contactId.getInt(), o.timestamp);
			if (o.keyed()) {
				stored.add(o.inviterMlDsaPubKey);
				stored.add(o.inviterIdentity);
				stored.add(o.bindingTimestamp);
			}
			Settings out = new Settings();
			out.put(toHexString(groupId), toHexString(clientHelper.toByteArray(
					stored)));
			settingsManager.mergeSettings(out,
					SETTINGS_NS_MEMBER_OFFERS_PENDING);
		}
	}

	private void removePendingOffer(byte[] groupId) throws DbException {
		synchronized (offersLock) {
			settingsManager.deleteSettings(SETTINGS_NS_MEMBER_OFFERS_PENDING,
					Collections.singletonList(toHexString(groupId)));
		}
	}

	private List<BdfList> entries(@Nullable String hex) {
		List<BdfList> out = new ArrayList<>();
		if (hex == null || hex.isEmpty()) return out;
		try {
			BdfList list = clientHelper.toList(fromHexString(hex));
			for (int i = 0; i < list.size(); i++) out.add(list.getList(i));
		} catch (FormatException ex) {
		}
		return out;
	}

	@Nullable
	private byte[] contactMlDsaKey(GroupTrState s, byte[] pubKey)
			throws DbException {
		return db.transactionWithNullableResult(true, txn -> {
			Contact c = host.findContact(txn, s, pubKey);
			return c == null ? null : c.getMlDsaSigPublicKey();
		});
	}

	@Nullable
	private String keyHex(GroupTrState s, Contact c) throws DbException {
		byte[] key = db.transactionWithNullableResult(true,
				txn -> host.keyOf(txn, s, c));
		return key == null ? null : toHexString(key);
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

	private GroupTrState requireLive(byte[] groupId) throws DbException {
		GroupTrState s = host.getGroup(groupId);
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

	private LocalAuthor localAuthor() throws DbException {
		return db.transactionWithResult(true, identityManager::getLocalAuthor);
	}

	private void announceUpdated(byte[] groupId) {
		eventBus.broadcast(new GroupTrLocalStateChangedEvent(groupId,
				GroupTrLocalStateChangedEvent.Kind.UPDATED));
	}

	private static boolean listed(GroupTrState s, byte[] pubKey) {
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pubKey)) return true;
		}
		return false;
	}

	private static GroupTrAuthException unavailable() {
		return new GroupTrAuthException(
				GroupTrAuthException.Reason.MEMBER_INVITES_UNAVAILABLE);
	}

	static String nsOf(byte[] groupId) {
		return SETTINGS_NS_MEMBER_INVITES_PREFIX + toHexString(groupId);
	}
}
