package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class AsyncPrekeyStore {

	public static final long PUBLISHED_SIGNED_PREKEY_ID = 0L;

	static final int MAX_AUDIENCES = 1024;

	private static final String NS = "org.zerionproject.async/prekeys";
	private static final String OTK_IDS = "otkIds";
	private static final String SPK_ID = "spkId";
	private static final String SPK_PUB = "spkPub";
	private static final String SPK_PRIV = "spkPriv";
	private static final String SPK_EXPIRY = "spkExpiry";
	private static final String SPK_PREV_ID = "spkPrevId";
	private static final String SPK_PREV_PUB = "spkPrevPub";
	private static final String SPK_PREV_PRIV = "spkPrevPriv";
	private static final String SPK_PREV_EXPIRY = "spkPrevExpiry";
	private static final String MESH_AGREE_PUB = "meshAgreePub";
	private static final String ALIAS_KEY = "otkAliasKey";
	private static final String AUDIENCES = "otkAudiences";
	private static final String LABEL_OTK_ALIAS =
			"org.zerionproject.async/ONE_TIME_PREKEY_ALIAS";
	private static final long ALIAS_RELOAD_INTERVAL_MS = 30_000L;
	private static final String SEEN_PREFIX = "seen.";
	private static final String FLOOR_PREFIX = "floor.";
	private static final String SEEN_SENDER_LABEL =
			"org.zerionproject.async/SEEN_SENDER";
	public static final int MAX_SEEN_SENDERS = 256;
	private static final String LEGACY_SEEN = "seen";
	private static final String LEGACY_FLOOR = "seenFloor";

	private static final long SPK_LIFETIME_SECONDS = 7L * 24 * 3600;
	public static final int MAX_SEEN = 4096;

	static final long MAX_ENVELOPE_LIFETIME_MS = 30L * 24 * 60 * 60 * 1000;

	private final CryptoComponent crypto;
	private final SettingsManager settingsManager;
	private final Clock clock;
	private final int maxSeen;
	private final SecureRandom random = new SecureRandom();
	private final Object lock = new Object();

	@Nullable
	private Map<String, String> aliasIndex = null;
	private long aliasIndexBuiltAt = 0L;

	public AsyncPrekeyStore(CryptoComponent crypto,
			SettingsManager settingsManager, Clock clock) {
		this(crypto, settingsManager, clock, MAX_SEEN);
	}

	public AsyncPrekeyStore(CryptoComponent crypto,
			SettingsManager settingsManager, Clock clock, int maxSeen) {
		if (maxSeen < 1) throw new IllegalArgumentException();
		this.crypto = crypto;
		this.settingsManager = settingsManager;
		this.clock = clock;
		this.maxSeen = maxSeen;
	}

	public static class SignedPrekey {
		public final long id;
		public final byte[] pub;
		public final byte[] priv;
		public final long expiry;

		SignedPrekey(long id, byte[] pub, byte[] priv, long expiry) {
			this.id = id;
			this.pub = pub;
			this.priv = priv;
			this.expiry = expiry;
		}
	}

	public List<AsyncPrekeyBundle.OneTimePrekey> generateOneTimePrekeys(
			int count) throws DbException {
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			LinkedHashSet<String> ids = parseList(s.get(OTK_IDS));
			Settings upd = new Settings();
			List<AsyncPrekeyBundle.OneTimePrekey> created =
					new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				byte[] id = new byte[AsyncPrekeyBundle.ONE_TIME_PREKEY_ID_BYTES];
				random.nextBytes(id);
				String idHex = StringUtils.toHexString(id);
				KeyPair kp = crypto.generateHybridAgreementKeyPair();
				byte[] pub = kp.getPublic().getEncoded();
				upd.put(otkPub(idHex), StringUtils.toHexString(pub));
				upd.put(otkPriv(idHex),
						StringUtils.toHexString(kp.getPrivate().getEncoded()));
				ids.add(idHex);
				created.add(new AsyncPrekeyBundle.OneTimePrekey(id, pub));
			}
			upd.put(OTK_IDS, joinList(ids));
			settingsManager.mergeSettings(upd, NS);
			aliasIndex = null;
			return created;
		}
	}

	public List<AsyncPrekeyBundle.OneTimePrekey> topUpOneTimePrekeys(
			int target, byte[] audience) throws DbException {
		synchronized (lock) {
			List<AsyncPrekeyBundle.OneTimePrekey> pool =
					topUpOneTimePrekeys(target);
			Settings s = settingsManager.getSettings(NS);
			byte[] aliasKey = aliasKeyLocked(s);
			rememberAudienceLocked(s, audience);
			List<AsyncPrekeyBundle.OneTimePrekey> out =
					new ArrayList<>(pool.size());
			for (AsyncPrekeyBundle.OneTimePrekey p : pool) {
				out.add(new AsyncPrekeyBundle.OneTimePrekey(
						alias(aliasKey, audience, p.id), p.pub));
			}
			aliasIndex = null;
			return out;
		}
	}

	public byte[] getMeshAgreementPublicKey() throws DbException {
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			String hex = s.get(MESH_AGREE_PUB);
			if (!empty(hex)) return hex(hex);
			KeyPair kp = crypto.generateHybridAgreementKeyPair();
			byte[] pub = kp.getPublic().getEncoded();
			PrivateKey priv = kp.getPrivate();
			if (priv instanceof HybridAgreementPrivateKey) {
				((HybridAgreementPrivateKey) priv).clear();
			}
			Settings upd = new Settings();
			upd.put(MESH_AGREE_PUB, StringUtils.toHexString(pub));
			settingsManager.mergeSettings(upd, NS);
			return pub;
		}
	}

	private byte[] aliasKeyLocked(Settings s) throws DbException {
		String hex = s.get(ALIAS_KEY);
		if (!empty(hex)) return hex(hex);
		byte[] key = new byte[SecretKey.LENGTH];
		random.nextBytes(key);
		Settings upd = new Settings();
		upd.put(ALIAS_KEY, StringUtils.toHexString(key));
		settingsManager.mergeSettings(upd, NS);
		s.put(ALIAS_KEY, StringUtils.toHexString(key));
		return key;
	}

	private void rememberAudienceLocked(Settings s, byte[] audience)
			throws DbException {
		LinkedHashSet<String> audiences = parseList(s.get(AUDIENCES));
		String a = StringUtils.toHexString(audience);
		if (audiences.contains(a)) return;
		audiences.add(a);
		while (audiences.size() > MAX_AUDIENCES) {
			audiences.remove(audiences.iterator().next());
		}
		Settings upd = new Settings();
		upd.put(AUDIENCES, joinList(audiences));
		settingsManager.mergeSettings(upd, NS);
		s.put(AUDIENCES, joinList(audiences));
	}

	private byte[] alias(byte[] aliasKey, byte[] audience, byte[] id) {
		byte[] mac = crypto.mac(LABEL_OTK_ALIAS, new SecretKey(aliasKey),
				audience, id);
		return java.util.Arrays.copyOf(mac,
				AsyncPrekeyBundle.ONE_TIME_PREKEY_ID_BYTES);
	}

	@Nullable
	private String unaliasLocked(Settings s, String idHex) throws DbException {
		long now = clock.currentTimeMillis();
		Map<String, String> index = aliasIndex;
		if (index == null || (!index.containsKey(idHex)
				&& now - aliasIndexBuiltAt >= ALIAS_RELOAD_INTERVAL_MS)) {
			index = buildAliasIndexLocked(s);
			aliasIndex = index;
			aliasIndexBuiltAt = now;
		}
		return index.get(idHex);
	}

	private Map<String, String> buildAliasIndexLocked(Settings s)
			throws DbException {
		Map<String, String> index = new java.util.HashMap<>();
		String keyHex = s.get(ALIAS_KEY);
		if (empty(keyHex)) return index;
		byte[] aliasKey = hex(keyHex);
		LinkedHashSet<String> ids = parseList(s.get(OTK_IDS));
		for (String audienceHex : parseList(s.get(AUDIENCES))) {
			byte[] audience = hex(audienceHex);
			for (String idHex : ids) {
				index.put(StringUtils.toHexString(
						alias(aliasKey, audience, hex(idHex))), idHex);
			}
		}
		return index;
	}

	public List<AsyncPrekeyBundle.OneTimePrekey> topUpOneTimePrekeys(int target)
			throws DbException {
		synchronized (lock) {
			LinkedHashSet<String> ids =
					parseList(settingsManager.getSettings(NS).get(OTK_IDS));
			if (ids.size() < target) {
				generateOneTimePrekeys(target - ids.size());
			}
			Settings s = settingsManager.getSettings(NS);
			ids = parseList(s.get(OTK_IDS));
			List<AsyncPrekeyBundle.OneTimePrekey> out = new ArrayList<>();
			for (String idHex : ids) {
				String pubHex = s.get(otkPub(idHex));
				if (empty(pubHex)) continue;
				out.add(new AsyncPrekeyBundle.OneTimePrekey(hex(idHex),
						hex(pubHex)));
			}
			return out;
		}
	}

	public SignedPrekey getSignedPrekey() throws DbException {
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			String pubHex = s.get(SPK_PUB);
			long expiry = s.getLong(SPK_EXPIRY, 0L);
			long now = clock.currentTimeMillis() / 1000L;
			if (pubHex == null || pubHex.isEmpty() || now >= expiry) {
				return rotateSignedPrekeyLocked(s);
			}
			return new SignedPrekey(s.getLong(SPK_ID, 0L), hex(pubHex),
					hex(s.get(SPK_PRIV)), expiry);
		}
	}

	public SignedPrekey rotateSignedPrekey() throws DbException {
		synchronized (lock) {
			return rotateSignedPrekeyLocked(settingsManager.getSettings(NS));
		}
	}

	private SignedPrekey rotateSignedPrekeyLocked(Settings s)
			throws DbException {
		Settings upd = new Settings();
		String curPub = s.get(SPK_PUB);
		if (curPub != null && !curPub.isEmpty()) {
			upd.putLong(SPK_PREV_ID, s.getLong(SPK_ID, 0L));
			upd.put(SPK_PREV_PUB, curPub);
			upd.put(SPK_PREV_PRIV, s.get(SPK_PRIV));
			upd.putLong(SPK_PREV_EXPIRY, s.getLong(SPK_EXPIRY, 0L));
		}
		long newId = s.getLong(SPK_ID, 0L) + 1L;
		KeyPair kp = crypto.generateHybridAgreementKeyPair();
		byte[] pub = kp.getPublic().getEncoded();
		byte[] priv = kp.getPrivate().getEncoded();
		long expiry = clock.currentTimeMillis() / 1000L + SPK_LIFETIME_SECONDS;
		upd.putLong(SPK_ID, newId);
		upd.put(SPK_PUB, StringUtils.toHexString(pub));
		upd.put(SPK_PRIV, StringUtils.toHexString(priv));
		upd.putLong(SPK_EXPIRY, expiry);
		settingsManager.mergeSettings(upd, NS);
		return new SignedPrekey(newId, pub, priv, expiry);
	}

	@Nullable
	public KeyPair resolvePrekey(int prekeyKind, byte[] prekeyId,
			long signedPrekeyId) throws DbException, GeneralSecurityException {
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			if (prekeyKind == AsyncEnvelope.PREKEY_KIND_ONE_TIME) {
				String idHex = StringUtils.toHexString(prekeyId);
				String pubHex = s.get(otkPub(idHex));
				String privHex = s.get(otkPriv(idHex));
				if (empty(pubHex) || empty(privHex)) return null;
				return keyPair(pubHex, privHex);
			}
			if (s.getLong(SPK_ID, -1L) == signedPrekeyId
					&& !empty(s.get(SPK_PUB))) {
				return keyPair(s.get(SPK_PUB), s.get(SPK_PRIV));
			}
			if (s.getLong(SPK_PREV_ID, -1L) == signedPrekeyId
					&& !empty(s.get(SPK_PREV_PUB))) {
				return keyPair(s.get(SPK_PREV_PUB), s.get(SPK_PREV_PRIV));
			}
			return null;
		}
	}

	public static final class PrekeyCandidate {
		public final KeyPair keyPair;
		public final boolean published;
		@Nullable
		public final byte[] oneTimeId;

		PrekeyCandidate(KeyPair keyPair, boolean published,
				@Nullable byte[] oneTimeId) {
			this.keyPair = keyPair;
			this.published = published;
			this.oneTimeId = oneTimeId;
		}
	}

	public List<PrekeyCandidate> resolveCandidates(int prekeyKind,
			byte[] prekeyId, long signedPrekeyId, long ttlSeconds)
			throws DbException, GeneralSecurityException {
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			List<PrekeyCandidate> out = new ArrayList<>(2);
			if (prekeyKind == AsyncEnvelope.PREKEY_KIND_ONE_TIME) {
				String idHex = StringUtils.toHexString(prekeyId);
				boolean published = false;
				if (!parseList(s.get(OTK_IDS)).contains(idHex)) {
					idHex = unaliasLocked(s, idHex);
					published = true;
				}
				if (idHex == null) return out;
				String pubHex = s.get(otkPub(idHex));
				String privHex = s.get(otkPriv(idHex));
				if (empty(pubHex) || empty(privHex)) return out;
				out.add(new PrekeyCandidate(keyPair(pubHex, privHex),
						published, hex(idHex)));
				return out;
			}
			if (signedPrekeyId == PUBLISHED_SIGNED_PREKEY_ID) {
				if (!empty(s.get(SPK_PUB))) {
					out.add(new PrekeyCandidate(keyPair(s.get(SPK_PUB),
							s.get(SPK_PRIV)), true, null));
				}
				if (!empty(s.get(SPK_PREV_PUB))
						&& previousMayStillOpen(s, ttlSeconds)) {
					out.add(new PrekeyCandidate(keyPair(s.get(SPK_PREV_PUB),
							s.get(SPK_PREV_PRIV)), true, null));
				}
				return out;
			}
			KeyPair legacy = resolvePrekey(prekeyKind, prekeyId,
					signedPrekeyId);
			if (legacy != null) {
				out.add(new PrekeyCandidate(legacy, false, null));
			}
			return out;
		}
	}

	private boolean previousMayStillOpen(Settings s, long ttlSeconds) {
		long prevExpiry = s.getLong(SPK_PREV_EXPIRY, 0L);
		if (prevExpiry <= 0L) return true;
		long nowSeconds = clock.currentTimeMillis() / 1000L;
		return nowSeconds <= prevExpiry + ttlSeconds;
	}

	public void consumeOneTimePrekey(byte[] prekeyId) throws DbException {
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			LinkedHashSet<String> ids = parseList(s.get(OTK_IDS));
			String idHex = StringUtils.toHexString(prekeyId);
			if (!ids.remove(idHex)) return;
			Settings upd = new Settings();
			upd.put(OTK_IDS, joinList(ids));
			upd.put(otkPub(idHex), "");
			upd.put(otkPriv(idHex), "");
			settingsManager.mergeSettings(upd, NS);
			aliasIndex = null;
		}
	}

	public boolean isSeen(byte[] dedupId) throws DbException {
		String h = StringUtils.toHexString(dedupId);
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			if (parseSeen(s.get(LEGACY_SEEN)).containsKey(h)) return true;
			for (Map.Entry<String, String> e : s.entrySet()) {
				if (e.getKey().startsWith(SEEN_PREFIX)
						&& parseSeen(e.getValue()).containsKey(h)) {
					return true;
				}
			}
			return false;
		}
	}

	private String senderKey(byte[] senderSigPub) {
		byte[] h = crypto.hash(SEEN_SENDER_LABEL, senderSigPub);
		return StringUtils.toHexString(java.util.Arrays.copyOf(h, 16));
	}

	public boolean checkAndMarkSeen(byte[] senderSigPub, byte[] dedupId,
			long expiryMs) throws DbException {
		String sender = senderKey(senderSigPub);
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			long now = clock.currentTimeMillis();
			long floor = s.getLong(FLOOR_PREFIX + sender, Long.MIN_VALUE);
			long legacyFloor = s.getLong(LEGACY_FLOOR, Long.MIN_VALUE);
			if (expiryMs <= floor || expiryMs <= legacyFloor
					|| expiryMs <= now) {
				return false;
			}
			LinkedHashMap<String, Long> set =
					parseSeen(s.get(SEEN_PREFIX + sender));
			String h = StringUtils.toHexString(dedupId);
			if (set.containsKey(h)) return false;
			if (parseSeen(s.get(LEGACY_SEEN)).containsKey(h)) return false;
			set.put(h, expiryMs);
			set.values().removeIf(expiry -> expiry <= now);
			boolean admitted = true;
			while (set.size() > maxSeen) {
				String oldest = null;
				long oldestExpiry = Long.MAX_VALUE;
				for (Map.Entry<String, Long> e : set.entrySet()) {
					if (e.getValue() < oldestExpiry) {
						oldestExpiry = e.getValue();
						oldest = e.getKey();
					}
				}
				set.remove(oldest);
				if (oldestExpiry > floor) floor = oldestExpiry;
				if (h.equals(oldest)) admitted = false;
			}
			Settings upd = new Settings();
			upd.put(SEEN_PREFIX + sender, joinSeen(set));
			upd.putLong(FLOOR_PREFIX + sender, floor);
			evictSurplusSenders(s, sender, upd);
			settingsManager.mergeSettings(upd, NS);
			return admitted;
		}
	}

	private void evictSurplusSenders(Settings s, String keep, Settings upd) {
		java.util.Map<String, Long> latest = new java.util.HashMap<>();
		for (Map.Entry<String, String> e : s.entrySet()) {
			if (!e.getKey().startsWith(SEEN_PREFIX)) continue;
			String sender = e.getKey().substring(SEEN_PREFIX.length());
			if (sender.equals(keep)) continue;
			LinkedHashMap<String, Long> set = parseSeen(e.getValue());
			if (set.isEmpty()) continue;
			long max = Long.MIN_VALUE;
			for (long v : set.values()) max = Math.max(max, v);
			latest.put(sender, max);
		}
		while (latest.size() + 1 > MAX_SEEN_SENDERS) {
			String oldest = null;
			long oldestMax = Long.MAX_VALUE;
			for (Map.Entry<String, Long> e : latest.entrySet()) {
				if (e.getValue() < oldestMax) {
					oldestMax = e.getValue();
					oldest = e.getKey();
				}
			}
			latest.remove(oldest);
			upd.put(SEEN_PREFIX + oldest, "");
			upd.putLong(FLOOR_PREFIX + oldest, Long.MIN_VALUE);
		}
	}

	public long seenFloor(byte[] senderSigPub) throws DbException {
		synchronized (lock) {
			return settingsManager.getSettings(NS)
					.getLong(FLOOR_PREFIX + senderKey(senderSigPub),
							Long.MIN_VALUE);
		}
	}

	private LinkedHashMap<String, Long> parseSeen(@Nullable String csv) {
		LinkedHashMap<String, Long> set = new LinkedHashMap<>();
		if (csv == null || csv.isEmpty()) return set;
		long legacyExpiry = clock.currentTimeMillis()
				+ MAX_ENVELOPE_LIFETIME_MS;
		for (String p : csv.split(",")) {
			if (p.isEmpty()) continue;
			int sep = p.indexOf(':');
			if (sep < 0) {
				set.put(p, legacyExpiry);
				continue;
			}
			try {
				set.put(p.substring(0, sep),
						Long.parseLong(p.substring(sep + 1)));
			} catch (NumberFormatException e) {
				set.put(p.substring(0, sep), legacyExpiry);
			}
		}
		return set;
	}

	private static String joinSeen(LinkedHashMap<String, Long> set) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Long> e : set.entrySet()) {
			if (sb.length() > 0) sb.append(',');
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		return sb.toString();
	}

	private KeyPair keyPair(String pubHex, String privHex)
			throws GeneralSecurityException, DbException {
		return new KeyPair(crypto.getHybridAgreementKeyParser()
				.parsePublicKey(hex(pubHex)),
				crypto.getHybridAgreementKeyParser()
						.parsePrivateKey(hex(privHex)));
	}

	private static byte[] hex(String s) throws DbException {
		try {
			return StringUtils.fromHexString(s);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private static String otkPub(String idHex) {
		return "otk." + idHex + ".pub";
	}

	private static String otkPriv(String idHex) {
		return "otk." + idHex + ".priv";
	}

	private static boolean empty(@Nullable String s) {
		return s == null || s.isEmpty();
	}

	private static LinkedHashSet<String> parseList(@Nullable String csv) {
		LinkedHashSet<String> set = new LinkedHashSet<>();
		if (csv == null || csv.isEmpty()) return set;
		for (String p : csv.split(",")) {
			if (!p.isEmpty()) set.add(p);
		}
		return set;
	}

	private static String joinList(LinkedHashSet<String> set) {
		return String.join(",", set);
	}
}
