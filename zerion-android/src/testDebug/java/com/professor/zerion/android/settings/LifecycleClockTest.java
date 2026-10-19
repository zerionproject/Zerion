package com.professor.zerion.android.settings;

import org.junit.Test;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.NullableDbCallable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.plugin.OnionClientAuthManager.State;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.plugin.tor.auth.OnionAuthRecord;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore;
import org.zerionproject.core.plugin.tor.auth.OnionAuthStore.ServiceRecord;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_LAST_ROTATION_TIME_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.test.TestUtils.getSecretKey;

public class LifecycleClockTest {

	private static final long T = 1_700_000_000_000L;
	private static final long DAY = TimeUnit.DAYS.toMillis(1);

	private final SecretKey key = getSecretKey();
	private final Map<String, Settings> stored = new HashMap<>();
	private final SettingsManager settings = new SettingsManager() {
		@Override
		public Settings getSettings(String namespace) {
			Settings s = new Settings();
			Settings kept = stored.get(namespace);
			if (kept != null) s.putAll(kept);
			return s;
		}

		@Override
		public Settings getSettings(Transaction txn, String namespace) {
			return getSettings(namespace);
		}

		@Override
		public void mergeSettings(Settings s, String namespace) {
			stored.computeIfAbsent(namespace, n -> new Settings()).putAll(s);
		}

		@Override
		public void mergeSettings(Transaction txn, Settings s,
				String namespace) {
			mergeSettings(s, namespace);
		}
	};
	private final TransactionManager db = (TransactionManager)
			Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] {TransactionManager.class},
					(p, m, args) -> {
						Transaction txn = new Transaction(null, false);
						switch (m.getName()) {
							case "transaction":
								((DbRunnable<?>) args[1]).run(txn);
								return null;
							case "transactionWithResult":
								return ((DbCallable<?, ?>) args[1]).call(txn);
							case "transactionWithNullableResult":
								return ((NullableDbCallable<?, ?>) args[1])
										.call(txn);
							default:
								throw new UnsupportedOperationException();
						}
					});
	private final AccountManager accounts = (AccountManager)
			Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] {AccountManager.class},
					(p, m, args) -> m.getName().equals("getDatabaseKey")
							? key : null);
	private final OnionAuthStore store = new OnionAuthStore(settings);

	private String b4(String settingsKey) throws Exception {
		return LifecycleClock.open(key,
				settings.getSettings(B4_SETTINGS_NAMESPACE).get(settingsKey));
	}

	@Test
	public void agingMovesEveryStoredTimeBackAndKeepsItSealed()
			throws Exception {
		Settings b4 = new Settings();
		b4.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
				LifecycleClock.seal(key, String.valueOf(T)));
		b4.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY,
				LifecycleClock.seal(key, String.valueOf(T + 5)));
		b4.put(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX + 3,
				LifecycleClock.seal(key, String.valueOf(T + 7)));
		b4.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + 3,
				LifecycleClock.seal(key, String.valueOf(T + 9)));
		String failures = LifecycleClock.seal(key, "4");
		b4.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX + 3, failures);
		String next = LifecycleClock.seal(key, "nextonion");
		b4.put(B4_ALICE_ONION3_NEXT_KEY, next);
		settings.mergeSettings(b4, B4_SETTINGS_NAMESPACE);
		OnionAuthRecord r = new OnionAuthRecord(new ContactId(3));
		r.state = State.AUTH_REQUIRED;
		r.negotiationStartedMs = T;
		r.lastSeenMs = T + 1;
		r.lastContactMs = T + 10;
		r.nextRepairMs = T + 2;
		r.nextOpenDialMs = T + 3;
		r.nextSilenceRepairMs = T + 4;
		r.rekeyedMs = 0;
		r.probeUntilMs = T + 6;
		store.save(null, r);
		ServiceRecord svc = new ServiceRecord();
		svc.gen = 2;
		svc.onion = "current";
		svc.oldOnion = "old";
		svc.lastRotationMs = T + 8;
		svc.oldSinceMs = T + 8;
		store.saveService(null, svc);

		LifecycleClock.age(db, settings, accounts, store, DAY);

		assertEquals(String.valueOf(T - DAY),
				b4(B4_ALICE_LAST_ROTATION_TIME_MS_KEY));
		assertEquals(String.valueOf(T + 5 - DAY),
				b4(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY));
		assertEquals(String.valueOf(T + 7 - DAY),
				b4(B4_CONTACT_ONION3_ANNOUNCED_AT_MS_KEY_PREFIX + 3));
		assertEquals(String.valueOf(T + 9 - DAY),
				b4(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + 3));
		Settings after = settings.getSettings(B4_SETTINGS_NAMESPACE);
		assertNotEquals(String.valueOf(T - DAY),
				after.get(B4_ALICE_LAST_ROTATION_TIME_MS_KEY));
		assertEquals(failures,
				after.get(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX + 3));
		assertEquals(next, after.get(B4_ALICE_ONION3_NEXT_KEY));
		OnionAuthRecord aged = store.load(null, new ContactId(3));
		assertEquals(T - DAY, aged.negotiationStartedMs);
		assertEquals(T + 1 - DAY, aged.lastSeenMs);
		assertEquals(T + 10 - DAY, aged.lastContactMs);
		assertEquals(T + 2 - DAY, aged.nextRepairMs);
		assertEquals(T + 3 - DAY, aged.nextOpenDialMs);
		assertEquals(T + 4 - DAY, aged.nextSilenceRepairMs);
		assertEquals(0, aged.rekeyedMs);
		assertEquals(T + 6 - DAY, aged.probeUntilMs);
		assertEquals(State.AUTH_REQUIRED, aged.state);
		ServiceRecord agedSvc = store.loadService(null);
		assertEquals(T + 8 - DAY, agedSvc.lastRotationMs);
		assertEquals(T + 8 - DAY, agedSvc.oldSinceMs);
		assertEquals("current", agedSvc.onion);
		assertEquals("old", agedSvc.oldOnion);
		assertEquals(2, agedSvc.gen);
	}

	@Test
	public void agingWithoutAnyStateWritesNoServiceRecord() throws Exception {
		LifecycleClock.age(db, settings, accounts, store, DAY);
		assertNull(stored.get(B4_SETTINGS_NAMESPACE));
		assertEquals(0, store.loadService(null).gen);
		assertNull(store.loadService(null).onion);
	}

	@Test
	public void theDetailsShowOnlyTheStartOfAnAddress() {
		assertEquals("ru3bfv", LifecycleTests.prefix(
				"ru3bfvgi52cq7zgscrqw6nhcaga5thrvrnnb23tefpdv7hxwrrhfl6yd",
				"none"));
		assertEquals("none", LifecycleTests.prefix(null, "none"));
		assertEquals("abc", LifecycleTests.prefix("abc", "none"));
	}
}
