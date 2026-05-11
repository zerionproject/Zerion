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
import org.briarproject.briar.api.grouptr.GroupTrState;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.event.GroupEpochCommitEvent;
import org.briarproject.briar.api.messaging.event.GroupMembershipChangedEvent;
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
import static org.briarproject.briar.grouptr.GroupTrConstants.S_DISSOLVED;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_EPOCH;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_GROUP_IDS;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_MEMBERS;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_NAME;
import static org.briarproject.briar.grouptr.GroupTrConstants.S_SALT;

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
			members.add(new GroupTrMember(creatorPub, creatorName, now, 0L));
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
			if (s == null) return;
			if (s.isDissolved()) return;
			byte[] sig = e.getRecordSig();
			byte[] signedInput = e.getSignedInput();
			switch (e.getKind()) {
				case MEMBER_ADDED:
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, s.getCreatorPubKey())) return;
					applyMemberAdded(s, e);
					break;
				case MEMBER_REMOVED:
					if (Arrays.equals(e.getTargetPubKey(),
							s.getCreatorPubKey())) return;
					if (e.getToEpoch() != s.getEpoch() + 1) return;
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, s.getCreatorPubKey())) return;
					applyMemberRemoved(s, e);
					break;
				case MEMBER_LEFT:
					if (Arrays.equals(e.getTargetPubKey(),
							s.getCreatorPubKey())) return;
					applyMemberLeft(s, e);
					break;
				case GROUP_DISSOLVED:
					if (!verify(sig, SIGNING_LABEL_GROUP_MEMBERSHIP,
							signedInput, s.getCreatorPubKey())) return;
					s.setDissolved(true);
					s.setEpoch(s.getEpoch() + 1);
					persist(s);
					break;
			}
		} catch (DbException | FormatException ex) {
			// drop silently — no logging in production per project policy
		}
	}

	private void handleEpochCommit(GroupEpochCommitEvent e) {
		try {
			GroupTrState s = getGroup(e.getGroupId());
			if (s == null || s.isDissolved()) return;
			if (e.getFromEpoch() != s.getEpoch()) return;
			if (!verify(e.getRecordSig(),
					SIGNING_LABEL_GROUP_EPOCH_COMMIT, e.getSignedInput(),
					s.getCreatorPubKey())) return;
			s.setEpoch(e.getToEpoch());
			persist(s);
		} catch (DbException | FormatException ex) {
			// drop silently
		}
	}

	private void applyMemberAdded(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] pk = e.getTargetPubKey();
		if (pk == null) return;
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
	}

	private void applyMemberRemoved(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] pk = e.getTargetPubKey();
		if (pk == null) return;
		List<GroupTrMember> next = new ArrayList<>(s.getMembers().size());
		for (GroupTrMember m : s.getMembers()) {
			if (!Arrays.equals(m.getPubKey(), pk)) next.add(m);
		}
		s.setMembers(next);
		s.setEpoch(e.getToEpoch());
		persist(s);
	}

	private void applyMemberLeft(GroupTrState s,
			GroupMembershipChangedEvent e)
			throws DbException, FormatException {
		byte[] pk = e.getTargetPubKey();
		if (pk == null) return;
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
		s.setEpoch(s.getEpoch() + 1);
		persist(s);
	}

	private boolean verify(byte[] sig, String label, byte[] signed,
			byte[] pubKeyBytes) {
		if (signed.length == 0) return false;
		PublicKey p = new SignaturePublicKey(pubKeyBytes);
		try {
			return crypto.verifySignature(sig, label, signed, p);
		} catch (GeneralSecurityException ex) {
			return false;
		}
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
		BdfList list = new BdfList();
		for (GroupTrMember m : s.getMembers()) {
			BdfList ml = new BdfList();
			ml.add(m.getPubKey());
			ml.add(m.getName());
			ml.add(m.getJoinedAt());
			ml.add(m.getJoinedAtEpoch());
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
			members.add(new GroupTrMember(ml.getRaw(0), ml.getString(1),
					ml.getLong(2), ml.getLong(3)));
		}
		return new GroupTrState(groupId, name, salt, creatorPub,
				creatorName, created, epoch, dissolved, members);
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
		LocalAuthor la = db.transactionWithResult(true,
				identityManager::getLocalAuthor);
		byte[] localPub = la.getPublicKey().getEncoded();
		PrivateKey signingKey = la.getPrivateKey();
		long timestamp = clock.currentTimeMillis();
		int epoch = (int) s.getEpoch();
		byte[] ctHash = crypto.hash(
				"org.briarproject.zerion/GROUP_POST_CT", body);
		byte[] signed = new byte[32 + 4 + 32 + ctHash.length];
		System.arraycopy(groupId, 0, signed, 0, 32);
		for (int i = 0; i < 4; i++) {
			signed[32 + i] = (byte) (epoch >>> ((3 - i) * 8));
		}
		System.arraycopy(localPub, 0, signed, 36, 32);
		System.arraycopy(ctHash, 0, signed, 68, ctHash.length);
		byte[] sig;
		try {
			sig = crypto.sign(
					"org.briarproject.zerion/GROUP_POST",
					signed, signingKey);
		} catch (GeneralSecurityException ex) {
			throw new DbException(ex);
		}
		db.transaction(false, txn -> {
			for (GroupTrMember m : s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), localPub)) continue;
				Contact c = findContactByPubKey(txn, m.getPubKey());
				if (c == null) continue;
				BdfList msgBody = autoDeleteTimerMs > 0
						? BdfList.of(32L, groupId, (long) epoch,
								localPub, body, sig,
								autoDeleteTimerMs)
						: BdfList.of(32L, groupId, (long) epoch,
								localPub, body, sig);
				dispatchToContact(txn, c, timestamp, msgBody);
			}
		});
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
		db.transaction(false, txn -> fanOut(txn, s, body, timestamp,
				null));
		applyLocalAdd(s, addedPubKey, addedName, timestamp, newEpoch);
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
