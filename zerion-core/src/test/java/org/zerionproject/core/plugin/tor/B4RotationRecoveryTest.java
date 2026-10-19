package org.zerionproject.core.plugin.tor;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.db.CommitAction;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.EventAction;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.event.B4PeerOnionAnnouncedEvent;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_LAST_ROTATION_TIME_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ONION3_NEXT_PRIVKEY_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_ROTATION_PHASE_KEY;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ANNOUNCE_FALLBACK_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ANNOUNCE_RATE_LIMIT_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PENDING_RETRY_MAX_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PENDING_RETRY_MIN_MS;
import static org.zerionproject.core.api.plugin.B4Constants.B4_SETTINGS_NAMESPACE;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PEER_LAST_CONTACT_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.B4_PEER_ROTATION_STATE_KEY_PREFIX;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_ANNOUNCED_AT_MS;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_NEXT;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.util.StringUtils.UTF_8;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.zerionproject.core.api.plugin.B4Constants.WIRE_KEY_ONION3_PUBLISH_NONCE;
import static org.zerionproject.core.api.plugin.B4Constants.B4_ALICE_NEXT_ANNOUNCED_KEY;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class B4RotationRecoveryTest extends BrambleMockTestCase {

	private static final String ORIGINAL = "original-onion";
	private static final String NEXT = "onion-of-nextkey";
	private static final String NEXT_KEY = "nextkey";
	private static final long HOUR = 60L * 60 * 1000;
	private static final long DAY = 24 * HOUR;

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final AccountManager accountManager =
			context.mock(AccountManager.class);
	private final SecretKey key = getSecretKey();
	private final long now = 1_700_000_000_000L;
	private final Settings stored = new Settings();
	private final Transaction txn = new Transaction(null, false);
	private final List<Contact> contacts = new ArrayList<>();
	private final String peerNext = onion('c');

	private static final class Transport
			implements B4OnionRotation.B4TorAdapter {

		final List<String> live = new ArrayList<>();
		final List<String> ops = new ArrayList<>();
		final List<TransportProperties> merged = new ArrayList<>();
		boolean openInbound = true;
		boolean dropMerges = false;

		@Override
		public HiddenServiceProperties publishHiddenService(
				@Nullable String privKey) {
			String k = privKey == null ? NEXT_KEY : privKey;
			live.add("onion-of-" + k);
			ops.add("publish onion-of-" + k);
			return hiddenService("onion-of-" + k, k);
		}

		@Override
		public boolean isPublished(String onion) {
			return live.contains(onion);
		}

		@Override
		public String getStartupOnion() {
			return ORIGINAL;
		}

		@Override
		public void removeHiddenService(String onion) {
			live.remove(onion);
			ops.add("remove " + onion);
		}

		@Override
		public void updateTorCurrentPrivKey(String newPrivKey) {
		}

		@Override
		public void mergeTorLocalProperties(TransportProperties props) {
			if (!dropMerges) merged.add(props);
		}

		@Override
		public boolean acceptsOpenInbound(ContactId c) {
			return openInbound;
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

	private static String onion(char c) {
		char[] chars = new char[56];
		Arrays.fill(chars, c);
		chars[55] = 'd';
		return new String(chars);
	}

	private static Contact contact(int id, boolean theyDialUs) {
		byte[] low = new byte[AuthorId.LENGTH];
		byte[] high = new byte[AuthorId.LENGTH];
		Arrays.fill(high, (byte) 0x70);
		Author them = new Author(new AuthorId(theyDialUs ? low : high),
				Author.FORMAT_VERSION, "them", getAuthor().getPublicKey());
		return getContact(new ContactId(id), them,
				new AuthorId(theyDialUs ? high : low), true);
	}

	private String sealed(String plaintext) throws Exception {
		return toHexString(FieldEncryption.encrypt(key,
				plaintext.getBytes(UTF_8)));
	}

	private void announcing(long rotatedAt) throws Exception {
		stored.put(B4_ALICE_ROTATION_PHASE_KEY, sealed("ANNOUNCING"));
		stored.put(B4_ALICE_ONION3_NEXT_KEY, sealed(NEXT));
		stored.put(B4_ALICE_ONION3_NEXT_PRIVKEY_KEY, sealed(NEXT_KEY));
		stored.put(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY,
				sealed(String.valueOf(rotatedAt)));
	}

	private void expectDatabase() throws Exception {
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
			will(returnValue(contacts));
		}});
	}

	private B4OnionRotation rotation(long clockNow, Transport transport,
			boolean delivered) {
		Clock clock = new Clock() {
			@Override
			public long currentTimeMillis() {
				return clockNow;
			}

			@Override
			public void sleep(long ms) {
			}
		};
		B4OnionRotation r = new B4OnionRotation(db, settingsManager,
				accountManager, clock);
		r.bindAdapter(transport);
		r.bindDeliveryCheck((t, c, next) -> delivered && NEXT.equals(next));
		return r;
	}

	private Transport started() {
		Transport t = new Transport();
		t.live.add(ORIGINAL);
		t.live.add(NEXT);
		return t;
	}

	private String phase() throws Exception {
		return new String(FieldEncryption.decrypt(key,
				org.zerionproject.core.util.StringUtils.fromHexString(
						stored.get(B4_ALICE_ROTATION_PHASE_KEY))), UTF_8);
	}

	@Test
	public void aSessionThatArrivedOnTheOldOnionDoesNotRetireIt()
			throws Exception {
		Contact c = contact(1, true);
		contacts.add(c);
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = started();
		rotation(now, transport, true).onPeerSyncSessionEstablished(c.getId(),
				ORIGINAL);
		assertTrue(transport.live.contains(ORIGINAL));
		assertEquals("ANNOUNCING", phase());
	}

	@Test
	public void anOutgoingSessionDoesNotProveThePeerReachesTheNewOnion()
			throws Exception {
		Contact c = contact(1, true);
		contacts.add(c);
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = started();
		rotation(now, transport, true).onPeerSyncSessionEstablished(c.getId(),
				null);
		assertTrue(transport.live.contains(ORIGINAL));
		assertEquals("ANNOUNCING", phase());
	}

	@Test
	public void aSessionOnTheNewOnionRetiresTheOldOneOnceTheGraceHasPassed()
			throws Exception {
		Contact c = contact(1, true);
		contacts.add(c);
		announcing(now - HOUR);
		expectDatabase();
		Transport transport = started();
		rotation(now, transport, false).onPeerSyncSessionEstablished(
				c.getId(), NEXT);
		assertTrue("retired before the grace ended",
				transport.live.contains(ORIGINAL));
		rotation(now - HOUR + B4OnionRotation.MIGRATION_GRACE_MS, transport,
				false).evaluateForceExpire();
		assertFalse(transport.live.contains(ORIGINAL));
		assertTrue(transport.live.contains(NEXT));
		assertEquals("IDLE", phase());
	}

	@Test
	public void aContactWeDialCountsOnceItHasReceivedTheNewOnion()
			throws Exception {
		contacts.add(contact(1, false));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = started();
		rotation(now, transport, false).evaluateForceExpire();
		assertTrue(transport.live.contains(ORIGINAL));
		rotation(now, transport, true).evaluateForceExpire();
		assertFalse(transport.live.contains(ORIGINAL));
	}

	@Test
	public void aContactThatDialsOurOpenOnionMustReachTheNewOne()
			throws Exception {
		contacts.add(contact(1, true));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = started();
		rotation(now, transport, true).evaluateForceExpire();
		assertTrue(transport.live.contains(ORIGINAL));
	}

	@Test
	public void aContactLimitedToTheAuthorizedServiceCountsOnceItHasTheNewOnion()
			throws Exception {
		contacts.add(contact(1, true));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = started();
		transport.openInbound = false;
		rotation(now, transport, false).evaluateForceExpire();
		assertTrue(transport.live.contains(ORIGINAL));
		rotation(now, transport, true).evaluateForceExpire();
		assertFalse(transport.live.contains(ORIGINAL));
	}

	@Test
	public void oneContactWithoutEvidenceKeepsTheOldOnion() throws Exception {
		Contact moved = contact(1, true);
		Contact stuck = contact(2, true);
		contacts.add(moved);
		contacts.add(stuck);
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, true);
		r.onPeerSyncSessionEstablished(moved.getId(), NEXT);
		assertTrue(transport.live.contains(ORIGINAL));
		r.onPeerSyncSessionEstablished(stuck.getId(), NEXT);
		assertFalse(transport.live.contains(ORIGINAL));
	}

	@Test
	public void aNewOnionIsAnnouncedOnceTorHasUploadedItsDescriptor()
			throws Exception {
		contacts.add(contact(1, true));
		expectDatabase();
		Transport transport = new Transport();
		transport.live.add(ORIGINAL);
		B4OnionRotation r = rotation(now, transport, false);
		r.forceRotate();
		assertEquals("ANNOUNCING", phase());
		assertTrue("announced before the descriptor was uploaded",
				transport.merged.isEmpty());
		r.onDescriptorUploaded(ORIGINAL);
		assertTrue(transport.merged.isEmpty());
		r.onDescriptorUploaded(NEXT);
		assertEquals(1, transport.merged.size());
		assertEquals(NEXT, transport.merged.get(0).get(WIRE_KEY_ONION3_NEXT));
		r.onDescriptorUploaded(NEXT);
		assertEquals(1, transport.merged.size());
	}

	@Test
	public void withoutAnUploadTheNewOnionIsAnnouncedAfterTheFallbackDelay()
			throws Exception {
		contacts.add(contact(1, true));
		announcing(now);
		expectDatabase();
		Transport transport = started();
		rotation(now + B4_ANNOUNCE_FALLBACK_MS - 1, transport, false)
				.announceNext(null);
		assertTrue(transport.merged.isEmpty());
		rotation(now + B4_ANNOUNCE_FALLBACK_MS, transport, false)
				.announceNext(null);
		assertEquals(1, transport.merged.size());
		assertEquals(NEXT, transport.merged.get(0).get(WIRE_KEY_ONION3_NEXT));
	}

	@Test
	public void theAnnouncementNamesOnlyTheHourTheNewOnionWasCreated()
			throws Exception {
		long rotatedAt = now + 37 * 60_000L + 12_345L;
		contacts.add(contact(1, true));
		announcing(rotatedAt);
		expectDatabase();
		Transport transport = started();
		rotation(rotatedAt + B4_ANNOUNCE_FALLBACK_MS, transport, false)
				.announceNext(null);
		assertEquals(1, transport.merged.size());
		long onWire = Long.parseLong(transport.merged.get(0)
				.get(WIRE_KEY_ONION3_ANNOUNCED_AT_MS));
		String kept = new String(FieldEncryption.decrypt(key,
				org.zerionproject.core.util.StringUtils.fromHexString(
						stored.get(B4_ALICE_ONION3_ANNOUNCED_AT_MS_KEY))),
				UTF_8);
		assertEquals("a whole hour, no later than the creation, kept exactly",
				(onWire % HOUR == 0 ? "a whole hour" : "not a whole hour")
						+ ", " + (onWire <= rotatedAt
						&& rotatedAt - onWire < HOUR
						? "no later than the creation" : "elsewhere")
						+ ", " + (kept.equals(String.valueOf(rotatedAt))
						? "kept exactly" : "kept as " + kept));
	}

	private void announcedByPeer(B4OnionRotation r, ContactId cid)
			throws Exception {
		r.onAnnounceReceived(txn, cid, peerNext, now);
	}

	private void failThreeTimes(B4OnionRotation r, ContactId cid)
			throws Exception {
		for (int i = 0; i < 3; i++) r.onPendingDialFailed(cid);
	}

	@Test
	public void aSuccessAfterRepeatedFailuresMakesThePendingOnionUsableAgain()
			throws Exception {
		ContactId cid = new ContactId(1);
		expectDatabase();
		B4OnionRotation r = rotation(now, started(), false);
		announcedByPeer(r, cid);
		r.onSuccessfulConnect(cid, peerNext);
		failThreeTimes(r, cid);
		assertNull(r.getPendingOnionForContact(txn, cid));
		r.onSuccessfulConnect(cid, peerNext);
		assertEquals(peerNext, r.getPendingOnionForContact(txn, cid));
	}

	@Test
	public void aSuppressedPendingOnionIsRetriedAfterABoundedDelay()
			throws Exception {
		ContactId cid = new ContactId(1);
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, false);
		announcedByPeer(r, cid);
		failThreeTimes(r, cid);
		assertNull(r.getPendingOnionForContact(txn, cid));
		assertNull(rotation(now + B4_PENDING_RETRY_MIN_MS - 1, transport,
				false).getPendingOnionForContact(txn, cid));
		B4OnionRotation later = rotation(now + B4_PENDING_RETRY_MIN_MS,
				transport, false);
		assertEquals(peerNext, later.getPendingOnionForContact(txn, cid));
		later.onPendingDialFailed(cid);
		assertNull(later.getPendingOnionForContact(txn, cid));
		assertNull(rotation(now + 3 * B4_PENDING_RETRY_MIN_MS - 1, transport,
				false).getPendingOnionForContact(txn, cid));
		assertEquals(peerNext, rotation(now + 3 * B4_PENDING_RETRY_MIN_MS,
				transport, false).getPendingOnionForContact(txn, cid));
	}

	@Test
	public void anAnnouncementOfTheSameOnionAllowsAnImmediateRetry()
			throws Exception {
		ContactId cid = new ContactId(1);
		expectDatabase();
		B4OnionRotation r = rotation(now, started(), false);
		announcedByPeer(r, cid);
		failThreeTimes(r, cid);
		assertNull(r.getPendingOnionForContact(txn, cid));
		announcedByPeer(r, cid);
		assertEquals(peerNext, r.getPendingOnionForContact(txn, cid));
	}

	@Test
	public void aFailedDialToTheCurrentOnionAllowsAnImmediateRetry()
			throws Exception {
		ContactId cid = new ContactId(1);
		expectDatabase();
		B4OnionRotation r = rotation(now, started(), false);
		announcedByPeer(r, cid);
		failThreeTimes(r, cid);
		assertNull(r.getPendingOnionForContact(txn, cid));
		r.onFallbackDialFailed(cid);
		assertEquals(peerNext, r.getPendingOnionForContact(txn, cid));
	}

	@Test
	public void aSuppressionLeftByAnOlderVersionIsRetried() throws Exception {
		ContactId cid = new ContactId(1);
		expectDatabase();
		B4OnionRotation r = rotation(now, started(), false);
		announcedByPeer(r, cid);
		stored.put(B4_CONTACT_PENDING_DIAL_FAILURES_KEY_PREFIX + cid.getInt(),
				sealed("3"));
		assertEquals(peerNext, r.getPendingOnionForContact(txn, cid));
	}

	private int announcements() {
		int n = 0;
		for (CommitAction a : txn.getActions()) {
			if (a instanceof EventAction && ((EventAction) a).getEvent()
					instanceof B4PeerOnionAnnouncedEvent) {
				n++;
			}
		}
		return n;
	}

	@Test
	public void aNewerAnnouncementWithinTheHourReplacesTheEarlierOnion()
			throws Exception {
		ContactId cid = new ContactId(1);
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, false);
		announcedByPeer(r, cid);
		failThreeTimes(r, cid);
		String replacement = onion('e');
		B4OnionRotation later = rotation(now + 60_000L, transport, false);
		later.onAnnounceReceived(txn, cid, replacement, now + 60_000L);
		assertEquals(replacement, later.getPendingOnionForContact(txn, cid));
		assertEquals(replacement, later.getPendingOnionForContact(cid));
		assertEquals(1, announcements());
		String third = onion('f');
		B4OnionRotation afterTheHour = rotation(
				now + B4_ANNOUNCE_RATE_LIMIT_MS, transport, false);
		afterTheHour.onAnnounceReceived(txn, cid, third,
				now + B4_ANNOUNCE_RATE_LIMIT_MS);
		assertEquals(third, afterTheHour.getPendingOnionForContact(txn, cid));
		assertEquals(2, announcements());
	}

	@Test
	public void anAnnouncementThatWasNotStoredIsMadeAgain() throws Exception {
		contacts.add(contact(1, true));
		announcing(now);
		expectDatabase();
		Transport transport = started();
		transport.dropMerges = true;
		B4OnionRotation r = rotation(now, transport, false);
		r.onDescriptorUploaded(NEXT);
		assertTrue(transport.merged.isEmpty());
		transport.dropMerges = false;
		rotation(now + HOUR - 60_000L, transport, false).evaluateAll();
		assertEquals(1, transport.merged.size());
		assertEquals(NEXT, transport.merged.get(0).get(WIRE_KEY_ONION3_NEXT));
		assertEquals(String.valueOf(B4OnionRotation.announcedAtOnWire(now)),
				transport.merged.get(0).get(WIRE_KEY_ONION3_ANNOUNCED_AT_MS));
		assertEquals("ANNOUNCING", phase());
	}

	@Test
	public void aPromotionPublishesTheNewOnionBeforeItRemovesTheOldOne()
			throws Exception {
		Contact c = contact(1, true);
		contacts.add(c);
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = new Transport();
		transport.live.add(ORIGINAL);
		rotation(now, transport, false).onPeerSyncSessionEstablished(
				c.getId(), NEXT);
		assertEquals("the old onion went before the new one was up",
				Arrays.asList("publish " + NEXT, "remove " + ORIGINAL),
				transport.ops);
		assertEquals("IDLE", phase());
	}

	@Test
	public void aWithdrawnAnnouncementDoesNotLetTheNextOneNotifyAgain()
			throws Exception {
		ContactId cid = new ContactId(1);
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, false);
		announcedByPeer(r, cid);
		assertEquals(1, announcements());
		B4OnionRotation later = rotation(now + 60_000L, transport, false);
		later.onPeerNextWithdrawn(txn, cid);
		assertNull(later.getPendingOnionForContact(txn, cid));
		later.onAnnounceReceived(txn, cid, onion('e'), now + 60_000L);
		assertEquals(onion('e'), later.getPendingOnionForContact(txn, cid));
		assertEquals("a contact that withdrew and announced again notified"
				+ " twice within the hour", 1, announcements());
		B4OnionRotation afterTheHour = rotation(
				now + B4_ANNOUNCE_RATE_LIMIT_MS, transport, false);
		afterTheHour.onPeerNextWithdrawn(txn, cid);
		afterTheHour.onAnnounceReceived(txn, cid, onion('f'),
				now + B4_ANNOUNCE_RATE_LIMIT_MS);
		assertEquals(2, announcements());
	}

	@Test
	public void anAnnouncementAContactHasNotFollowedIsSentAgainEachDay()
			throws Exception {
		contacts.add(contact(1, true));
		announcing(now);
		stored.put(B4_ALICE_NEXT_ANNOUNCED_KEY, sealed("1"));
		expectDatabase();
		Transport transport = started();
		rotation(now + 30 * 60_000L, transport, false).evaluateAll();
		int before = nonces(transport).size();
		rotation(now + HOUR + 60_000L, transport, false).evaluateAll();
		List<String> after = nonces(transport);
		assertEquals("the announcement was not sent again after an hour",
				before + 1, after.size());
		rotation(now + 2 * HOUR + 60_000L, transport, false).evaluateAll();
		assertEquals("sent again within the day", after.size(),
				nonces(transport).size());
		rotation(now + HOUR + 60_000L + 24 * HOUR, transport, false)
				.evaluateAll();
		List<String> daily = nonces(transport);
		assertEquals(after.size() + 1, daily.size());
		assertNotEquals(daily.get(daily.size() - 1),
				daily.get(daily.size() - 2));
		for (TransportProperties p : transport.merged) {
			if (p.containsKey(WIRE_KEY_ONION3_PUBLISH_NONCE)) {
				assertEquals(NEXT, p.get(WIRE_KEY_ONION3_NEXT));
			}
		}
	}

	@Test
	public void anAnnouncementEveryContactFollowedIsNotSentAgain()
			throws Exception {
		Contact c = contact(1, true);
		contacts.add(c);
		announcing(now);
		stored.put(B4_ALICE_NEXT_ANNOUNCED_KEY, sealed("1"));
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now + HOUR + 60_000L, transport, false);
		r.onPeerSyncSessionEstablished(c.getId(), NEXT);
		r.evaluateAll();
		assertTrue(nonces(transport).isEmpty());
	}

	private static List<String> nonces(Transport transport) {
		List<String> out = new ArrayList<>();
		for (TransportProperties p : transport.merged) {
			String n = p.get(WIRE_KEY_ONION3_PUBLISH_NONCE);
			if (n != null && !n.isEmpty()) out.add(n);
		}
		return out;
	}

	@Test
	public void theContactsARotationWaitsForAreListedOnceTheDayHasPassed()
			throws Exception {
		Contact c = contact(1, true);
		contacts.add(c);
		announcing(now - HOUR);
		expectDatabase();
		Transport transport = started();
		assertTrue(rotation(now, transport, false)
				.getContactsHoldingRotation().isEmpty());
		B4OnionRotation later = rotation(
				now - HOUR + B4OnionRotation.MIGRATION_GRACE_MS, transport,
				false);
		assertEquals(Collections.singletonList(c.getId()),
				later.getContactsHoldingRotation());
		stored.put(B4_PEER_ROTATION_STATE_KEY_PREFIX + 1, sealed("MIGRATED"));
		assertTrue(later.getContactsHoldingRotation().isEmpty());
	}

	private void lastContactAgo(int id, long ago) throws Exception {
		stored.put(B4_PEER_LAST_CONTACT_KEY_PREFIX + id,
				sealed(String.valueOf(now - ago)));
	}

	@Test
	public void aContactSilentForNinetyDaysNoLongerHoldsTheOldOnionOnTheSideThatIsDialled()
			throws Exception {
		contacts.add(contact(1, true));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		lastContactAgo(1, 90 * DAY + 60_000L);
		expectDatabase();
		Transport transport = started();
		rotation(now, transport, false).evaluateForceExpire();
		assertFalse(transport.live.contains(ORIGINAL));
		assertEquals("IDLE", phase());
	}

	@Test
	public void aContactSilentForEightyNineDaysStillHoldsTheOldOnion()
			throws Exception {
		contacts.add(contact(1, true));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		lastContactAgo(1, 89 * DAY);
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, false);
		r.evaluateForceExpire();
		assertTrue(transport.live.contains(ORIGINAL));
		assertEquals("ANNOUNCING", phase());
		assertEquals(Collections.singletonList(contacts.get(0).getId()),
				r.getContactsHoldingRotation());
	}

	@Test
	public void theDesignatedDialerNeverStopsWaitingForASilentContact()
			throws Exception {
		contacts.add(contact(1, false));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		lastContactAgo(1, 400 * DAY);
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, false);
		r.evaluateForceExpire();
		assertTrue(transport.live.contains(ORIGINAL));
		assertEquals("ANNOUNCING", phase());
		assertEquals(Collections.singletonList(contacts.get(0).getId()),
				r.getContactsHoldingRotation());
	}

	@Test
	public void aContactSilentForNinetyDaysIsNotListedAsHoldingTheRotation()
			throws Exception {
		contacts.add(contact(1, true));
		contacts.add(contact(2, true));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		lastContactAgo(1, 91 * DAY);
		lastContactAgo(2, 10 * DAY);
		expectDatabase();
		Transport transport = started();
		assertEquals(Collections.singletonList(contacts.get(1).getId()),
				rotation(now, transport, false).getContactsHoldingRotation());
	}

	@Test
	public void anyContactWithinNinetyDaysRestartsTheClock() throws Exception {
		contacts.add(contact(1, true));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		lastContactAgo(1, 100 * DAY);
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, false);
		r.recordContact(contacts.get(0).getId(), now - 5 * DAY);
		r.evaluateForceExpire();
		assertTrue(transport.live.contains(ORIGINAL));
		rotation(now + 84 * DAY, transport, false).evaluateForceExpire();
		assertTrue(transport.live.contains(ORIGINAL));
		rotation(now + 86 * DAY, transport, false).evaluateForceExpire();
		assertFalse(transport.live.contains(ORIGINAL));
	}

	@Test
	public void aLastContactWrittenByAClockSetBackIsIgnored()
			throws Exception {
		contacts.add(contact(1, true));
		ContactId c = contacts.get(0).getId();
		lastContactAgo(1, 5 * DAY);
		expectDatabase();
		B4OnionRotation r = rotation(now, started(), false);

		r.recordContact(c, now - 100 * DAY);

		assertEquals("a time before the stored one was written",
				now - 5 * DAY, r.getLastContactMs(c));
		r.recordContact(c, now - DAY);
		assertEquals(now - DAY, r.getLastContactMs(c));
	}

	@Test
	public void aContactAddedSinceTheLastSeedIsNotListedAsHoldingTheRotation()
			throws Exception {
		contacts.add(contact(1, true));
		contacts.add(contact(2, true));
		stored.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
				sealed(String.valueOf(now - 30 * DAY)));
		stored.put(B4OnionRotation.B4_ALICE_NEXT_ROTATION_DAYS_KEY,
				sealed("5"));
		lastContactAgo(1, 40 * DAY);
		expectDatabase();

		assertEquals("a contact without a seeded time is not holding",
				Collections.singletonList(contacts.get(0).getId()),
				rotation(now, started(), false).getContactsHoldingRotation());
	}

	private static boolean absent(@Nullable String stored) {
		return stored == null || stored.isEmpty();
	}

	@Test
	public void noScheduledRotationStartsWhileAContactHasNotBeenInContactSinceThePreviousOne()
			throws Exception {
		contacts.add(contact(1, true));
		stored.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
				sealed(String.valueOf(now - 30 * DAY)));
		stored.put(B4OnionRotation.B4_ALICE_NEXT_ROTATION_DAYS_KEY,
				sealed("5"));
		lastContactAgo(1, 40 * DAY);
		expectDatabase();
		Transport transport = started();
		rotation(now, transport, false).evaluateTrigger();
		assertTrue("a rotation started while a contact had not moved",
				absent(stored.get(B4_ALICE_ONION3_NEXT_KEY)));
		assertTrue(absent(stored.get(B4_ALICE_ROTATION_PHASE_KEY)));
		lastContactAgo(1, DAY);
		rotation(now, transport, false).evaluateTrigger();
		assertEquals("ANNOUNCING", phase());
		assertFalse(absent(stored.get(B4_ALICE_ONION3_NEXT_KEY)));
	}

	@Test
	public void aPromotionLeavesTheNextIntervalToBeDrawnOnceEveryContactHasMoved()
			throws Exception {
		contacts.add(contact(1, true));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		lastContactAgo(1, 91 * DAY);
		stored.put(B4OnionRotation.B4_ALICE_NEXT_ROTATION_DAYS_KEY,
				sealed("5"));
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, false);
		r.evaluateForceExpire();
		assertEquals("IDLE", phase());
		assertTrue("the interval was drawn at the promotion", absent(
				stored.get(B4OnionRotation.B4_ALICE_NEXT_ROTATION_DAYS_KEY)));
		r.evaluateTrigger();
		assertTrue("the interval was drawn before the contact came back",
				absent(stored.get(
						B4OnionRotation.B4_ALICE_NEXT_ROTATION_DAYS_KEY)));
		assertEquals("IDLE", phase());
		lastContactAgo(1, -HOUR);
		rotation(now + DAY, transport, false).evaluateTrigger();
		assertFalse(absent(stored.get(
				B4OnionRotation.B4_ALICE_NEXT_ROTATION_DAYS_KEY)));
		assertEquals("IDLE", phase());
	}

	@Test
	public void theContactsADueRotationWaitsForBeforeItStartsAreListed()
			throws Exception {
		contacts.add(contact(1, true));
		contacts.add(contact(2, true));
		stored.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
				sealed(String.valueOf(now - 30 * DAY)));
		lastContactAgo(1, 40 * DAY);
		lastContactAgo(2, DAY);
		expectDatabase();
		Transport transport = started();
		B4OnionRotation r = rotation(now, transport, false);
		r.evaluateTrigger();
		assertTrue(absent(stored.get(B4_ALICE_ONION3_NEXT_KEY)));
		assertEquals("the contact a due rotation waits for is not listed",
				Collections.singletonList(contacts.get(0).getId()),
				r.getContactsHoldingRotation());
		lastContactAgo(1, HOUR);
		assertTrue(r.getContactsHoldingRotation().isEmpty());
	}

	@Test
	public void noContactIsListedWhileNoRotationIsDue() throws Exception {
		contacts.add(contact(1, true));
		lastContactAgo(1, 40 * DAY);
		stored.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
				sealed(String.valueOf(now - 2 * DAY)));
		expectDatabase();
		B4OnionRotation r = rotation(now, started(), false);
		assertTrue(r.getContactsHoldingRotation().isEmpty());
		stored.put(B4_ALICE_LAST_ROTATION_TIME_MS_KEY,
				sealed(String.valueOf(now - 7 * DAY)));
		stored.put(B4OnionRotation.B4_ALICE_NEXT_ROTATION_DAYS_KEY,
				sealed("10"));
		assertTrue(r.getContactsHoldingRotation().isEmpty());
	}

	@Test
	public void aContactWithNoRecordedContactIsSeededNotTreatedAsSilent()
			throws Exception {
		contacts.add(contact(1, true));
		announcing(now - B4OnionRotation.MIGRATION_GRACE_MS - 1);
		expectDatabase();
		Transport transport = started();
		rotation(now, transport, false).evaluateAll();
		assertTrue(transport.live.contains(ORIGINAL));
		assertTrue(stored.containsKey(B4_PEER_LAST_CONTACT_KEY_PREFIX + 1));
		rotation(now + 91 * DAY, transport, false).evaluateForceExpire();
		assertFalse(transport.live.contains(ORIGINAL));
	}

	@Test
	public void theRetryDelayGrowsAndIsCapped() {
		assertEquals(B4_PENDING_RETRY_MIN_MS,
				B4OnionRotation.pendingRetryDelayMs(3));
		assertEquals(2 * B4_PENDING_RETRY_MIN_MS,
				B4OnionRotation.pendingRetryDelayMs(4));
		assertEquals(4 * B4_PENDING_RETRY_MIN_MS,
				B4OnionRotation.pendingRetryDelayMs(5));
		assertEquals(B4_PENDING_RETRY_MAX_MS,
				B4OnionRotation.pendingRetryDelayMs(7));
		assertEquals(B4_PENDING_RETRY_MAX_MS,
				B4OnionRotation.pendingRetryDelayMs(1000));
	}
}
