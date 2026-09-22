package org.zerionproject.core.plugin.tor;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.plugin.B4Constants;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.FieldEncryption;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.briarproject.onionwrapper.TorWrapper.HiddenServiceProperties;
import org.jmock.Expectations;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_PRIVKEY_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ROTATION_PHASE_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A2-NET-01: the next onion is published again by every process while the
 * rotation announces, promotion publishes it when no process has, and a
 * peer's session after the migration grace counts as its migration.
 */
public class B4OnionRotationTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final AccountManager accountManager =
			context.mock(AccountManager.class);
	private final SecretKey key = getSecretKey();
	private final long now = 1_700_000_000_000L;

	private static final class Recording implements B4OnionRotation.B4TorAdapter {
		final List<String> published = new ArrayList<>();
		final List<String> live = new ArrayList<>();

		@Override
		public HiddenServiceProperties publishHiddenService(
				@Nullable String privKey) {
			published.add(privKey == null ? "generated" : privKey);
			live.add("onion-of-" + privKey);
			return hiddenService("onion-of-" + privKey, privKey);
		}

		@Override
		public boolean isPublished(String onion) {
			return live.contains(onion);
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

	private Settings announcing(String onion, String privKey,
			long announcedAt) throws Exception {
		Settings s = new Settings();
		s.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		s.put(B4_ALICE_ONION3_NEXT_KEY, sealed(onion));
		s.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY, sealed(privKey));
		s.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY,
				sealed(String.valueOf(announcedAt)));
		return s;
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

	@Test
	public void aFreshProcessPublishesTheAnnouncedOnionAgain()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Settings s = announcing("onion-of-nextkey", "nextkey", now);
		context.checking(new DbExpectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(key));
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			allowing(settingsManager).getSettings(txn, B4_SETTINGS_NAMESPACE);
			will(returnValue(s));
		}});
		Recording adapter = new Recording();
		B4OnionRotation r = rotation(now);
		r.bindAdapter(adapter);
		r.republishPendingOnion();
		assertEquals(Collections.singletonList("nextkey"), adapter.published);
	}

	@Test
	public void anAlreadyPublishedOnionIsNotPublishedTwice() throws Exception {
		Transaction txn = new Transaction(null, true);
		Settings s = announcing("onion-of-nextkey", "nextkey", now);
		context.checking(new DbExpectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(key));
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			allowing(settingsManager).getSettings(txn, B4_SETTINGS_NAMESPACE);
			will(returnValue(s));
		}});
		Recording adapter = new Recording();
		adapter.live.add("onion-of-nextkey");
		B4OnionRotation r = rotation(now);
		r.bindAdapter(adapter);
		r.republishPendingOnion();
		assertTrue(adapter.published.isEmpty());
	}

	@Test
	public void nothingIsPublishedWhileIdle() throws Exception {
		Transaction txn = new Transaction(null, true);
		context.checking(new DbExpectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(key));
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			allowing(settingsManager).getSettings(txn, B4_SETTINGS_NAMESPACE);
			will(returnValue(new Settings()));
		}});
		Recording adapter = new Recording();
		B4OnionRotation r = rotation(now);
		r.bindAdapter(adapter);
		r.republishPendingOnion();
		assertTrue(adapter.published.isEmpty());
	}

	/** A session after the grace marks the peer migrated; before it, only
	 *  as having received the announcement. */
	@Test
	public void aSessionAfterTheGraceMarksThePeerMigrated() throws Exception {
		ContactId cid = new ContactId(7);
		Contact c = getContact(cid, org.zerionproject.core.test.TestUtils
				.getAuthor(), new org.zerionproject.core.api.identity.AuthorId(
				org.zerionproject.core.test.TestUtils.getRandomId()), true);
		Transaction txn = new Transaction(null, false);
		Settings s = announcing("onion-of-nextkey", "nextkey", now);
		List<Settings> merged = new ArrayList<>();
		context.checking(new DbExpectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(key));
			oneOf(db).transactionWithResult(with(false), withDbCallable(txn));
			allowing(settingsManager).getSettings(txn, B4_SETTINGS_NAMESPACE);
			will(returnValue(s));
			allowing(settingsManager).mergeSettings(with(txn),
					with(any(Settings.class)), with(B4_SETTINGS_NAMESPACE));
			will(new org.jmock.api.Action() {
				@Override
				public Object invoke(org.jmock.api.Invocation inv) {
					merged.add((Settings) inv.getParameter(1));
					return null;
				}

				@Override
				public void describeTo(org.hamcrest.Description d) {
				}
			});
			allowing(db).getContacts(txn);
			will(returnValue(Collections.singletonList(c)));
		}});
		B4OnionRotation early = rotation(now + 1000);
		early.bindAdapter(new Recording());
		early.onPeerSyncSessionEstablished(cid);
		assertEquals(1, merged.size());
		String state = new String(FieldEncryption.decrypt(key,
				org.zerionproject.core.util.StringUtils.fromHexString(
						merged.get(0).get(B4Constants
								.B4_PEER_ROTATION_STATE_KEY_PREFIX
								+ cid.getInt()))), UTF_8);
		assertEquals("PRE_ANNOUNCED", state);
	}
}
