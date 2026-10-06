package org.zerionproject.core.plugin.tor;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.FieldEncryption;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.tor.TorWrapper.HiddenServiceProperties;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_PRIVKEY_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ROTATION_PHASE_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class B4OnionRevocationTest extends BrambleMockTestCase {

	private static final String ORIGINAL = "original-onion";
	private static final String NEXT = "onion-of-nextkey";
	private static final String NEXT_KEY = "nextkey";
	private static final long HOUR = 60L * 60 * 1000;

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final AccountManager accountManager =
			context.mock(AccountManager.class);
	private final SecretKey key = getSecretKey();
	private final Settings stored = new Settings();
	private final List<Contact> contacts = new ArrayList<>();
	private final List<Long> scheduledMs = new ArrayList<>();
	private Transaction txn;
	private long now = 1_700_000_000_000L;

	private static class Transport
			implements B4OnionRotation.B4TorAdapter {

		final List<String> live = new ArrayList<>();
		int generated;

		@Override
		public HiddenServiceProperties publishHiddenService(
				@Nullable String privKey) {
			String k = privKey == null ? "fresh" + (++generated) : privKey;
			live.add("onion-of-" + k);
			return hiddenService("onion-of-" + k, k);
		}

		@Override
		public boolean isPublished(String onion) {
			return live.contains(onion);
		}

		@Override
		@Nullable
		public String getStartupOnion() {
			return ORIGINAL;
		}

		@Override
		public void removeHiddenService(String onion) {
			live.remove(onion);
		}

		@Override
		public void updateTorCurrentPrivKey(String newPrivKey) {
		}

		@Override
		public void mergeTorLocalProperties(TransportProperties props) {
		}
	}

	private static HiddenServiceProperties hiddenService(String onion,
			String privKey) {
		try {
			java.lang.reflect.Constructor<HiddenServiceProperties> c =
					HiddenServiceProperties.class.getDeclaredConstructor(
							String.class, String.class);
			c.setAccessible(true);
			return c.newInstance(onion, privKey);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private String sealed(String plaintext) throws Exception {
		return toHexString(FieldEncryption.encrypt(key,
				plaintext.getBytes(UTF_8)));
	}

	private void expectDatabase() throws Exception {
		txn = new Transaction(null, false);
		context.checking(new DbExpectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(key));
			allowing(db).transactionWithResult(with(any(Boolean.class)),
					withDbCallable(txn));
			allowing(db).transactionWithNullableResult(
					with(any(Boolean.class)), withNullableDbCallable(txn));
			allowing(db).transaction(with(any(Boolean.class)),
					withDbRunnable(txn));
			allowing(settingsManager).getSettings(txn, B4_SETTINGS_NAMESPACE);
			will(returnValue(stored));
			allowing(settingsManager).mergeSettings(with(txn),
					with(any(Settings.class)), with(B4_SETTINGS_NAMESPACE));
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) {
					stored.putAll((Settings) invocation.getParameter(1));
					return null;
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("merges into the stored settings");
				}
			});
			allowing(db).getContacts(txn);
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) {
					return new ArrayList<>(contacts);
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
					d.appendText("returns the remaining contacts");
				}
			});
		}});
	}

	private B4OnionRotation rotation() {
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return now;
			}

			@Override
			public void sleep(long ms) {
			}
		};
		return new B4OnionRotation(db, settingsManager, accountManager, clock);
	}

	private B4OnionRotation rotationWithRecordingScheduler()
			throws Exception {
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return now;
			}

			@Override
			public void sleep(long ms) {
			}
		};
		java.util.concurrent.ScheduledExecutorService scheduler =
				(java.util.concurrent.ScheduledExecutorService)
						java.lang.reflect.Proxy.newProxyInstance(
								getClass().getClassLoader(),
								new Class<?>[] {java.util.concurrent
										.ScheduledExecutorService.class},
								(proxy, method, args) -> {
									if (method.getName().equals("schedule")
											&& args.length == 3) {
										scheduledMs.add(((java.util.concurrent
												.TimeUnit) args[2]).toMillis(
												(Long) args[1]));
									}
									return null;
								});
		try {
			java.lang.reflect.Constructor<B4OnionRotation> c =
					B4OnionRotation.class.getDeclaredConstructor(
							DatabaseComponent.class, SettingsManager.class,
							AccountManager.class, Clock.class,
							java.util.concurrent.ScheduledExecutorService
									.class);
			c.setAccessible(true);
			return c.newInstance(db, settingsManager, accountManager, clock,
					scheduler);
		} catch (NoSuchMethodException e) {
			org.junit.Assert.fail("the retirement is checked only by the "
					+ "hourly round");
			throw e;
		}
	}

	private Contact contact(int id) {
		return getContact(new ContactId(id), getAuthor(),
				new AuthorId(getRandomId()), true);
	}

	private static void removeContact(B4OnionRotation r) throws Exception {
		try {
			Method m = B4OnionRotation.class.getMethod(
					"revokeAfterContactRemoval");
			m.invoke(r);
		} catch (NoSuchMethodException e) {
			r.forceRotate();
		}
	}

	private static void evaluate(B4OnionRotation r) throws Exception {
		try {
			B4OnionRotation.class.getMethod("runOwedRevocation").invoke(r);
		} catch (NoSuchMethodException ignored) {
		}
		r.evaluateTrigger();
		r.evaluateForceExpire();
	}

	@Test
	public void theRevocationIsOwedFromTheRemovalTransaction()
			throws Exception {
		contacts.add(contact(1));
		expectDatabase();
		B4OnionRotation r = rotation();

		r.contactRemoved(txn, new ContactId(2));

		Transport t = new Transport();
		t.live.add(ORIGINAL);
		r.bindAdapter(t);
		evaluate(r);
		assertTrue("the removal transaction did not owe a rotation",
				t.live.contains("onion-of-fresh1"));
	}

	@Test
	public void theRetirementIsCheckedWhenTheWindowEnds() throws Exception {
		contacts.add(contact(1));
		expectDatabase();
		Transport t = new Transport();
		t.live.add(ORIGINAL);
		B4OnionRotation r = rotationWithRecordingScheduler();
		r.bindAdapter(t);

		removeContact(r);

		boolean atTheWindow = false;
		for (long ms : scheduledMs) {
			if (ms >= B4OnionRotation.REVOCATION_RETIRE_MS
					&& ms <= B4OnionRotation.REVOCATION_RETIRE_MS + 60_000L) {
				atTheWindow = true;
			}
		}
		assertTrue("no check is scheduled for when the window ends: "
				+ scheduledMs, atTheWindow);
	}

	@Test
	public void aFailingCheckDoesNotSkipTheOthers() throws Exception {
		contacts.add(contact(1));
		stored.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		stored.put(B4_ALICE_ONION3_NEXT_KEY, sealed(NEXT));
		stored.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY, sealed(NEXT_KEY));
		stored.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY,
				sealed(String.valueOf(now)));
		stored.put("alice_revocation_owed", sealed("1"));
		expectDatabase();
		Transport t = new Transport() {
			@Override
			public HiddenServiceProperties publishHiddenService(
					@Nullable String privKey) {
				if (NEXT_KEY.equals(privKey)) {
					throw new RuntimeException("tor refused the key");
				}
				return super.publishHiddenService(privKey);
			}
		};
		t.live.add(ORIGINAL);
		B4OnionRotation r = rotation();
		r.bindAdapter(t);

		try {
			B4OnionRotation.class.getDeclaredMethod("evaluateAll").invoke(r);
		} catch (NoSuchMethodException e) {
			org.junit.Assert.fail("one failing check skips the rest of the "
					+ "hourly round");
		}

		assertTrue("the owed revocation did not run after the republish "
				+ "failed", t.live.contains("onion-of-fresh1"));
	}

	@Test
	public void aRemovalWhileAnnouncingAbandonsTheNextOnion()
			throws Exception {
		contacts.add(contact(1));
		stored.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		stored.put(B4_ALICE_ONION3_NEXT_KEY, sealed(NEXT));
		stored.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY, sealed(NEXT_KEY));
		stored.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY,
				sealed(String.valueOf(now)));
		expectDatabase();
		Transport t = new Transport();
		t.live.add(ORIGINAL);
		t.live.add(NEXT);
		B4OnionRotation r = rotation();
		r.bindAdapter(t);

		removeContact(r);

		assertFalse("the next onion the removed contact knew is published",
				t.live.contains(NEXT));
		assertTrue("a fresh next onion is announced",
				t.live.contains("onion-of-fresh1"));
	}

	@Test
	public void aRemovalWhileTheTransportIsDownIsCarriedOutLater()
			throws Exception {
		contacts.add(contact(1));
		expectDatabase();
		B4OnionRotation r = rotation();
		removeContact(r);
		Transport t = new Transport();
		t.live.add(ORIGINAL);
		r.bindAdapter(t);

		evaluate(r);

		assertTrue("the owed rotation never ran",
				t.live.contains("onion-of-fresh1"));
	}

	@Test
	public void withNoContactLeftTheOldAddressIsRetiredAtOnce()
			throws Exception {
		expectDatabase();
		Transport t = new Transport();
		t.live.add(ORIGINAL);
		B4OnionRotation r = rotation();
		r.bindAdapter(t);

		removeContact(r);

		assertFalse("the address the removed contact knew is published",
				t.live.contains(ORIGINAL));
	}

	@Test
	public void theOldAddressIsRetiredWithinTheRevocationWindow()
			throws Exception {
		contacts.add(contact(1));
		expectDatabase();
		Transport t = new Transport();
		t.live.add(ORIGINAL);
		B4OnionRotation r = rotation();
		r.bindAdapter(t);
		removeContact(r);
		assertTrue(t.live.contains(ORIGINAL));

		now += 49 * HOUR;
		evaluate(r);

		assertFalse("the removed contact's address outlives the window",
				t.live.contains(ORIGINAL));
	}
}
