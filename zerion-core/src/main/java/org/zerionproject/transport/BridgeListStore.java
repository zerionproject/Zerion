package org.zerionproject.transport;

import org.bouncycastle.util.encoders.Base64;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.tor.BridgeList;
import org.zerionproject.tor.BridgeListKeys;

import java.nio.charset.Charset;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@ThreadSafe
@NotNullByDefault
public class BridgeListStore {

	public enum Verdict {
		ACCEPTED, NOT_NEWER, BAD_SIGNATURE, MALFORMED, EXPIRED, NOT_YET_VALID,
		UNAVAILABLE
	}

	public static final class FetchState {
		public final long nextFetchMs;
		public final long fetchedVersion;

		FetchState(long nextFetchMs, long fetchedVersion) {
			this.nextFetchMs = nextFetchMs;
			this.fetchedVersion = fetchedVersion;
		}
	}

	public static final String NAMESPACE = "tor-bridge-list";

	public static final long BUNDLED_SEQ = 1;

	public static final int RECENT_BRIDGE_DAYS = 30;

	static final int MAX_FUTURE_DAYS = 1;

	static final String KEY_LIST = "list";
	static final String KEY_SIGNATURE = "signature";
	static final String KEY_HIGHEST_SEQ_PREFIX = "highest-seq:";
	static final String KEY_NEXT_FETCH = "next-fetch-ms";
	static final String KEY_FETCHED_VERSION = "fetched-version";
	static final String KEY_BUILT_IN_DAY = "built-in-bridges-day";
	static final String KEY_BUILT_IN_OWNER = "built-in-bridges-owner";

	private static final long DAY_MS = 24L * 60 * 60 * 1000;
	private static final Charset ASCII = Charset.forName("US-ASCII");

	private final SettingsManager settingsManager;
	private final Clock clock;
	private final BridgeListKeys keys;
	private final BridgeHealth.Owner owner;
	private final Object lock = new Object();

	@GuardedBy("lock")
	@Nullable
	private String cachedList = null;
	@GuardedBy("lock")
	@Nullable
	private String cachedSignature = null;
	@GuardedBy("lock")
	@Nullable
	private BridgeList cached = null;

	private volatile boolean builtInNow = false;

	@Inject
	public BridgeListStore(SettingsManager settingsManager, Clock clock,
			BridgeListKeys keys, BridgeHealth.Owner owner) {
		this.settingsManager = settingsManager;
		this.clock = clock;
		this.keys = keys;
		this.owner = owner;
	}

	public Verdict offer(byte[] file, byte[] signatureFile,
			boolean localFile) {
		if (file.length == 0 || file.length > BridgeList.MAX_FILE_BYTES) {
			return Verdict.MALFORMED;
		}
		byte[] signer = BridgeList.signingKey(file, signatureFile,
				localFile ? keys.forLocalFile() : keys.forNetwork());
		if (signer == null) return Verdict.BAD_SIGNATURE;
		BridgeList list = BridgeList.parse(file);
		if (list == null) return Verdict.MALFORMED;
		int today = today();
		if (list.getIssuedDay() > today + MAX_FUTURE_DAYS) {
			return Verdict.NOT_YET_VALID;
		}
		if (list.getExpiresDay() < today) return Verdict.EXPIRED;
		synchronized (lock) {
			Settings s;
			try {
				s = settingsManager.getSettings(NAMESPACE);
			} catch (DbException e) {
				return Verdict.UNAVAILABLE;
			}
			long floor = Math.max(BUNDLED_SEQ, highestSeq(s));
			if (list.getSeq() <= floor) return Verdict.NOT_NEWER;
			Settings update = new Settings();
			update.put(KEY_LIST, Base64.toBase64String(file));
			update.put(KEY_SIGNATURE,
					new String(signatureFile, ASCII).trim());
			update.putLong(highestSeqKey(signer), list.getSeq());
			try {
				settingsManager.mergeSettings(update, NAMESPACE);
			} catch (DbException e) {
				return Verdict.UNAVAILABLE;
			}
			cachedList = null;
			cachedSignature = null;
			cached = null;
			return Verdict.ACCEPTED;
		}
	}

	@Nullable
	public BridgeList current() {
		Settings s;
		try {
			s = settingsManager.getSettings(NAMESPACE);
		} catch (DbException e) {
			return null;
		}
		String text = s.get(KEY_LIST);
		String signature = s.get(KEY_SIGNATURE);
		if (text == null || text.isEmpty() || signature == null
				|| signature.isEmpty()) {
			return null;
		}
		BridgeList list = load(text, signature);
		if (list == null || list.getSeq() <= BUNDLED_SEQ) return null;
		int today = today();
		if (list.getExpiresDay() < today) return null;
		if (list.getIssuedDay() > today + MAX_FUTURE_DAYS) return null;
		return list;
	}

	public long highestSeq() {
		try {
			return highestSeq(settingsManager.getSettings(NAMESPACE));
		} catch (DbException e) {
			return 0;
		}
	}

	private long highestSeq(Settings s) {
		long highest = 0;
		for (byte[] key : keys.forLocalFile()) {
			highest = Math.max(highest, s.getLong(highestSeqKey(key), 0));
		}
		return highest;
	}

	static String highestSeqKey(byte[] signer) {
		return KEY_HIGHEST_SEQ_PREFIX + Base64.toBase64String(signer);
	}

	public void noteBuiltInBridges(boolean inUse) {
		builtInNow = inUse;
		if (!inUse) return;
		int today = today();
		String current = owner.id();
		try {
			Settings s = settingsManager.getSettings(NAMESPACE);
			if (s.getLong(KEY_BUILT_IN_DAY, -1) == today
					&& current.equals(s.get(KEY_BUILT_IN_OWNER))) {
				return;
			}
			Settings update = new Settings();
			update.putLong(KEY_BUILT_IN_DAY, today);
			update.put(KEY_BUILT_IN_OWNER, current);
			settingsManager.mergeSettings(update, NAMESPACE);
		} catch (DbException ignored) {
		}
	}

	public boolean builtInBridgesRecent() {
		if (builtInNow) return true;
		Settings s;
		try {
			s = settingsManager.getSettings(NAMESPACE);
		} catch (DbException e) {
			return false;
		}
		if (s.get(KEY_BUILT_IN_DAY) == null
				&& s.get(KEY_BUILT_IN_OWNER) == null) {
			return false;
		}
		long day = s.getLong(KEY_BUILT_IN_DAY, -1);
		int today = today();
		boolean recent = day >= 0 && day <= today + MAX_FUTURE_DAYS
				&& today - day <= RECENT_BRIDGE_DAYS
				&& owner.id().equals(s.get(KEY_BUILT_IN_OWNER));
		if (!recent) {
			try {
				settingsManager.deleteSettings(NAMESPACE, Arrays.asList(
						KEY_BUILT_IN_DAY, KEY_BUILT_IN_OWNER));
			} catch (DbException ignored) {
			}
		}
		return recent;
	}

	@Nullable
	public FetchState fetchState() {
		try {
			Settings s = settingsManager.getSettings(NAMESPACE);
			return new FetchState(s.getLong(KEY_NEXT_FETCH, 0),
					s.getLong(KEY_FETCHED_VERSION, 0));
		} catch (DbException e) {
			return null;
		}
	}

	public void saveFetchState(long nextFetchMs, long fetchedVersion) {
		Settings update = new Settings();
		update.putLong(KEY_NEXT_FETCH, nextFetchMs);
		update.putLong(KEY_FETCHED_VERSION, fetchedVersion);
		try {
			settingsManager.mergeSettings(update, NAMESPACE);
		} catch (DbException ignored) {
		}
	}

	@Nullable
	private BridgeList load(String text, String signature) {
		synchronized (lock) {
			if (text.equals(cachedList) && signature.equals(cachedSignature)) {
				return cached;
			}
			BridgeList list = null;
			try {
				byte[] file = Base64.decode(text);
				byte[] sig = signature.getBytes(ASCII);
				if (BridgeList.signatureValid(file, sig, keys.forLocalFile())) {
					list = BridgeList.parse(file);
				}
			} catch (RuntimeException ignored) {
			}
			cachedList = text;
			cachedSignature = signature;
			cached = list;
			return list;
		}
	}

	private int today() {
		return utcDay(clock.currentTimeMillis());
	}

	static int utcDay(long ms) {
		long day = ms / DAY_MS;
		if (ms < 0 && ms % DAY_MS != 0) day--;
		return (int) day;
	}
}
