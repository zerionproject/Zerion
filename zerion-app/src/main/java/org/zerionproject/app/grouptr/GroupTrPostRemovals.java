package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupPostDeleteReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.app.api.messaging.MessagingManager.GROUP_ACTIONS_MIN_VERSION;
import static org.zerionproject.app.grouptr.GroupTrConstants.SIGNING_LABEL_GROUP_POST_DELETE;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
final class GroupTrPostRemovals {

	static final long KEPT_MS = 7L * 24L * 60L * 60L * 1000L;
	static final int MAX_PENDING_PER_GROUP = 500;
	static final int MAX_PENDING_PER_AUTHOR = 125;
	static final int MAX_DELETED_PER_GROUP = 500;
	static final byte SIGNED_INPUT_DOMAIN = (byte) 0x09;
	static final int SIGNED_INPUT_LENGTH = 32 + 4 + 32 + 32 + 8 + 1;

	static final String NAMESPACE_PREFIX = "grouptr-removed:";
	private static final String KEY_PENDING = "pending";
	private static final String KEY_DELETED = "deleted";
	private static final String KEY_READ_AT = "readAt";
	private static final String KEY_OWN = "own";

	static final class Copy {

		final byte[] identity;
		final byte[] sender;
		final boolean local;
		final boolean accepted;
		final long arrival;

		Copy(byte[] identity, byte[] sender, boolean local, boolean accepted,
				long arrival) {
			this.identity = identity;
			this.sender = sender;
			this.local = local;
			this.accepted = accepted;
			this.arrival = arrival;
		}
	}

	interface Signer {

		byte[] sign(String label, byte[] signed) throws DbException;

		void clear();
	}

	interface Host {

		ReentrantLock lockFor(byte[] groupId);

		@Nullable
		GroupTrState group(byte[] groupId) throws DbException;

		@Nullable
		byte[] contactPubKey(GroupTrState s, ContactId c);

		boolean signerKnown(GroupTrState s, byte[] pubKey) throws DbException;

		boolean verify(GroupTrState s, byte[] sig, String label,
				byte[] signed, byte[] pubKey);

		Signer signer(GroupTrState s) throws DbException;

		byte[] localKey(GroupTrState s) throws DbException;

		byte[] localKey(Transaction txn, byte[] groupId) throws DbException;

		@Nullable
		Copy describe(byte[] groupId, BdfDictionary meta, byte[] localPub);

		@Nullable
		Contact contactByPubKey(Transaction txn, GroupTrState s,
				byte[] pubKey) throws DbException;

		void dispatchRecord(Transaction txn, Contact c, long timestamp,
				BdfList body, BdfDictionary meta) throws DbException;

		void lowerUnread(byte[] groupId, int by);
	}

	private interface FoundCopies {

		void inTransaction(Transaction txn, Map<String, byte[]> found)
				throws DbException;
	}

	private final DatabaseComponent db;
	private final SettingsManager settingsManager;
	private final ClientHelper clientHelper;
	private final IdentityManager identityManager;
	private final ContactManager contactManager;
	private final MessagingManager messagingManager;
	private final EventBus eventBus;
	private final Clock clock;
	private final GroupTrPostCache posts;
	private final Host host;

	GroupTrPostRemovals(DatabaseComponent db, SettingsManager settingsManager,
			ClientHelper clientHelper, IdentityManager identityManager,
			ContactManager contactManager, MessagingManager messagingManager,
			EventBus eventBus, Clock clock, GroupTrPostCache posts,
			Host host) {
		this.db = db;
		this.settingsManager = settingsManager;
		this.clientHelper = clientHelper;
		this.identityManager = identityManager;
		this.contactManager = contactManager;
		this.messagingManager = messagingManager;
		this.eventBus = eventBus;
		this.clock = clock;
		this.posts = posts;
		this.host = host;
	}

	static byte[] signedInput(byte[] groupId, long epoch, byte[] senderPubKey,
			byte[] targetPostId, long timestamp) {
		if (groupId.length != 32 || senderPubKey.length != 32
				|| targetPostId.length != 32) {
			throw new IllegalArgumentException();
		}
		byte[] out = new byte[SIGNED_INPUT_LENGTH];
		System.arraycopy(groupId, 0, out, 0, 32);
		int e = (int) epoch;
		for (int i = 0; i < 4; i++) {
			out[32 + i] = (byte) (e >>> ((3 - i) * 8));
		}
		System.arraycopy(senderPubKey, 0, out, 36, 32);
		System.arraycopy(targetPostId, 0, out, 68, 32);
		for (int i = 0; i < 8; i++) {
			out[100 + i] = (byte) (timestamp >>> ((7 - i) * 8));
		}
		out[108] = SIGNED_INPUT_DOMAIN;
		return out;
	}

	void received(GroupPostDeleteReceivedEvent e) {
		byte[] groupId = e.getGroupId();
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.group(groupId);
			if (s == null || s.isDissolved()) return;
			byte[] deliverer = host.contactPubKey(s, e.getContactId());
			if (deliverer == null) return;
			byte[] author = e.getSenderPubKey();
			if (!GroupTrManagerImpl.groupPostDeliveryAccepted(s, author,
					deliverer)) {
				return;
			}
			if (!host.signerKnown(s, author)) return;
			byte[] signed = signedInput(groupId, e.getEpoch(), author,
					e.getTargetPostId(), e.getTimestamp());
			if (!host.verify(s, e.getRecordSig(),
					SIGNING_LABEL_GROUP_POST_DELETE, signed, author)) {
				return;
			}
			remove(groupId, author,
					Collections.singletonList(e.getTargetPostId()), true,
					null);
		} catch (DbException | RuntimeException ex) {
		} finally {
			lock.unlock();
		}
	}

	boolean dropsArrival(byte[] groupId, byte[] identity, byte[] sender) {
		String ns = namespace(groupId);
		try {
			Settings stored = settingsManager.getSettings(ns);
			long now = clock.currentTimeMillis();
			String id = toHexString(identity);
			String who = toHexString(sender);
			Entries deleted = Entries.parse(stored.get(KEY_DELETED), now);
			if (deleted.matches(id, who)) return true;
			Entries pending = Entries.parse(stored.get(KEY_PENDING), now);
			if (!pending.matches(id, who)) return false;
			pending.remove(id);
			deleted.add(id, who, now + KEPT_MS, MAX_DELETED_PER_GROUP);
			Settings update = new Settings();
			update.put(KEY_PENDING, pending.join());
			update.put(KEY_DELETED, deleted.join());
			settingsManager.mergeSettings(update, ns);
			return true;
		} catch (DbException ex) {
			return false;
		}
	}

	int deleteForEveryone(byte[] groupId, Collection<byte[]> postIds)
			throws DbException {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.group(groupId);
			if (s == null) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.GROUP_NOT_FOUND);
			}
			if (s.isDissolved()) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.GROUP_DISSOLVED);
			}
			byte[] localPub = host.localKey(s);
			if (!isMember(s, localPub)) {
				throw new GroupTrAuthException(
						GroupTrAuthException.Reason.NOT_A_MEMBER);
			}
			long timestamp = clock.currentTimeMillis();
			long epoch = s.getEpoch();
			int[] keeping = {0};
			Signer signer = host.signer(s);
			try {
				remove(groupId, localPub, postIds, false, (txn, found) -> {
					if (found.isEmpty()) return;
					List<byte[]> targets = new ArrayList<>(found.values());
					List<BdfList> records = new ArrayList<>(targets.size());
					for (byte[] target : targets) {
						byte[] sig = signer.sign(SIGNING_LABEL_GROUP_POST_DELETE,
								signedInput(groupId, epoch, localPub, target,
										timestamp));
						records.add(BdfList.of(
								(long) MessageTypes.GROUP_POST_DELETE, groupId,
								epoch, localPub, target, timestamp, sig));
					}
					for (GroupTrMember m : s.getMembers()) {
						if (Arrays.equals(m.getPubKey(), localPub)) continue;
						Contact c = host.contactByPubKey(txn, s, m.getPubKey());
						if (c == null) continue;
						if (messagingManager.getContactClientMinorVersion(txn,
								c.getId()) < GROUP_ACTIONS_MIN_VERSION) {
							keeping[0]++;
							continue;
						}
						for (int i = 0; i < records.size(); i++) {
							host.dispatchRecord(txn, c, timestamp, records.get(i),
									recordMetadata(groupId, targets.get(i),
											timestamp));
						}
					}
					String ns = namespace(groupId);
					Entries own = Entries.parse(
							settingsManager.getSettings(txn, ns).get(KEY_OWN),
							timestamp);
					for (byte[] target : targets) {
						own.add(toHexString(target), epoch + "_" + timestamp,
								timestamp + KEPT_MS, MAX_DELETED_PER_GROUP);
					}
					Settings update = new Settings();
					update.put(KEY_OWN, own.join());
					settingsManager.mergeSettings(txn, update, ns);
				});
			} finally {
				signer.clear();
			}
			return keeping[0];
		} finally {
			lock.unlock();
		}
	}

	void resendOwnDeletes(byte[] groupId, ContactId contactId)
			throws DbException {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			GroupTrState s = host.group(groupId);
			if (s == null || s.isDissolved()) return;
			byte[] memberPub = host.contactPubKey(s, contactId);
			if (memberPub == null || !isMember(s, memberPub)) return;
			byte[] localPub = host.localKey(s);
			if (!isMember(s, localPub)) return;
			long now = clock.currentTimeMillis();
			Entries own = Entries.parse(settingsManager.getSettings(
					namespace(groupId)).get(KEY_OWN), now);
			if (own.isEmpty()) return;
			Signer signer = host.signer(s);
			try {
				db.transaction(false, txn -> {
					Contact c = host.contactByPubKey(txn, s, memberPub);
					if (c == null) return;
					if (messagingManager.getContactClientMinorVersion(txn,
							c.getId()) < GROUP_ACTIONS_MIN_VERSION) {
						return;
					}
					for (String[] e : own.list()) {
						String[] at = e[1].split("_");
						if (at.length != 2) continue;
						long epoch;
						long timestamp;
						try {
							epoch = Long.parseLong(at[0]);
							timestamp = Long.parseLong(at[1]);
						} catch (NumberFormatException ex) {
							continue;
						}
						byte[] target;
						try {
							target = fromHexString(e[0]);
						} catch (FormatException ex) {
							continue;
						}
						byte[] sig = signer.sign(SIGNING_LABEL_GROUP_POST_DELETE,
								signedInput(groupId, epoch, localPub, target,
										timestamp));
						host.dispatchRecord(txn, c, now, BdfList.of(
								(long) MessageTypes.GROUP_POST_DELETE, groupId,
								epoch, localPub, target, timestamp, sig),
								recordMetadata(groupId, target, timestamp));
					}
				});
			} finally {
				signer.clear();
			}
		} finally {
			lock.unlock();
		}
	}

	void deleteForMe(byte[] groupId, Collection<byte[]> postIds)
			throws DbException {
		ReentrantLock lock = host.lockFor(groupId);
		lock.lock();
		try {
			remove(groupId, null, postIds, false, null);
		} finally {
			lock.unlock();
		}
	}

	int countMembers(byte[] groupId, boolean supporting) throws DbException {
		GroupTrState s = host.group(groupId);
		if (s == null) return 0;
		return db.transactionWithResult(true, txn -> {
			byte[] localPub = host.localKey(txn, groupId);
			int n = 0;
			for (GroupTrMember m : s.getMembers()) {
				if (Arrays.equals(m.getPubKey(), localPub)) continue;
				Contact c = host.contactByPubKey(txn, s, m.getPubKey());
				if (c == null) continue;
				boolean actions = messagingManager.getContactClientMinorVersion(
						txn, c.getId()) >= GROUP_ACTIONS_MIN_VERSION;
				if (actions == supporting) n++;
			}
			return n;
		});
	}

	void noteRead(byte[] groupId) {
		Settings update = new Settings();
		update.putLong(KEY_READ_AT, clock.currentTimeMillis());
		try {
			settingsManager.mergeSettings(update, namespace(groupId));
		} catch (DbException ex) {
		}
	}

	void noteReadBefore(Transaction txn, byte[] groupId, long acceptedFrom)
			throws DbException {
		String ns = namespace(groupId);
		String readAt = settingsManager.getSettings(txn, ns).get(KEY_READ_AT);
		if (readAt != null && !readAt.isEmpty()) return;
		Settings update = new Settings();
		update.putLong(KEY_READ_AT, acceptedFrom - 1L);
		settingsManager.mergeSettings(txn, update, ns);
	}

	void forget(byte[] groupId) throws DbException {
		settingsManager.deleteNamespaces(
				java.util.Collections.singletonList(namespace(groupId)));
	}

	private void remove(byte[] groupId, @Nullable byte[] author,
			Collection<byte[]> targets, boolean pendIfMissing,
			@Nullable FoundCopies beforeRemoval)
			throws DbException {
		Map<String, byte[]> wanted = new LinkedHashMap<>();
		for (byte[] t : targets) wanted.put(toHexString(t), t);
		if (wanted.isEmpty()) return;
		String key = toHexString(groupId);
		long now = clock.currentTimeMillis();
		Map<String, byte[]> foundStored = new LinkedHashMap<>();
		Map<String, String> senders = new HashMap<>();
		Set<String> found = new HashSet<>();
		Set<String> unread = new HashSet<>();
		for (Map.Entry<String, byte[]> e
				: posts.removePosts(key, wanted.keySet(), author).entrySet()) {
			found.add(e.getKey());
			senders.put(e.getKey(), toHexString(e.getValue()));
		}
		for (GroupTrPostCache.BufferedPost b
				: posts.removeBuffered(key, wanted.keySet(), author)) {
			String hex = toHexString(b.identity);
			found.add(hex);
			senders.put(hex, toHexString(b.post.getSenderPubKey()));
		}
		List<byte[]> removed = new ArrayList<>();
		try {
			removeStored(groupId, author, wanted, pendIfMissing, beforeRemoval,
					now, foundStored, senders, found, unread, removed);
		} catch (DbException | RuntimeException ex) {
			posts.forgetHistory(key);
			throw ex;
		}
		if (!unread.isEmpty()) host.lowerUnread(groupId, unread.size());
		if (!removed.isEmpty()) {
			eventBus.broadcast(new GroupTrPostsRemovedEvent(groupId, removed,
					unread.size()));
		}
	}

	private void removeStored(byte[] groupId, @Nullable byte[] author,
			Map<String, byte[]> wanted, boolean pendIfMissing,
			@Nullable FoundCopies beforeRemoval, long now,
			Map<String, byte[]> foundStored, Map<String, String> senders,
			Set<String> found, Set<String> unread, List<byte[]> removed)
			throws DbException {
		String ns = namespace(groupId);
		List<MessageId> copies = new ArrayList<>();
		db.transaction(false, txn -> {
			byte[] localPub = host.localKey(txn, groupId);
			Settings stored = settingsManager.getSettings(txn, ns);
			long readAt = stored.getLong(KEY_READ_AT, Long.MAX_VALUE);
			BdfDictionary query = new BdfDictionary();
			query.put("messageType", (long) MessageTypes.GROUP_POST);
			query.put("groupId", groupId);
			if (author != null) query.put("groupSenderPubKey", author);
			for (Contact c : contactManager.getContacts(txn)) {
				GroupId cg = messagingManager.getContactGroup(c).getId();
				Collection<MessageId> ids;
				try {
					ids = clientHelper.getMessageIds(txn, cg, query);
				} catch (FormatException ex) {
					continue;
				}
				for (MessageId id : ids) {
					BdfDictionary meta;
					try {
						meta = clientHelper.getMessageMetadataAsDictionary(txn,
								id);
					} catch (NoSuchMessageException | FormatException ex) {
						continue;
					}
					Copy copy = host.describe(groupId, meta, localPub);
					if (copy == null) continue;
					String hex = toHexString(copy.identity);
					if (!wanted.containsKey(hex)) continue;
					if (author != null && !Arrays.equals(copy.sender, author)) {
						continue;
					}
					if (!copy.local && copy.accepted && copy.arrival > readAt) {
						unread.add(hex);
					}
					foundStored.put(hex, wanted.get(hex));
					senders.put(hex, toHexString(copy.sender));
					copies.add(id);
				}
			}
			if (beforeRemoval != null) {
				beforeRemoval.inTransaction(txn, foundStored);
			}
			for (MessageId id : copies) {
				try {
					db.removeMessage(txn, id);
				} catch (NoSuchMessageException gone) {
				}
			}
			found.addAll(foundStored.keySet());
			Entries deleted = Entries.parse(stored.get(KEY_DELETED), now);
			Entries pending = Entries.parse(stored.get(KEY_PENDING), now);
			boolean changed = false;
			for (String hex : wanted.keySet()) {
				String who = senders.get(hex);
				if (who == null && author != null) who = toHexString(author);
				if (who == null) continue;
				if (found.contains(hex)) {
					pending.remove(hex);
					deleted.add(hex, who, now + KEPT_MS,
							MAX_DELETED_PER_GROUP);
					changed = true;
				} else if (pendIfMissing && !deleted.contains(hex)) {
					pending.add(hex, who, now + KEPT_MS,
							MAX_PENDING_PER_AUTHOR, MAX_PENDING_PER_GROUP);
					changed = true;
				}
			}
			if (changed) {
				Settings update = new Settings();
				update.put(KEY_DELETED, deleted.join());
				update.put(KEY_PENDING, pending.join());
				settingsManager.mergeSettings(txn, update, ns);
			}
			for (String hex : wanted.keySet()) {
				if (found.contains(hex)) removed.add(wanted.get(hex));
			}
		});
	}

	private static BdfDictionary recordMetadata(byte[] groupId,
			byte[] targetPostId, long timestamp) {
		BdfDictionary meta = new BdfDictionary();
		meta.put("messageType", (long) MessageTypes.GROUP_POST_DELETE);
		meta.put("groupId", groupId);
		meta.put("groupTargetPostId", targetPostId);
		meta.put("timestamp", timestamp);
		meta.put("local", true);
		return meta;
	}

	private static boolean isMember(GroupTrState s, byte[] pubKey) {
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pubKey)) return true;
		}
		return false;
	}

	private static String namespace(byte[] groupId) {
		return NAMESPACE_PREFIX + toHexString(groupId);
	}

	static final class Entries {

		private final LinkedHashMap<String, String[]> byTarget =
				new LinkedHashMap<>();

		static Entries parse(@Nullable String stored, long now) {
			Entries out = new Entries();
			if (stored == null || stored.isEmpty()) return out;
			for (String entry : stored.split(",")) {
				String[] parts = entry.split("\\.");
				if (parts.length != 3) continue;
				long keptUntil;
				try {
					keptUntil = Long.parseLong(parts[2]);
				} catch (NumberFormatException ex) {
					continue;
				}
				if (keptUntil <= now) continue;
				out.byTarget.put(parts[0], new String[] {parts[1], parts[2]});
			}
			return out;
		}

		boolean contains(String target) {
			return byTarget.containsKey(target);
		}

		boolean isEmpty() {
			return byTarget.isEmpty();
		}

		List<String[]> list() {
			List<String[]> out = new ArrayList<>(byTarget.size());
			for (Map.Entry<String, String[]> e : byTarget.entrySet()) {
				out.add(new String[] {e.getKey(), e.getValue()[0]});
			}
			return out;
		}

		boolean matches(String target, String sender) {
			String[] e = byTarget.get(target);
			return e != null && e[0].equals(sender);
		}

		void remove(String target) {
			byTarget.remove(target);
		}

		void add(String target, String sender, long keptUntil, int max) {
			byTarget.remove(target);
			byTarget.put(target,
					new String[] {sender, Long.toString(keptUntil)});
			Iterator<String> it = byTarget.keySet().iterator();
			while (byTarget.size() > max && it.hasNext()) {
				it.next();
				it.remove();
			}
		}

		void add(String target, String sender, long keptUntil,
				int maxPerSender, int max) {
			byTarget.remove(target);
			byTarget.put(target,
					new String[] {sender, Long.toString(keptUntil)});
			while (count(sender) > maxPerSender) removeOldest(sender);
			while (byTarget.size() > max) removeOldest(busiestSender());
		}

		private int count(String sender) {
			int n = 0;
			for (String[] e : byTarget.values()) {
				if (e[0].equals(sender)) n++;
			}
			return n;
		}

		private String busiestSender() {
			Map<String, Integer> counts = new HashMap<>();
			String busiest = "";
			int most = 0;
			for (String[] e : byTarget.values()) {
				int n = counts.merge(e[0], 1, Integer::sum);
				if (n > most) {
					most = n;
					busiest = e[0];
				}
			}
			return busiest;
		}

		private void removeOldest(String sender) {
			Iterator<String[]> it = byTarget.values().iterator();
			while (it.hasNext()) {
				if (it.next()[0].equals(sender)) {
					it.remove();
					return;
				}
			}
		}

		String join() {
			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, String[]> e : byTarget.entrySet()) {
				if (sb.length() > 0) sb.append(',');
				sb.append(e.getKey()).append('.').append(e.getValue()[0])
						.append('.').append(e.getValue()[1]);
			}
			return sb.toString();
		}
	}
}
