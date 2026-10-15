package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
public class OnionAuthStore {

	static final String NAMESPACE = "onion-client-auth";

	private static final String K_STATE = "state";
	private static final String K_LOCAL_GEN = "localGen";
	private static final String K_PEER_GEN = "peerGen";
	private static final String K_DIAL_PRIV = "dialPriv";
	private static final String K_DIAL_PUB = "dialPub";
	private static final String K_PEER_ONION = "peerOnion";
	private static final String K_PEER_PUB = "peerPub";
	private static final String K_READY = "ready";
	private static final String K_PROBE_OK = "probeOk";
	private static final String K_PEER_PROBE_OK = "peerProbeOk";
	private static final String K_COMMIT_SENT = "commitSent";
	private static final String K_PEER_COMMIT = "peerCommit";
	private static final String K_PROBING = "probing";
	private static final String K_PROBE_UNTIL = "probeUntil";
	private static final String K_STARTED = "started";
	private static final String K_ROTATE_ACKED = "rotateAcked";
	private static final String K_REPAIRS = "repairs";
	private static final String K_NEXT_REPAIR = "nextRepair";
	private static final String K_COMMIT_RESENT = "commitResent";
	private static final String K_REKEYING = "rekeying";
	private static final String K_REKEYED = "rekeyed";
	private static final String K_LAST_SEEN = "lastSeen";
	private static final String K_OPEN_DIALS = "openDials";
	private static final String K_NEXT_OPEN_DIAL = "nextOpenDial";
	private static final String K_SILENCE_REPAIRS = "silenceRepairs";
	private static final String K_NEXT_SILENCE_REPAIR = "nextSilenceRepair";
	private static final String K_COMMITTED_KEY = "committedKey";
	private static final String K_COMMITTED_ONION = "committedOnion";
	private static final String K_COMMITTED_GEN = "committedGen";
	private static final String K_COMMITTED_LOCKED = "committedLocked";

	private static final String SVC_ONION = "svc.onion";
	private static final String SVC_KEY = "svc.key";
	private static final String SVC_GEN = "svc.gen";
	private static final String SVC_OLD_ONION = "svc.old.onion";
	private static final String SVC_OLD_KEY = "svc.old.key";
	private static final String SVC_OLD_SINCE = "svc.old.since";
	private static final String SVC_LAST_ROTATION = "svc.lastRotation";
	private static final String SVC_ROTATION_PERIOD = "svc.rotationPeriod";
	private static final String SVC_REVOCATION_PENDING = "svc.revocationPending";

	private static final String[] CONTACT_FIELDS = {K_STATE, K_LOCAL_GEN,
			K_PEER_GEN, K_DIAL_PRIV, K_DIAL_PUB, K_PEER_ONION, K_PEER_PUB,
			K_READY, K_PROBE_OK, K_PEER_PROBE_OK, K_COMMIT_SENT,
			K_PEER_COMMIT, K_PROBING, K_PROBE_UNTIL, K_STARTED,
			K_ROTATE_ACKED, K_REPAIRS, K_NEXT_REPAIR, K_COMMIT_RESENT,
			K_REKEYING, K_REKEYED, K_LAST_SEEN, K_OPEN_DIALS,
			K_NEXT_OPEN_DIAL, K_SILENCE_REPAIRS, K_NEXT_SILENCE_REPAIR,
			K_COMMITTED_KEY, K_COMMITTED_ONION, K_COMMITTED_GEN,
			K_COMMITTED_LOCKED};

	public static final class ServiceRecord {

		@Nullable
		public String onion;
		@Nullable
		public String privateKey;
		public long gen;
		@Nullable
		public String oldOnion;
		@Nullable
		public String oldPrivateKey;
		public long oldSinceMs;
		public long lastRotationMs;
		public long rotationPeriodMs;
		public boolean revocationPending;
	}

	private final SettingsManager settingsManager;

	@Inject
	public OnionAuthStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	private static String key(ContactId c, String field) {
		return "c" + c.getInt() + "." + field;
	}

	public OnionAuthRecord load(Transaction txn, ContactId c)
			throws DbException {
		Settings s = settingsManager.getSettings(txn, NAMESPACE);
		return read(s, c);
	}

	private OnionAuthRecord read(Settings s, ContactId c) {
		OnionAuthRecord r = new OnionAuthRecord(c);
		String state = s.get(key(c, K_STATE));
		if (state == null || state.isEmpty()) return r;
		r.state = State.valueOf(state);
		r.localGen = s.getLong(key(c, K_LOCAL_GEN), 0);
		r.peerGen = s.getLong(key(c, K_PEER_GEN), 0);
		r.dialPrivateKey = bytes(s.get(key(c, K_DIAL_PRIV)));
		r.dialPublicKey = bytes(s.get(key(c, K_DIAL_PUB)));
		r.peerOnion = empty(s.get(key(c, K_PEER_ONION)));
		r.peerPublicKey = bytes(s.get(key(c, K_PEER_PUB)));
		r.readyReceived = s.getBoolean(key(c, K_READY), false);
		r.probeSucceeded = s.getBoolean(key(c, K_PROBE_OK), false);
		r.peerProbeSucceeded = s.getBoolean(key(c, K_PEER_PROBE_OK), false);
		r.commitSent = s.getBoolean(key(c, K_COMMIT_SENT), false);
		r.peerCommitReceived = s.getBoolean(key(c, K_PEER_COMMIT), false);
		r.probing = s.getBoolean(key(c, K_PROBING), false);
		r.probeUntilMs = s.getLong(key(c, K_PROBE_UNTIL), 0);
		r.negotiationStartedMs = s.getLong(key(c, K_STARTED), 0);
		r.rotateAcked = s.getBoolean(key(c, K_ROTATE_ACKED), false);
		r.repairs = s.getInt(key(c, K_REPAIRS), 0);
		r.nextRepairMs = s.getLong(key(c, K_NEXT_REPAIR), 0);
		r.commitResent = s.getBoolean(key(c, K_COMMIT_RESENT), false);
		r.rekeying = s.getBoolean(key(c, K_REKEYING), false);
		r.rekeyedMs = s.getLong(key(c, K_REKEYED), 0);
		r.lastSeenMs = s.getLong(key(c, K_LAST_SEEN), 0);
		r.openDials = s.getInt(key(c, K_OPEN_DIALS), 0);
		r.nextOpenDialMs = s.getLong(key(c, K_NEXT_OPEN_DIAL), 0);
		r.silenceRepairs = s.getInt(key(c, K_SILENCE_REPAIRS), 0);
		r.nextSilenceRepairMs = s.getLong(key(c, K_NEXT_SILENCE_REPAIR), 0);
		r.committedPeerKey = bytes(s.get(key(c, K_COMMITTED_KEY)));
		r.committedPeerOnion = empty(s.get(key(c, K_COMMITTED_ONION)));
		r.committedPeerGen = s.getLong(key(c, K_COMMITTED_GEN), 0);
		r.committedLocked = s.getBoolean(key(c, K_COMMITTED_LOCKED), false);
		return r;
	}

	public void save(Transaction txn, OnionAuthRecord r) throws DbException {
		Settings s = new Settings();
		ContactId c = r.contactId;
		s.put(key(c, K_STATE), r.state.name());
		s.putLong(key(c, K_LOCAL_GEN), r.localGen);
		s.putLong(key(c, K_PEER_GEN), r.peerGen);
		s.put(key(c, K_DIAL_PRIV), hex(r.dialPrivateKey));
		s.put(key(c, K_DIAL_PUB), hex(r.dialPublicKey));
		s.put(key(c, K_PEER_ONION), r.peerOnion == null ? "" : r.peerOnion);
		s.put(key(c, K_PEER_PUB), hex(r.peerPublicKey));
		s.putBoolean(key(c, K_READY), r.readyReceived);
		s.putBoolean(key(c, K_PROBE_OK), r.probeSucceeded);
		s.putBoolean(key(c, K_PEER_PROBE_OK), r.peerProbeSucceeded);
		s.putBoolean(key(c, K_COMMIT_SENT), r.commitSent);
		s.putBoolean(key(c, K_PEER_COMMIT), r.peerCommitReceived);
		s.putBoolean(key(c, K_PROBING), r.probing);
		s.putLong(key(c, K_PROBE_UNTIL), r.probeUntilMs);
		s.putLong(key(c, K_STARTED), r.negotiationStartedMs);
		s.putBoolean(key(c, K_ROTATE_ACKED), r.rotateAcked);
		s.putInt(key(c, K_REPAIRS), r.repairs);
		s.putLong(key(c, K_NEXT_REPAIR), r.nextRepairMs);
		s.putBoolean(key(c, K_COMMIT_RESENT), r.commitResent);
		s.putBoolean(key(c, K_REKEYING), r.rekeying);
		s.putLong(key(c, K_REKEYED), r.rekeyedMs);
		s.putLong(key(c, K_LAST_SEEN), r.lastSeenMs);
		s.putInt(key(c, K_OPEN_DIALS), r.openDials);
		s.putLong(key(c, K_NEXT_OPEN_DIAL), r.nextOpenDialMs);
		s.putInt(key(c, K_SILENCE_REPAIRS), r.silenceRepairs);
		s.putLong(key(c, K_NEXT_SILENCE_REPAIR), r.nextSilenceRepairMs);
		s.put(key(c, K_COMMITTED_KEY), hex(r.committedPeerKey));
		s.put(key(c, K_COMMITTED_ONION),
				r.committedPeerOnion == null ? "" : r.committedPeerOnion);
		s.putLong(key(c, K_COMMITTED_GEN), r.committedPeerGen);
		s.putBoolean(key(c, K_COMMITTED_LOCKED), r.committedLocked);
		settingsManager.mergeSettings(txn, s, NAMESPACE);
	}

	public void clear(Transaction txn, ContactId c) throws DbException {
		List<String> keys = new ArrayList<>(CONTACT_FIELDS.length);
		for (String f : CONTACT_FIELDS) keys.add(key(c, f));
		settingsManager.deleteSettings(txn, NAMESPACE, keys);
	}

	public void deleteClearedRecords(Transaction txn) throws DbException {
		Settings s = settingsManager.getSettings(txn, NAMESPACE);
		java.util.Set<String> cleared = new java.util.HashSet<>();
		for (String k : s.keySet()) {
			int dot = k.indexOf('.');
			if (!k.startsWith("c") || dot < 2) continue;
			if (!k.substring(1, dot).chars().allMatch(Character::isDigit)) {
				continue;
			}
			String prefix = k.substring(0, dot + 1);
			String state = s.get(prefix + K_STATE);
			if (state == null || state.isEmpty()) cleared.add(k);
		}
		if (!cleared.isEmpty()) {
			settingsManager.deleteSettings(txn, NAMESPACE, cleared);
		}
	}

	public void saveRotationPeriod(Transaction txn, long periodMs)
			throws DbException {
		Settings s = new Settings();
		s.putLong(SVC_ROTATION_PERIOD, periodMs);
		settingsManager.mergeSettings(txn, s, NAMESPACE);
	}

	public List<OnionAuthRecord> loadAll(Transaction txn) throws DbException {
		Settings s = settingsManager.getSettings(txn, NAMESPACE);
		List<OnionAuthRecord> out = new ArrayList<>();
		for (String k : s.keySet()) {
			if (k.startsWith("c") && k.endsWith("." + K_STATE)) {
				String v = s.get(k);
				if (v == null || v.isEmpty()) continue;
				int id = Integer.parseInt(k.substring(1,
						k.length() - K_STATE.length() - 1));
				out.add(read(s, new ContactId(id)));
			}
		}
		return out;
	}

	public ServiceRecord loadService(Transaction txn) throws DbException {
		Settings s = settingsManager.getSettings(txn, NAMESPACE);
		ServiceRecord r = new ServiceRecord();
		r.onion = empty(s.get(SVC_ONION));
		r.privateKey = empty(s.get(SVC_KEY));
		r.gen = s.getLong(SVC_GEN, 0);
		r.oldOnion = empty(s.get(SVC_OLD_ONION));
		r.oldPrivateKey = empty(s.get(SVC_OLD_KEY));
		r.oldSinceMs = s.getLong(SVC_OLD_SINCE, 0);
		r.lastRotationMs = s.getLong(SVC_LAST_ROTATION, 0);
		r.rotationPeriodMs = s.getLong(SVC_ROTATION_PERIOD, 0);
		r.revocationPending = s.getBoolean(SVC_REVOCATION_PENDING, false);
		return r;
	}

	public void saveService(Transaction txn, ServiceRecord r)
			throws DbException {
		Settings s = new Settings();
		s.put(SVC_ONION, r.onion == null ? "" : r.onion);
		s.put(SVC_KEY, r.privateKey == null ? "" : r.privateKey);
		s.putLong(SVC_GEN, r.gen);
		s.put(SVC_OLD_ONION, r.oldOnion == null ? "" : r.oldOnion);
		s.put(SVC_OLD_KEY, r.oldPrivateKey == null ? "" : r.oldPrivateKey);
		s.putLong(SVC_OLD_SINCE, r.oldSinceMs);
		s.putLong(SVC_LAST_ROTATION, r.lastRotationMs);
		s.putLong(SVC_ROTATION_PERIOD, r.rotationPeriodMs);
		s.putBoolean(SVC_REVOCATION_PENDING, r.revocationPending);
		settingsManager.mergeSettings(txn, s, NAMESPACE);
	}

	@Nullable
	private static String empty(@Nullable String v) {
		return v == null || v.isEmpty() ? null : v;
	}

	private static String hex(@Nullable byte[] b) {
		return b == null ? "" : StringUtils.toHexString(b);
	}

	@Nullable
	private static byte[] bytes(@Nullable String hex) {
		if (hex == null || hex.isEmpty()) return null;
		try {
			return StringUtils.fromHexString(hex);
		} catch (org.zerionproject.core.api.FormatException e) {
			return null;
		}
	}
}
