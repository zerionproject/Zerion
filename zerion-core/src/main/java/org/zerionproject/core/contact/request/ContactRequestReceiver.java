package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.event.ContactRequestAcceptedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestAddedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestExpiredEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestReceivedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestWithdrawnEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestsChangedEvent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridCommitmentPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.ReservedNames;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.util.StringUtils;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION_HYBRID;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.ID_LABEL;
import static org.zerionproject.core.api.contact.request.ContactLink.KIND_PERMANENT;
import static org.zerionproject.core.api.contact.request.ContactLink.KIND_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ACCEPT_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.CHURN_LIMIT_PER_HOUR;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.CHURN_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REPLACEMENT_EVENT_INTERVAL_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REPLAY_ROWS_PER_UNDECIDED;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DELIVERED_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_REQUEST;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_WITHDRAW;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_BODY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_ID_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_UNDECIDED_PERMANENT;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_UNDECIDED_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.RENDEZVOUS_MODE_PUBLISH_ONLY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REPLAY_RECORD_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.UNDECIDED_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.UNPUBLISH_GRACE_MS;

@Singleton
@ThreadSafe
@NotNullByDefault
public class ContactRequestReceiver {

	private final DatabaseComponent db;
	private final ContactLinkStore links;
	private final ContactRequestCrypto crypto;
	private final IdentityManager identityManager;
	private final Clock clock;

	private final Map<String, ArrayDeque<Long>> churn =
			new ConcurrentHashMap<>();
	private final Map<String, Long> replacementEvents =
			new ConcurrentHashMap<>();

	@Inject
	public ContactRequestReceiver(DatabaseComponent db, ContactLinkStore links,
			ContactRequestCrypto crypto, IdentityManager identityManager,
			Clock clock) {
		this.db = db;
		this.links = links;
		this.crypto = crypto;
		this.identityManager = identityManager;
		this.clock = clock;
	}

	InboxSession.Receiver forLink(byte[] linkId) {
		return new InboxSession.Receiver() {
			@Override
			public boolean isFull() throws DbException {
				return ContactRequestReceiver.this.isFull(linkId);
			}

			@Override
			@Nullable
			public byte[] handle(RequestPlaintext p, SecretKey mk,
					byte[] helloNonce, long receivedAt, long admittedAt)
					throws DbException {
				return ContactRequestReceiver.this.handle(linkId, p, mk,
						helloNonce, receivedAt, admittedAt);
			}
		};
	}

	boolean isFull(byte[] linkId) throws DbException {
		return db.transactionWithResult(true, txn -> {
			ContactLink link = links.getLink(txn, linkId);
			if (link == null) return true;
			int cap = link.getKind() == KIND_PERMANENT
					? MAX_UNDECIDED_PERMANENT : MAX_UNDECIDED_ZERO_ID;
			int undecided = 0;
			for (IncomingRequest r : db.getIncomingRequests(txn)) {
				if (r.isUndecided() && Arrays.equals(r.getLinkId(), linkId)) {
					undecided++;
				}
			}
			if (undecided >= cap) return true;
			if (db.countRequestReplay(txn, linkId)
					>= REPLAY_ROWS_PER_UNDECIDED * cap) {
				return true;
			}
			return churnCount(linkId, clock.currentTimeMillis())
					>= CHURN_LIMIT_PER_HOUR;
		});
	}

	private int churnCount(byte[] linkId, long now) {
		ArrayDeque<Long> times = churn.get(StringUtils.toHexString(linkId));
		if (times == null) return 0;
		synchronized (times) {
			while (!times.isEmpty()
					&& times.peekFirst() <= now - CHURN_WINDOW_MS) {
				times.removeFirst();
			}
			return times.size();
		}
	}

	private void recordChurn(byte[] linkId, long now) {
		ArrayDeque<Long> times = churn.computeIfAbsent(
				StringUtils.toHexString(linkId), k -> new ArrayDeque<>());
		synchronized (times) {
			times.addLast(now);
		}
	}

	private boolean replacementEventDue(byte[] linkId, byte[] tag, long now) {
		replacementEvents.values().removeIf(
				t -> t <= now - REPLACEMENT_EVENT_INTERVAL_MS);
		String key = StringUtils.toHexString(linkId) + ":"
				+ StringUtils.toHexString(tag);
		Long last = replacementEvents.get(key);
		if (last != null && now - last < REPLACEMENT_EVENT_INTERVAL_MS) {
			return false;
		}
		replacementEvents.put(key, now);
		return true;
	}

	@Nullable
	byte[] handle(byte[] linkId, RequestPlaintext p, SecretKey mk,
			byte[] helloNonce, long receivedAt) throws DbException {
		return handle(linkId, p, mk, helloNonce, receivedAt, 0);
	}

	static boolean admittedWhileActive(ContactLink link, long admittedAt,
			long now) {
		if (admittedAt <= 0) return false;
		long endedAt;
		if (link.getState() == ContactLink.STATE_RETIRED) {
			endedAt = link.getRetiredAt();
		} else if (link.getState() == ContactLink.STATE_WITHDRAWN) {
			endedAt = link.getWithdrawnAt();
		} else {
			return false;
		}
		return endedAt > 0 && endedAt >= admittedAt
				&& now - endedAt <= UNPUBLISH_GRACE_MS;
	}

	@Nullable
	byte[] handle(byte[] linkId, RequestPlaintext p, SecretKey mk,
			byte[] helloNonce, long receivedAt, long admittedAt)
			throws DbException {
		byte[] ack = crypto.ack(mk, p.requestId, p.kind);
		boolean accepted = db.transactionWithResult(false, txn -> {
			ContactLink link = links.getLink(txn, linkId);
			if (link == null || link.getHybridPublicKey() == null) {
				return false;
			}
			byte[] idHash = crypto.requestIdHash(p.requestId);
			if (p.kind == KIND_REQUEST
					&& link.getState() != ContactLink.STATE_ACTIVE
					&& !admittedWhileActive(link, admittedAt, receivedAt)) {
				return false;
			}
			if (p.kind == KIND_WITHDRAW) {
				withdraw(txn, linkId, p.requestId, idHash, receivedAt);
				return true;
			}
			if (p.kind != KIND_REQUEST) return false;
			if (db.containsRequestReplay(txn, idHash, KIND_REQUEST)
					|| db.containsRequestReplay(txn, idHash, KIND_WITHDRAW)) {
				return true;
			}
			byte[] oneTimeLink = p.oneTimeLink;
			byte[] pop = p.pop;
			byte[] tag = p.senderTag;
			if (oneTimeLink == null || pop == null || tag == null) {
				return false;
			}
			byte[] otkHash = crypto.oneTimeKeyHash(oneTimeLink);
			if (db.containsOneTimeKey(txn, otkHash, idHash)) return false;
			byte[] oneTimeX = Arrays.copyOfRange(oneTimeLink,
					1 + LINK_ID_BYTES, LINK_BODY_BYTES);
			KeyPair linkKeys = links.keyPair(link);
			try {
				if (!crypto.verifyProofOfPossession(pop, oneTimeX, linkKeys,
						helloNonce, p.requestId)) {
					return false;
				}
			} finally {
				((HybridAgreementPrivateKey) linkKeys.getPrivate()).clear();
			}
			if (isKnownKey(txn, oneTimeLink)) return false;
			db.addRequestReplay(txn, idHash, KIND_REQUEST, otkHash, linkId,
					receivedAt + REPLAY_RECORD_MS);
			if (db.containsRequestBlock(txn, linkId, tag)) {
				recordChurn(linkId, receivedAt);
				return true;
			}
			boolean replaced = false;
			for (IncomingRequest old : db.getIncomingRequests(txn)) {
				if (old.isUndecided()
						&& Arrays.equals(old.getLinkId(), linkId)
						&& MessageDigest.isEqual(old.getSenderTag(), tag)) {
					db.removeIncomingRequest(txn, old.getRequestId());
					replaced = true;
				}
			}
			if (replaced) recordChurn(linkId, receivedAt);
			String name = p.name == null ? null : sanitizeName(p.name);
			String note = p.note == null ? null : sanitize(p.note);
			if (note != null && note.isEmpty()) note = null;
			db.addIncomingRequest(txn, new IncomingRequest(p.requestId,
					linkId, oneTimeLink, name, note, tag, p.timestamp,
					receivedAt, IncomingRequest.STATE_RECEIVED, 0, null));
			if (replaced) {
				if (replacementEventDue(linkId, tag, receivedAt)) {
					txn.attach(new ContactRequestReceivedEvent(p.requestId,
							false));
				} else {
					txn.attach(new ContactRequestsChangedEvent());
				}
			} else {
				replacementEventDue(linkId, tag, receivedAt);
				txn.attach(new ContactRequestReceivedEvent(p.requestId, false));
			}
			return true;
		});
		return accepted ? ack : null;
	}

	private void withdraw(Transaction txn, byte[] linkId, byte[] requestId,
			byte[] idHash, long receivedAt) throws DbException {
		IncomingRequest r = find(txn, requestId);
		if (r == null || !Arrays.equals(r.getLinkId(), linkId)) return;
		if (!db.containsRequestReplay(txn, idHash, KIND_WITHDRAW)) {
			db.addRequestReplay(txn, idHash, KIND_WITHDRAW, null, linkId,
					receivedAt + REPLAY_RECORD_MS);
		}
		if (r.isUndecided()) {
			db.removeIncomingRequest(txn, requestId);
			links.removeUnneededLinks(txn);
			txn.attach(new ContactRequestWithdrawnEvent(requestId, false));
		} else if (r.getState() == IncomingRequest.STATE_ACCEPTED) {
			removePending(txn, r.getPendingContactId());
			db.updateIncomingRequest(txn, r.withdrawn());
			links.removeUnneededLinks(txn);
			txn.attach(new ContactRequestWithdrawnEvent(requestId, false));
		}
	}

	private boolean isKnownKey(Transaction txn, byte[] oneTimeLink)
			throws DbException {
		byte[] commitment = Arrays.copyOfRange(oneTimeLink, 1,
				1 + LINK_ID_BYTES);
		byte[] x = Arrays.copyOfRange(oneTimeLink, 1 + LINK_ID_BYTES,
				LINK_BODY_BYTES);
		byte[] blob = Arrays.copyOfRange(oneTimeLink, 1, LINK_BODY_BYTES);
		if (links.isOwnLinkKey(txn, commitment, x)) return true;
		for (PendingContact pc : db.getPendingContacts(txn)) {
			byte[] key = pc.getPublicKey().getEncoded();
			if (Arrays.equals(key, blob) || Arrays.equals(key, x)) return true;
			if (key.length == blob.length && Arrays.equals(
					Arrays.copyOfRange(key, LINK_ID_BYTES, key.length), x)) {
				return true;
			}
		}
		for (Contact c : db.getContacts(txn)) {
			PublicKey k = c.getHandshakePublicKey();
			if (k != null && Arrays.equals(k.getEncoded(), x)) return true;
		}
		return false;
	}

	static String sanitize(String s) {
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.getType(cp) == Character.CONTROL) continue;
			if (isBidiControl(cp)) continue;
			sb.appendCodePoint(cp);
		}
		return sb.toString();
	}

	@Nullable
	static String sanitizeName(String name) {
		String clean = sanitize(name).trim();
		if (clean.isEmpty() || ReservedNames.isReserved(clean)) return null;
		return clean;
	}

	private static boolean isBidiControl(int cp) {
		return (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)
				|| cp == 0x200E || cp == 0x200F || cp == 0x061C;
	}

	@Nullable
	private IncomingRequest find(Transaction txn, byte[] requestId)
			throws DbException {
		for (IncomingRequest r : db.getIncomingRequests(txn)) {
			if (Arrays.equals(r.getRequestId(), requestId)) return r;
		}
		return null;
	}

	private void removePending(Transaction txn, @Nullable PendingContactId p)
			throws DbException {
		if (p == null) return;
		for (PendingContact pc : db.getPendingContacts(txn)) {
			if (pc.getId().equals(p)) {
				db.removePendingContact(txn, p);
				return;
			}
		}
	}

	public void markOpened(byte[] requestId) throws DbException {
		db.transaction(false, txn -> {
			IncomingRequest r = find(txn, requestId);
			if (r != null && r.getState() == IncomingRequest.STATE_RECEIVED) {
				db.updateIncomingRequest(txn, r.opened());
				txn.attach(new ContactRequestsChangedEvent());
			}
		});
	}

	public PendingContact accept(byte[] requestId, String alias)
			throws DbException {
		if (ReservedNames.isReserved(alias)) {
			throw new IllegalArgumentException();
		}
		return db.transactionWithResult(false, txn -> {
			IncomingRequest r = find(txn, requestId);
			if (r == null || !r.isUndecided()) {
				throw new IllegalStateException();
			}
			ContactLink link = links.getLink(txn, r.getLinkId());
			if (link == null || link.getHybridPublicKey() == null) {
				throw new IllegalStateException();
			}
			long now = clock.currentTimeMillis();
			PendingContact p = pendingFor(r, alias, now,
					expiryFor(r, now));
			db.addPendingContact(txn, p,
					identityManager.getLocalAuthor(txn).getId(), false);
			KeyPair keys = links.keyPair(link);
			try {
				db.setPendingContactOurKeys(txn, p.getId(),
						keys.getPublic().getEncoded().clone(),
						keys.getPrivate().getEncoded().clone());
			} finally {
				((HybridAgreementPrivateKey) keys.getPrivate()).clear();
			}
			db.updateIncomingRequest(txn, r.accepted(now, p.getId()));
			if (link.getKind() == KIND_ZERO_ID
					&& link.getState() == ContactLink.STATE_ACTIVE) {
				links.rotateZeroIdAfterAccept(txn, link);
			}
			txn.attach(new ContactRequestAcceptedEvent(requestId, false));
			return p;
		});
	}

	static long expiryFor(IncomingRequest r, long now) {
		return Math.min(now + ACCEPT_WINDOW_MS,
				r.getReceivedAt() + DELIVERED_WINDOW_MS);
	}

	private PendingContact pendingFor(IncomingRequest r, String alias,
			long now, long expiry) {
		byte[] blob = Arrays.copyOfRange(r.getOneTimeLink(), 1,
				LINK_BODY_BYTES);
		PendingContactId id = new PendingContactId(
				crypto.getCrypto().hash(ID_LABEL, blob));
		return new PendingContact(id, new HybridCommitmentPublicKey(blob),
				alias, now, FORMAT_VERSION_HYBRID, expiry,
				RENDEZVOUS_MODE_PUBLISH_ONLY);
	}

	public void retryAccepted(byte[] requestId) throws DbException {
		db.transaction(false, txn -> {
			IncomingRequest r = find(txn, requestId);
			if (r == null || r.getState() != IncomingRequest.STATE_ACCEPTED
					|| r.getPendingContactId() == null) {
				throw new IllegalStateException();
			}
			long now = clock.currentTimeMillis();
			long expiry = expiryFor(r, now);
			if (expiry <= now) throw new IllegalStateException();
			ContactLink link = links.getLink(txn, r.getLinkId());
			if (link == null || link.getHybridPublicKey() == null) {
				throw new IllegalStateException();
			}
			PendingContact old = null;
			for (PendingContact pc : db.getPendingContacts(txn)) {
				if (pc.getId().equals(r.getPendingContactId())) old = pc;
			}
			String alias = old == null ? "" : old.getAlias();
			if (old != null) db.removePendingContact(txn, old.getId());
			if (alias.isEmpty()) throw new IllegalStateException();
			PendingContact p = pendingFor(r, alias, now, expiry);
			db.addPendingContact(txn, p,
					identityManager.getLocalAuthor(txn).getId(), false);
			KeyPair keys = links.keyPair(link);
			try {
				db.setPendingContactOurKeys(txn, p.getId(),
						keys.getPublic().getEncoded().clone(),
						keys.getPrivate().getEncoded().clone());
			} finally {
				((HybridAgreementPrivateKey) keys.getPrivate()).clear();
			}
			txn.attach(new ContactRequestsChangedEvent());
		});
	}

	public void decline(byte[] requestId) throws DbException {
		db.transaction(false, txn -> {
			IncomingRequest r = find(txn, requestId);
			if (r == null || !r.isUndecided()) return;
			db.removeIncomingRequest(txn, requestId);
			links.removeUnneededLinks(txn);
			txn.attach(new ContactRequestsChangedEvent());
		});
	}

	public void block(byte[] requestId) throws DbException {
		db.transaction(false, txn -> {
			IncomingRequest r = find(txn, requestId);
			if (r == null || !r.isUndecided()) return;
			db.addRequestBlock(txn, r.getLinkId(), r.getSenderTag(),
					clock.currentTimeMillis());
			db.removeIncomingRequest(txn, requestId);
			links.removeUnneededLinks(txn);
			txn.attach(new ContactRequestsChangedEvent());
		});
	}

	public void dismiss(byte[] requestId) throws DbException {
		db.transaction(false, txn -> {
			IncomingRequest r = find(txn, requestId);
			if (r == null || r.getState() != IncomingRequest.STATE_WITHDRAWN) {
				return;
			}
			db.removeIncomingRequest(txn, requestId);
			links.removeUnneededLinks(txn);
			txn.attach(new ContactRequestsChangedEvent());
		});
	}

	void convertingPendingContact(Transaction txn, PendingContactId p,
			ContactId c) throws DbException {
		for (IncomingRequest r : db.getIncomingRequests(txn)) {
			if (p.equals(r.getPendingContactId())) {
				db.removeIncomingRequest(txn, r.getRequestId());
				links.removeUnneededLinks(txn);
				txn.attach(new ContactRequestsChangedEvent());
				txn.attach(new ContactRequestAddedEvent(r.getRequestId(),
						false, c));
			}
		}
	}

	public void maintain() throws DbException {
		long now = clock.currentTimeMillis();
		db.transaction(false, txn -> {
			db.removeExpiredRequestReplay(txn, now);
			boolean changed = false;
			for (IncomingRequest r : db.getIncomingRequests(txn)) {
				if (r.isUndecided()
						&& now >= r.getReceivedAt() + UNDECIDED_WINDOW_MS) {
					db.removeIncomingRequest(txn, r.getRequestId());
					txn.attach(new ContactRequestExpiredEvent(
							r.getRequestId(), false));
					changed = true;
				} else if (!r.isUndecided()
						&& now >= r.getReceivedAt() + DELIVERED_WINDOW_MS) {
					removePending(txn, r.getPendingContactId());
					db.removeIncomingRequest(txn, r.getRequestId());
					txn.attach(new ContactRequestExpiredEvent(
							r.getRequestId(), false));
					changed = true;
				}
			}
			if (changed) links.removeUnneededLinks(txn);
		});
	}
}
