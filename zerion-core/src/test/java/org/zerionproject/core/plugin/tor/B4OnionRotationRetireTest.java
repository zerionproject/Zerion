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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_CURRENT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_PRIVKEY_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ROTATION_PHASE_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.plugin.B4Constants.FORCE_EXPIRE_DAYS;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class B4OnionRotationRetireTest extends BrambleMockTestCase {

	private static final String ORIGINAL = "original-onion";
	private static final String NEXT = "onion-of-nextkey";
	private static final String NEXT_KEY = "nextkey";

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final AccountManager accountManager =
			context.mock(AccountManager.class);
	private final SecretKey key = getSecretKey();
	private final long now = 1_700_000_000_000L;
	private final Settings stored = new Settings();
	private final ContactId contactId = new ContactId(7);
	private final Contact contact = getContact(contactId, getAuthor(),
			new AuthorId(getRandomId()), true);

	private static final class Transport
			implements B4OnionRotation.B4TorAdapter {

		final List<String> live = new ArrayList<>();
		final List<String> removed = new ArrayList<>();
		@Nullable
		final String startupOnion;

		Transport(@Nullable String startupOnion) {
			this.startupOnion = startupOnion;
		}

		@Override
		public HiddenServiceProperties publishHiddenService(
				@Nullable String privKey) {
			live.add("onion-of-" + privKey);
			return hiddenService("onion-of-" + privKey, privKey);
		}

		@Override
		public boolean isPublished(String onion) {
			return live.contains(onion);
		}

		@Override
		@Nullable
		public String getStartupOnion() {
			return startupOnion;
		}

		@Override
		public void removeHiddenService(String onion) {
			removed.add(onion);
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

	private void announcing(long announcedAt) throws Exception {
		stored.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		stored.put(B4_ALICE_ONION3_NEXT_KEY, sealed(NEXT));
		stored.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY, sealed(NEXT_KEY));
		stored.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY,
				sealed(String.valueOf(announcedAt)));
	}

	private void expectDatabase() throws Exception {
		Transaction txn = new Transaction(null, false);
		context.checking(new DbExpectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(key));
			allowing(db).transactionWithResult(with(any(Boolean.class)),
					withDbCallable(txn));
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
			will(returnValue(Collections.singletonList(contact)));
		}});
	}

	private B4OnionRotation rotation(long clockNow) {
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return clockNow;
			}

			@Override
			public void sleep(long ms) {
			}
		};
		return new B4OnionRotation(db, settingsManager, accountManager, clock);
	}

	private Transport startedWith(String startupOnion) {
		Transport t = new Transport(startupOnion);
		t.live.add(startupOnion);
		t.live.add(NEXT);
		return t;
	}

	@Test
	public void theFirstPromotionAfterMigrationRetiresTheOriginalOnion()
			throws Exception {
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = startedWith(ORIGINAL);
		B4OnionRotation r = rotation(now);
		r.bindAdapter(transport);
		r.onPeerSyncSessionEstablished(contactId);
		assertEquals("the rotation completed", "IDLE", open(
				B4_ALICE_ROTATION_PHASE_KEY));
		assertFalse("the original onion is still published",
				transport.live.contains(ORIGINAL));
		assertTrue(transport.live.contains(NEXT));
		assertEquals(NEXT, open(B4_ALICE_ONION3_CURRENT_KEY));
	}

	@Test
	public void theFirstPromotionAtTheForceExpiryRetiresTheOriginalOnion()
			throws Exception {
		announcing(now - (FORCE_EXPIRE_DAYS + 1) * 24L * 60 * 60 * 1000);
		expectDatabase();
		Transport transport = startedWith(ORIGINAL);
		B4OnionRotation r = rotation(now);
		r.bindAdapter(transport);
		r.evaluateForceExpire();
		assertFalse("the original onion is still published",
				transport.live.contains(ORIGINAL));
		assertTrue(transport.live.contains(NEXT));
	}

	@Test
	public void aResumedPromotionNeverRemovesTheOnionItPromotes()
			throws Exception {
		announcing(now);
		expectDatabase();
		Transport transport = new Transport(NEXT);
		transport.live.add(NEXT);
		B4OnionRotation r = rotation(now);
		r.bindAdapter(transport);
		assertTrue(r.forceCompleteRotation());
		assertTrue(transport.removed.isEmpty());
		assertEquals(Collections.singletonList(NEXT), transport.live);
	}

	@Test
	public void aLaterPromotionRetiresTheRecordedCurrentOnion()
			throws Exception {
		announcing(now);
		stored.put(B4_ALICE_ONION3_CURRENT_KEY, sealed("current-onion"));
		expectDatabase();
		Transport transport = new Transport(ORIGINAL);
		transport.live.add("current-onion");
		transport.live.add(NEXT);
		B4OnionRotation r = rotation(now);
		r.bindAdapter(transport);
		assertTrue(r.forceCompleteRotation());
		assertEquals(Collections.singletonList("current-onion"),
				transport.removed);
		assertEquals(Collections.singletonList(NEXT), transport.live);
	}

	private String open(String settingsKey) throws Exception {
		return new String(FieldEncryption.decrypt(key,
				org.zerionproject.core.util.StringUtils.fromHexString(
						stored.get(settingsKey))), UTF_8);
	}
}
