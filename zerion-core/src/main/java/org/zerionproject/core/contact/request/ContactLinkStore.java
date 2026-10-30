package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkLimitException;
import org.zerionproject.core.api.contact.request.FloodNotice;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.event.ContactLinkFloodedEvent;
import org.zerionproject.core.api.contact.request.event.ContactLinksChangedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.plugin.tor.TorRendezvousCrypto;
import org.zerionproject.core.plugin.tor.TorRendezvousCryptoImpl;
import org.zerionproject.core.util.StringUtils;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.api.contact.request.ContactLink.KIND_PERMANENT;
import static org.zerionproject.core.api.contact.request.ContactLink.KIND_PER_ADD_BINDING;
import static org.zerionproject.core.api.contact.request.ContactLink.KIND_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactLink.STATE_ACTIVE;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ACCOUNT_TAG_SECRET_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_NOTICE_SLOTS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_PAUSE_MAX_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_PAUSE_MIN_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_PAUSE_RESET_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.INBOX_KEY_SEED_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_TYPE_REQUEST_ONLY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_TYPE_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_LABEL_CODE_POINTS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_PERMANENT_LINKS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.PER_ADD_BINDING_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.X25519_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ZERO_ID_OPEN_MS;

@Singleton
@ThreadSafe
@NotNullByDefault
public class ContactLinkStore {

	static final String SETTINGS_NAMESPACE =
			"org.zerionproject.contactrequest";
	static final String KEY_PAUSED = "pausedUntilConfirmed";
	static final String KEY_FLOOD_PREFIX = "flood.";
	static final String KEY_FLOOD_ZERO_ID = KEY_FLOOD_PREFIX + "zeroId";
	static final String KEY_FLOOD_LINK = ".link";
	static final String KEY_FLOOD_AT = ".at";
	static final String KEY_FLOOD_LABEL = ".label";
	static final String KEY_FLOOD_PAUSED_UNTIL = ".pausedUntil";
	static final String KEY_FLOOD_COUNT = ".count";
	static final String KEY_FLOOD_LAST_AT = ".lastAt";

	private final DatabaseComponent db;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;
	private final InviteLinkCodec codec;
	private final Clock clock;
	private final TorRendezvousCrypto onionCrypto;

	private final java.util.Set<String> powInboxes =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final java.util.Map<String, Integer> held =
			new java.util.HashMap<>();

	@Inject
	public ContactLinkStore(DatabaseComponent db, CryptoComponent crypto,
			IdentityManager identityManager, InviteLinkCodec codec,
			Clock clock) {
		this.db = db;
		this.crypto = crypto;
		this.identityManager = identityManager;
		this.codec = codec;
		this.clock = clock;
		onionCrypto = new TorRendezvousCryptoImpl(crypto);
	}

	public InviteLinkCodec getCodec() {
		return codec;
	}

	public Collection<ContactLink> getLinks(Transaction txn)
			throws DbException {
		return db.getContactLinks(txn);
	}

	@Nullable
	public ContactLink getLink(Transaction txn, byte[] linkId)
			throws DbException {
		for (ContactLink l : db.getContactLinks(txn)) {
			if (Arrays.equals(l.getLinkId(), linkId)) return l;
		}
		return null;
	}

	@Nullable
	public ContactLink getZeroId(Transaction txn) throws DbException {
		for (ContactLink l : db.getContactLinks(txn)) {
			if (l.getKind() == KIND_ZERO_ID && l.getState() == STATE_ACTIVE) {
				return l;
			}
		}
		return null;
	}

	public ContactLink getOrCreateZeroId(Transaction txn) throws DbException {
		ContactLink z = getZeroId(txn);
		if (z != null) return z;
		return create(txn, KIND_ZERO_ID, null);
	}

	public ContactLink createPermanent(Transaction txn, String label)
			throws DbException, ContactLinkLimitException {
		String trimmed = label.trim();
		int points = trimmed.codePointCount(0, trimmed.length());
		if (points < 1 || points > MAX_LABEL_CODE_POINTS) {
			throw new IllegalArgumentException();
		}
		int active = 0;
		for (ContactLink l : db.getContactLinks(txn)) {
			if (l.getKind() == KIND_PERMANENT
					&& l.getState() == STATE_ACTIVE) {
				active++;
			}
		}
		if (active >= MAX_PERMANENT_LINKS) {
			throw new ContactLinkLimitException();
		}
		return create(txn, KIND_PERMANENT, trimmed);
	}

	private ContactLink create(Transaction txn, int kind,
			@Nullable String label) throws DbException {
		KeyPair keys = crypto.generateHybridAgreementKeyPair();
		byte[] pub = keys.getPublic().getEncoded();
		byte[] linkId = crypto.hash(HYBRID_COMMITMENT_LABEL, pub);
		byte[] seed = new byte[INBOX_KEY_SEED_BYTES];
		crypto.getSecureRandom().nextBytes(seed);
		AuthorId local = identityManager.getLocalAuthor(txn).getId();
		ContactLink link = new ContactLink(linkId, local, kind, STATE_ACTIVE,
				label, pub, keys.getPrivate().getEncoded(), seed,
				clock.currentTimeMillis(), 0, 0, 0);
		db.addContactLink(txn, link);
		txn.attach(new ContactLinksChangedEvent());
		return link;
	}

	public ContactLink markShared(Transaction txn, byte[] linkId)
			throws DbException {
		ContactLink l = getLink(txn, linkId);
		if (l == null || l.getState() != STATE_ACTIVE || !l.hasInbox()) {
			throw new IllegalArgumentException();
		}
		ContactLink shared = l.withLastSharedAt(clock.currentTimeMillis());
		db.updateContactLink(txn, shared);
		txn.attach(new ContactLinksChangedEvent());
		return shared;
	}

	public ContactLink resetZeroId(Transaction txn) throws DbException {
		ContactLink old = getZeroId(txn);
		if (old != null) {
			db.updateContactLink(txn, old.retired(clock.currentTimeMillis()));
		}
		ContactLink fresh = create(txn, KIND_ZERO_ID, null);
		removeUnneededLinks(txn);
		return fresh;
	}

	public ContactLink rotateZeroIdAfterAccept(Transaction txn,
			ContactLink accepted) throws DbException {
		long now = clock.currentTimeMillis();
		ContactLink stored = getLink(txn, accepted.getLinkId());
		if (stored != null && stored.getKind() == KIND_ZERO_ID
				&& stored.getState() == STATE_ACTIVE) {
			db.updateContactLink(txn, stored.retired(now));
			txn.attach(new ContactLinksChangedEvent());
		}
		ContactLink current = getZeroId(txn);
		ContactLink fresh = current != null ? current
				: create(txn, KIND_ZERO_ID, null);
		removeUnneededLinks(txn);
		return fresh;
	}

	@Nullable
	public FloodNotice flooded(Transaction txn, byte[] linkId)
			throws DbException {
		ContactLink l = getLink(txn, linkId);
		if (l == null || l.getState() != STATE_ACTIVE || !l.hasInbox()) {
			return null;
		}
		long now = clock.currentTimeMillis();
		String hex = StringUtils.toHexString(l.getLinkId());
		Settings current = db.getSettings(txn, SETTINGS_NAMESPACE);
		Settings s = new Settings();
		FloodNotice notice;
		if (l.getKind() == KIND_PERMANENT) {
			String own = findPermanentSlot(current, hex);
			String slot = own != null ? own : permanentSlot(current, hex);
			int count = own == null ? 0
					: current.getInt(slot + KEY_FLOOD_COUNT, 0);
			long lastAt = own == null ? 0
					: current.getLong(slot + KEY_FLOOD_LAST_AT, 0);
			if (lastAt <= 0 || now - lastAt > FLOOD_PAUSE_RESET_MS) count = 0;
			long pause = FLOOD_PAUSE_MIN_MS;
			for (int i = 0; i < count && pause < FLOOD_PAUSE_MAX_MS; i++) {
				pause *= 2;
			}
			pause = Math.min(pause, FLOOD_PAUSE_MAX_MS);
			long pausedUntil = now + pause;
			s.put(slot + KEY_FLOOD_LINK, hex);
			s.putLong(slot + KEY_FLOOD_AT, now);
			s.put(slot + KEY_FLOOD_LABEL,
					l.getLabel() == null ? "" : l.getLabel());
			s.putLong(slot + KEY_FLOOD_PAUSED_UNTIL, pausedUntil);
			s.putInt(slot + KEY_FLOOD_COUNT, count + 1);
			s.putLong(slot + KEY_FLOOD_LAST_AT, now);
			notice = new FloodNotice(l.getLinkId(), KIND_PERMANENT,
					l.getLabel(), now, pausedUntil);
		} else {
			db.updateContactLink(txn, l.retired(now));
			create(txn, KIND_ZERO_ID, null);
			s.put(KEY_FLOOD_ZERO_ID + KEY_FLOOD_LINK, hex);
			s.putLong(KEY_FLOOD_ZERO_ID + KEY_FLOOD_AT, now);
			notice = new FloodNotice(l.getLinkId(), KIND_ZERO_ID, null, now, 0);
		}
		db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
		txn.attach(new ContactLinksChangedEvent());
		txn.attach(new ContactLinkFloodedEvent(l.getLinkId(), l.getKind()));
		removeUnneededLinks(txn);
		return notice;
	}

	private static String permanentSlotKey(int i) {
		return KEY_FLOOD_PREFIX + i;
	}

	@Nullable
	private static String findPermanentSlot(Settings current, String hex) {
		for (int i = 0; i < FLOOD_NOTICE_SLOTS; i++) {
			String slot = permanentSlotKey(i);
			if (hex.equals(current.get(slot + KEY_FLOOD_LINK))) return slot;
		}
		return null;
	}

	private static String permanentSlot(Settings current, String hex) {
		String own = findPermanentSlot(current, hex);
		if (own != null) return own;
		String oldest = permanentSlotKey(0);
		long oldestAt = Long.MAX_VALUE;
		for (int i = 0; i < FLOOD_NOTICE_SLOTS; i++) {
			String slot = permanentSlotKey(i);
			String link = current.get(slot + KEY_FLOOD_LINK);
			if (link == null || link.isEmpty()) return slot;
			long at = current.getLong(slot + KEY_FLOOD_AT, 0);
			if (at < oldestAt) {
				oldestAt = at;
				oldest = slot;
			}
		}
		return oldest;
	}

	private static void clearSlot(Settings s, String slot) {
		s.put(slot + KEY_FLOOD_LINK, "");
		s.putLong(slot + KEY_FLOOD_AT, 0);
		s.put(slot + KEY_FLOOD_LABEL, "");
		s.putLong(slot + KEY_FLOOD_PAUSED_UNTIL, 0);
		s.putInt(slot + KEY_FLOOD_COUNT, 0);
		s.putLong(slot + KEY_FLOOD_LAST_AT, 0);
	}

	@Nullable
	private static byte[] slotLink(Settings s, String slot) {
		String hex = s.get(slot + KEY_FLOOD_LINK);
		if (hex == null || hex.isEmpty()) return null;
		try {
			return StringUtils.fromHexString(hex);
		} catch (FormatException invalid) {
			return null;
		}
	}

	public List<FloodNotice> getFloodNotices(Transaction txn)
			throws DbException {
		Settings s = db.getSettings(txn, SETTINGS_NAMESPACE);
		List<FloodNotice> out = new ArrayList<>();
		long zeroIdAt = s.getLong(KEY_FLOOD_ZERO_ID + KEY_FLOOD_AT, 0);
		byte[] zeroId = slotLink(s, KEY_FLOOD_ZERO_ID);
		if (zeroIdAt > 0 && zeroId != null) {
			out.add(new FloodNotice(zeroId, KIND_ZERO_ID, null, zeroIdAt, 0));
		}
		for (int i = 0; i < FLOOD_NOTICE_SLOTS; i++) {
			String slot = permanentSlotKey(i);
			long at = s.getLong(slot + KEY_FLOOD_AT, 0);
			byte[] linkId = slotLink(s, slot);
			if (at <= 0 || linkId == null) continue;
			String label = s.get(slot + KEY_FLOOD_LABEL);
			if (label != null && label.isEmpty()) label = null;
			out.add(new FloodNotice(linkId, KIND_PERMANENT, label, at,
					s.getLong(slot + KEY_FLOOD_PAUSED_UNTIL, 0)));
		}
		return out;
	}

	public long getPausedUntil(Transaction txn, byte[] linkId)
			throws DbException {
		return pausedUntil(db.getSettings(txn, SETTINGS_NAMESPACE), linkId);
	}

	private static long pausedUntil(Settings s, byte[] linkId) {
		String slot = findPermanentSlot(s, StringUtils.toHexString(linkId));
		if (slot == null) return 0;
		return s.getLong(slot + KEY_FLOOD_PAUSED_UNTIL, 0);
	}

	public long nextPauseEnd(Transaction txn, long now) throws DbException {
		Settings s = db.getSettings(txn, SETTINGS_NAMESPACE);
		long next = 0;
		for (ContactLink l : db.getContactLinks(txn)) {
			if (l.getKind() != KIND_PERMANENT || l.getState() != STATE_ACTIVE) {
				continue;
			}
			long until = pausedUntil(s, l.getLinkId());
			if (until > now && (next == 0 || until < next)) next = until;
		}
		return next;
	}

	public void resume(Transaction txn, byte[] linkId) throws DbException {
		ContactLink l = getLink(txn, linkId);
		if (l == null || l.getKind() != KIND_PERMANENT
				|| l.getState() != STATE_ACTIVE) {
			throw new IllegalArgumentException();
		}
		Settings current = db.getSettings(txn, SETTINGS_NAMESPACE);
		String slot = findPermanentSlot(current,
				StringUtils.toHexString(linkId));
		if (slot == null
				|| current.getLong(slot + KEY_FLOOD_PAUSED_UNTIL, 0) <= 0) {
			return;
		}
		Settings s = new Settings();
		s.putLong(slot + KEY_FLOOD_PAUSED_UNTIL, 0);
		db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
		txn.attach(new ContactLinksChangedEvent());
	}

	public void dismissFloodNotice(Transaction txn, byte[] linkId)
			throws DbException {
		String hex = StringUtils.toHexString(linkId);
		Settings current = db.getSettings(txn, SETTINGS_NAMESPACE);
		String slot;
		if (hex.equals(current.get(KEY_FLOOD_ZERO_ID + KEY_FLOOD_LINK))) {
			slot = KEY_FLOOD_ZERO_ID;
		} else {
			slot = findPermanentSlot(current, hex);
		}
		if (slot == null || current.getLong(slot + KEY_FLOOD_AT, 0) <= 0) {
			return;
		}
		Settings s = new Settings();
		clearSlot(s, slot);
		db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
		txn.attach(new ContactLinksChangedEvent());
	}

	public void withdraw(Transaction txn, byte[] linkId) throws DbException {
		ContactLink l = getLink(txn, linkId);
		if (l == null || l.getKind() != KIND_PERMANENT
				|| l.getState() != STATE_ACTIVE) {
			throw new IllegalArgumentException();
		}
		db.updateContactLink(txn, l.withdrawn(clock.currentTimeMillis()));
		Settings current = db.getSettings(txn, SETTINGS_NAMESPACE);
		String slot = findPermanentSlot(current,
				StringUtils.toHexString(linkId));
		if (slot != null) {
			Settings s = new Settings();
			clearSlot(s, slot);
			db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
		}
		txn.attach(new ContactLinksChangedEvent());
		removeUnneededLinks(txn);
	}

	public void removeUnneededLinks(Transaction txn) throws DbException {
		Collection<IncomingRequest> incoming = db.getIncomingRequests(txn);
		for (ContactLink l : db.getContactLinks(txn)) {
			if (l.getState() == STATE_ACTIVE || !l.hasInbox()) continue;
			if (isHeld(l.getLinkId())) continue;
			boolean needed = false;
			for (IncomingRequest r : incoming) {
				if ((r.isUndecided()
						|| r.getState() == IncomingRequest.STATE_ACCEPTED)
						&& Arrays.equals(r.getLinkId(), l.getLinkId())) {
					needed = true;
				}
			}
			if (!needed) {
				db.removeRequestBlocks(txn, l.getLinkId());
				db.removeContactLink(txn, l.getLinkId());
				txn.attach(new ContactLinksChangedEvent());
			}
		}
	}

	public boolean isInboxOpen(Transaction txn, ContactLink l, long now)
			throws DbException {
		if (!isInboxOpen(l, now)) return false;
		return l.getKind() != KIND_PERMANENT
				|| getPausedUntil(txn, l.getLinkId()) <= now;
	}

	public boolean isInboxOpen(ContactLink l, long now) {
		if (l.getState() != STATE_ACTIVE || l.getInboxKey() == null) {
			return false;
		}
		if (l.getKind() == KIND_PERMANENT) return true;
		if (l.getKind() != KIND_ZERO_ID) return false;
		return l.getLastSharedAt() > 0
				&& now < l.getLastSharedAt() + ZERO_ID_OPEN_MS;
	}

	public void hold(byte[] linkId) {
		String key = StringUtils.toHexString(linkId);
		synchronized (held) {
			Integer n = held.get(key);
			held.put(key, n == null ? 1 : n + 1);
		}
	}

	public boolean release(byte[] linkId) {
		String key = StringUtils.toHexString(linkId);
		synchronized (held) {
			Integer n = held.get(key);
			if (n == null || n <= 1) {
				held.remove(key);
				return true;
			}
			held.put(key, n - 1);
			return false;
		}
	}

	boolean isHeld(byte[] linkId) {
		synchronized (held) {
			return held.containsKey(StringUtils.toHexString(linkId));
		}
	}

	public void setInboxPowProtected(String onion, boolean protectedByPow) {
		if (protectedByPow) powInboxes.add(onion);
		else powInboxes.remove(onion);
	}

	public boolean isInboxPowProtected(ContactLink l) {
		return l.getInboxKey() != null && powInboxes.contains(inboxOnion(l));
	}

	public String inboxOnion(ContactLink l) {
		byte[] seed = l.getInboxKey();
		if (seed == null) throw new IllegalArgumentException();
		return onionCrypto.getOnion(seed);
	}

	public String inboxKeyBlob(ContactLink l) {
		byte[] seed = l.getInboxKey();
		if (seed == null) throw new IllegalArgumentException();
		return onionCrypto.getPrivateKeyBlob(seed);
	}

	public byte[] body(ContactLink l) {
		byte[] pub = l.getHybridPublicKey();
		if (pub == null) throw new IllegalArgumentException();
		byte[] body = new byte[1 + l.getLinkId().length + X25519_BYTES];
		body[0] = (byte) (l.getKind() == KIND_PERMANENT
				? LINK_TYPE_REQUEST_ONLY : LINK_TYPE_ZERO_ID);
		System.arraycopy(l.getLinkId(), 0, body, 1, l.getLinkId().length);
		System.arraycopy(pub, 0, body, 1 + l.getLinkId().length,
				X25519_BYTES);
		return body;
	}

	public String linkString(ContactLink l) {
		return codec.format(body(l), inboxOnion(l));
	}

	public KeyPair keyPair(ContactLink l) {
		byte[] pub = l.getHybridPublicKey();
		byte[] priv = l.getHybridPrivateKey();
		if (pub == null || priv == null) throw new IllegalArgumentException();
		return new KeyPair(new HybridAgreementPublicKey(pub),
				new HybridAgreementPrivateKey(priv.clone()));
	}

	public void recordPerAddShare(Transaction txn, String link, long at)
			throws DbException, FormatException {
		InviteLink parsed = codec.parse(link);
		if (parsed.getFlow() == InviteLink.Flow.MUTUAL_CLASSICAL) {
			throw new FormatException();
		}
		forgetPerAddBinding(txn);
		AuthorId local = identityManager.getLocalAuthor(txn).getId();
		db.addContactLink(txn, new ContactLink(parsed.getCommitment(), local,
				KIND_PER_ADD_BINDING, STATE_ACTIVE, null, null, null, null, at,
				at, 0, 0));
	}

	@Nullable
	public ContactLink freshPerAddBinding(Transaction txn, long now)
			throws DbException {
		for (ContactLink l : db.getContactLinks(txn)) {
			if (l.getKind() == KIND_PER_ADD_BINDING
					&& now - l.getLastSharedAt() <= PER_ADD_BINDING_MS) {
				return l;
			}
		}
		return null;
	}

	public void forgetPerAddBinding(Transaction txn) throws DbException {
		for (ContactLink l : db.getContactLinks(txn)) {
			if (l.getKind() == KIND_PER_ADD_BINDING) {
				db.removeContactLink(txn, l.getLinkId());
			}
		}
	}

	@Nullable
	public byte[] currentPerAddCommitment(Transaction txn)
			throws DbException {
		KeyPair perAdd = identityManager.getHybridHandshakeKeys(txn);
		if (perAdd == null) return null;
		return crypto.hash(HYBRID_COMMITMENT_LABEL,
				perAdd.getPublic().getEncoded());
	}

	public boolean isOwnLinkKey(Transaction txn, byte[] commitment,
			byte[] x25519) throws DbException {
		KeyPair perAdd = identityManager.getHybridHandshakeKeys(txn);
		if (perAdd != null) {
			byte[] pub = perAdd.getPublic().getEncoded();
			if (MessageDigest.isEqual(commitment,
					crypto.hash(HYBRID_COMMITMENT_LABEL, pub))
					|| MessageDigest.isEqual(x25519,
					Arrays.copyOf(pub, X25519_BYTES))) {
				return true;
			}
		}
		for (ContactLink l : db.getContactLinks(txn)) {
			byte[] pub = l.getHybridPublicKey();
			if (pub == null) continue;
			if (MessageDigest.isEqual(commitment, l.getLinkId())
					|| MessageDigest.isEqual(x25519,
					Arrays.copyOf(pub, X25519_BYTES))) {
				return true;
			}
		}
		return false;
	}

	public boolean isOwnLink(Transaction txn, InviteLink parsed)
			throws DbException {
		if (parsed.getFlow() == InviteLink.Flow.MUTUAL_CLASSICAL) {
			KeyPair classical = identityManager.getHandshakeKeys(txn);
			return Arrays.equals(parsed.getKeyBlob(),
					classical.getPublic().getEncoded());
		}
		String inbox = parsed.getInbox();
		if (inbox != null && isOwnInbox(txn, inbox)) return true;
		return isOwnLinkKey(txn, parsed.getCommitment(), parsed.getX25519());
	}

	public boolean isOwnInbox(Transaction txn, String onion)
			throws DbException {
		for (ContactLink l : db.getContactLinks(txn)) {
			if (l.getInboxKey() != null && inboxOnion(l).equals(onion)) {
				return true;
			}
		}
		return false;
	}

	public byte[] getAccountTagSecret(Transaction txn) throws DbException {
		AuthorId local = identityManager.getLocalAuthor(txn).getId();
		byte[] secret = db.getRequestAccountSecret(txn, local);
		if (secret != null) return secret.clone();
		secret = new byte[ACCOUNT_TAG_SECRET_BYTES];
		crypto.getSecureRandom().nextBytes(secret);
		db.setRequestAccountSecret(txn, local, secret.clone());
		return secret;
	}

	private volatile boolean snapshotting = false;

	public boolean isPaused(Transaction txn) throws DbException {
		return !snapshotting && isPauseStored(txn);
	}

	public boolean isPauseStored(Transaction txn) throws DbException {
		return db.getSettings(txn, SETTINGS_NAMESPACE)
				.getBoolean(KEY_PAUSED, false);
	}

	public void setSnapshotting(boolean snapshotting) {
		this.snapshotting = snapshotting;
	}

	public void setPaused(Transaction txn, boolean paused) throws DbException {
		Settings s = new Settings();
		s.putBoolean(KEY_PAUSED, paused);
		db.mergeSettings(txn, s, SETTINGS_NAMESPACE);
		txn.attach(new ContactLinksChangedEvent());
	}

	public List<ContactLink> getInboxLinks(Transaction txn)
			throws DbException {
		List<ContactLink> out = new ArrayList<>();
		for (ContactLink l : db.getContactLinks(txn)) {
			if (l.hasInbox()) out.add(l);
		}
		return out;
	}
}
