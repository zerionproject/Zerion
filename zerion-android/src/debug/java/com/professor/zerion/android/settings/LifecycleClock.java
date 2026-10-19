package com.professor.zerion.android.settings;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.crypto.FieldEncryption;
import org.zerionproject.core.plugin.tor.auth.OnionAuthRecord;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore.ServiceRecord;

import java.security.GeneralSecurityException;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_LAST_ROTATION_TIME_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.fromHexString;
import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
final class LifecycleClock {

	private LifecycleClock() {
	}

	static void age(TransactionManager db, SettingsManager settings,
			AccountManager accounts, OnionAuthStore store, long deltaMs)
			throws DbException {
		if (deltaMs <= 0) throw new IllegalArgumentException();
		db.transaction(false, txn -> {
			SecretKey key = accounts.getDatabaseKey();
			if (key == null) throw new DbException();
			Settings stored = settings.getSettings(txn, B4_SETTINGS_NAMESPACE);
			Settings aged = new Settings();
			for (String k : stored.keySet()) {
				if (!isRotationTime(k)) continue;
				String plain = open(key, stored.get(k));
				if (plain == null) continue;
				long t;
				try {
					t = Long.parseLong(plain);
				} catch (NumberFormatException e) {
					continue;
				}
				aged.put(k, seal(key, String.valueOf(earlier(t, deltaMs))));
			}
			if (!aged.isEmpty()) {
				settings.mergeSettings(txn, aged, B4_SETTINGS_NAMESPACE);
			}
			for (OnionAuthRecord r : store.loadAll(txn)) {
				r.negotiationStartedMs = earlier(r.negotiationStartedMs, deltaMs);
				r.lastSeenMs = earlier(r.lastSeenMs, deltaMs);
				r.lastContactMs = earlier(r.lastContactMs, deltaMs);
				r.nextRepairMs = earlier(r.nextRepairMs, deltaMs);
				r.nextOpenDialMs = earlier(r.nextOpenDialMs, deltaMs);
				r.nextSilenceRepairMs = earlier(r.nextSilenceRepairMs, deltaMs);
				r.rekeyedMs = earlier(r.rekeyedMs, deltaMs);
				r.probeUntilMs = earlier(r.probeUntilMs, deltaMs);
				store.save(txn, r);
			}
			ServiceRecord svc = store.loadService(txn);
			if (svc.gen != 0 || svc.onion != null || svc.oldOnion != null) {
				svc.lastRotationMs = earlier(svc.lastRotationMs, deltaMs);
				svc.oldSinceMs = earlier(svc.oldSinceMs, deltaMs);
				store.saveService(txn, svc);
			}
		});
	}

	static boolean isRotationTime(String key) {
		return key.equals(B4_ALICE_LAST_ROTATION_TIME_MS_KEY)
				|| key.equals(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY)
				|| key.startsWith(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX)
				|| key.startsWith(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX);
	}

	static long earlier(long timeMs, long deltaMs) {
		if (timeMs == 0) return 0;
		return Math.max(1, timeMs - deltaMs);
	}

	@Nullable
	static String open(SecretKey key, @Nullable String sealedHex) {
		if (sealedHex == null || sealedHex.isEmpty()) return null;
		try {
			return new String(FieldEncryption.decrypt(key,
					fromHexString(sealedHex)), UTF_8);
		} catch (FormatException | GeneralSecurityException e) {
			return null;
		}
	}

	static String seal(SecretKey key, String plaintext) throws DbException {
		try {
			return toHexString(FieldEncryption.encrypt(key,
					plaintext.getBytes(UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new DbException(e);
		}
	}
}
