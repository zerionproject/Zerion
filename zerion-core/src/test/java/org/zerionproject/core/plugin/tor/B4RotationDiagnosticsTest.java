package org.zerionproject.core.plugin.tor;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.FieldEncryption;
import org.zerionproject.core.plugin.tor.B4OnionRotation.PeerRotationState;
import org.zerionproject.core.plugin.tor.B4OnionRotation.RotationPhase;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.junit.Test;

import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_NEXT_ANNOUNCED_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ROTATION_PHASE_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_ONION3_PENDING_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PEER_ROTATION_STATE_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class B4RotationDiagnosticsTest extends BrambleMockTestCase {

	private static final String PENDING =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";
	private static final ContactId CONTACT = new ContactId(7);

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final AccountManager accountManager =
			context.mock(AccountManager.class);
	private final SecretKey key = getSecretKey();
	private final long now = 1_700_000_000_000L;

	private String sealed(String plaintext) throws Exception {
		return toHexString(FieldEncryption.encrypt(key,
				plaintext.getBytes(UTF_8)));
	}

	private boolean delivered = false;

	private static org.zerionproject.core.api.contact.Contact weDialIt() {
		byte[] low = new byte[org.zerionproject.core.api.UniqueId.LENGTH];
		byte[] high = new byte[org.zerionproject.core.api.UniqueId.LENGTH];
		java.util.Arrays.fill(high, (byte) 0x70);
		return org.zerionproject.core.test.TestUtils.getContact(CONTACT,
				new org.zerionproject.core.api.identity.Author(
						new org.zerionproject.core.api.identity.AuthorId(high),
						org.zerionproject.core.api.identity.Author
								.FORMAT_VERSION, "them",
						org.zerionproject.core.test.TestUtils.getAuthor()
								.getPublicKey()),
				new org.zerionproject.core.api.identity.AuthorId(low), true);
	}

	private B4RotationDiagnostics diagnose(Settings s) throws Exception {
		Transaction txn = new Transaction(null, true);
		org.zerionproject.core.api.contact.Contact contact = weDialIt();
		context.checking(new DbExpectations() {{
			allowing(accountManager).getDatabaseKey();
			will(returnValue(key));
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			allowing(settingsManager).getSettings(txn, B4_SETTINGS_NAMESPACE);
			will(returnValue(s));
			allowing(db).getContact(txn, CONTACT);
			will(returnValue(contact));
		}});
		B4OnionRotation r = new B4OnionRotation(db, settingsManager,
				accountManager, new Clock() {
			@Override
			public long currentTimeMillis() {
				return now;
			}

			@Override
			public void sleep(long ms) {
			}
		});
		r.bindDeliveryCheck((t, c, next) -> delivered);
		try {
			return r.diagnostics(CONTACT);
		} finally {
			r.shutdown();
		}
	}

	@Test
	public void aContactWithEvidenceOfTheNewOnionIsReportedAsMoved()
			throws Exception {
		Settings s = new Settings();
		s.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		s.put(B4_ALICE_ONION3_NEXT_KEY, sealed("own-next"));
		s.put(B4_ALICE_NEXT_ANNOUNCED_KEY, sealed("1"));
		delivered = true;
		assertEquals(PeerRotationState.MIGRATED, diagnose(s).getPeerState());
	}

	@Test
	public void aContactWithoutEvidenceIsNotReportedAsMoved() throws Exception {
		Settings s = new Settings();
		s.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		s.put(B4_ALICE_ONION3_NEXT_KEY, sealed("own-next"));
		s.put(B4_ALICE_NEXT_ANNOUNCED_KEY, sealed("1"));
		delivered = false;
		assertEquals(PeerRotationState.CURRENT, diagnose(s).getPeerState());
	}

	@Test
	public void nothingIsReportedWhenNoRotationIsRunning() throws Exception {
		B4RotationDiagnostics d = diagnose(new Settings());
		assertEquals(RotationPhase.IDLE, d.getPhase());
		assertFalse(d.isAnnounced());
		assertEquals(PeerRotationState.CURRENT, d.getPeerState());
		assertFalse(d.isContactAddressPending());
		assertEquals(0L, d.getContactRetryAtMs());
	}

	@Test
	public void ownAndContactRotationAreReported() throws Exception {
		Settings s = new Settings();
		s.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		s.put(B4_ALICE_ONION3_NEXT_KEY, sealed("own-next"));
		s.put(B4_ALICE_NEXT_ANNOUNCED_KEY, sealed("1"));
		s.put(B4_PEER_ROTATION_STATE_KEY_PREFIX + CONTACT.getInt(),
				sealed("MIGRATED"));
		s.put(B4_CONTACT_ONION3_PENDING_KEY_PREFIX + CONTACT.getInt(),
				sealed(PENDING));
		s.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX + CONTACT.getInt(),
				sealed("3"));
		s.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + CONTACT.getInt(),
				sealed(String.valueOf(now + 120_000)));
		B4RotationDiagnostics d = diagnose(s);
		assertEquals(RotationPhase.ANNOUNCING, d.getPhase());
		assertTrue(d.isAnnounced());
		assertEquals(PeerRotationState.MIGRATED, d.getPeerState());
		assertTrue(d.isContactAddressPending());
		assertEquals(now + 120_000, d.getContactRetryAtMs());
	}

	@Test
	public void aPendingAddressBelowTheThresholdIsNotSuppressed()
			throws Exception {
		Settings s = new Settings();
		s.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		s.put(B4_CONTACT_ONION3_PENDING_KEY_PREFIX + CONTACT.getInt(),
				sealed(PENDING));
		s.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX + CONTACT.getInt(),
				sealed("1"));
		s.put(B4_CONTACT_PENDING_RETRY_AT_KEY_PREFIX + CONTACT.getInt(),
				sealed(String.valueOf(now + 120_000)));
		B4RotationDiagnostics d = diagnose(s);
		assertFalse(d.isAnnounced());
		assertEquals(PeerRotationState.CURRENT, d.getPeerState());
		assertTrue(d.isContactAddressPending());
		assertEquals(0L, d.getContactRetryAtMs());
	}
}
