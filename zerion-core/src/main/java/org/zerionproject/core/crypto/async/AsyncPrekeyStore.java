package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Persists this account's async prekeys and the envelope replay seen-set in the
 * encrypted settings store (SQLCipher-backed). It generates and rotates one-time
 * and signed prekeys, resolves the private key an incoming envelope consumed so
 * {@link AsyncSealedSender#open} can decrypt, deletes a one-time prekey once it
 * is used (per-message forward secrecy), and deduplicates envelopes. It does not
 * hold the account identity; the caller supplies identity keys when publishing a
 * bundle. All key material is stored only in the encrypted settings namespace.
 */
@ThreadSafe
@NotNullByDefault
public class AsyncPrekeyStore {

	private static final String NS = "org.zerionproject.async/prekeys";
	private static final String OTK_IDS = "otkIds";
	private static final String SPK_ID = "spkId";
	private static final String SPK_PUB = "spkPub";
	private static final String SPK_PRIV = "spkPriv";
	private static final String SPK_EXPIRY = "spkExpiry";
	private static final String SPK_PREV_ID = "spkPrevId";
	private static final String SPK_PREV_PUB = "spkPrevPub";
	private static final String SPK_PREV_PRIV = "spkPrevPriv";
	private static final String SEEN = "seen";

	private static final long SPK_LIFETIME_SECONDS = 7L * 24 * 3600;
	private static final int MAX_SEEN = 4096;

	private final CryptoComponent crypto;
	private final SettingsManager settingsManager;
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();
	private final Object lock = new Object();

	public AsyncPrekeyStore(CryptoComponent crypto,
			SettingsManager settingsManager, Clock clock) {
		this.crypto = crypto;
		this.settingsManager = settingsManager;
		this.clock = clock;
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

	/** Generates and persists {@code count} one-time prekeys, returning their
	 * public parts for inclusion in a published bundle. */
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
			return created;
		}
	}

	/** Tops the one-time prekey pool up to {@code target}, generating only the
	 * shortfall, and returns the full current set of public parts for a bundle.
	 * Unlike {@link #generateOneTimePrekeys}, repeated calls do not grow the
	 * store without bound. */
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

	/** Returns the current signed prekey, generating a fresh one if none exists
	 * or the current one has expired. */
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

	/** Rotates the signed prekey, keeping the previous one for a grace window so
	 * in-flight envelopes can still be opened. */
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

	/** Resolves the private keypair an envelope consumed, or null if unknown or
	 * already consumed. */
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

	/** Deletes a used one-time prekey so it cannot open a replayed envelope. */
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
		}
	}

	/** Records an envelope dedup id, returning true if it is new (not a replay).
	 * The seen-set is bounded and evicts oldest first. */
	public boolean checkAndMarkSeen(byte[] dedupId) throws DbException {
		synchronized (lock) {
			Settings s = settingsManager.getSettings(NS);
			LinkedHashSet<String> set = parseList(s.get(SEEN));
			String h = StringUtils.toHexString(dedupId);
			if (!set.add(h)) return false;
			while (set.size() > MAX_SEEN) set.remove(set.iterator().next());
			Settings upd = new Settings();
			upd.put(SEEN, joinList(set));
			settingsManager.mergeSettings(upd, NS);
			return true;
		}
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
