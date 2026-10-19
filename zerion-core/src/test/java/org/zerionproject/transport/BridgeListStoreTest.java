package org.zerionproject.transport;

import org.junit.Test;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.tor.BridgeList;
import org.zerionproject.tor.BridgeListFixtures;
import org.zerionproject.tor.BridgeListFixtures.Signer;
import org.zerionproject.tor.BridgeListKeys;
import org.zerionproject.tor.CircumventionProvider.BridgeType;
import org.zerionproject.transport.BridgeListStore.Verdict;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.zerionproject.tor.BridgeListFixtures.DAY_MS;
import static org.zerionproject.tor.BridgeListFixtures.HOUR_MS;
import static org.zerionproject.tor.BridgeListFixtures.bytes;
import static org.zerionproject.tor.BridgeListFixtures.date;
import static org.zerionproject.tor.BridgeListFixtures.groups;
import static org.zerionproject.tor.BridgeListFixtures.json;
import static org.zerionproject.tor.BridgeListFixtures.obfs4;
import static org.zerionproject.tor.BridgeListFixtures.vanilla;

public class BridgeListStoreTest {

	private static final long TODAY = LocalDate.of(2026, 10, 18).toEpochDay();

	private final Signer ours = new Signer();
	private String account = "account";
	private final Signer localOnly = new Signer();
	private final Signer theirs = new Signer();
	private final NamespacedSettings settings = new NamespacedSettings();
	private final NamespacedSettings.TestClock clock =
			new NamespacedSettings.TestClock(TODAY * DAY_MS + 12 * HOUR_MS);

	private BridgeListStore store() {
		return new BridgeListStore(settings, clock, new BridgeListKeys(
				Collections.singletonList(ours.publicKey()),
				Collections.singletonList(localOnly.publicKey())),
				() -> account);
	}

	private static byte[] list(long seq) {
		return list(seq, TODAY, TODAY + 90);
	}

	private static byte[] list(long seq, long issued, long expires) {
		return bytes(json(seq, date(issued), date(expires), groups(
				BridgeList.OBFS4_DEFAULT,
				Arrays.asList(obfs4(10 + (int) seq), obfs4(11 + (int) seq)),
				BridgeList.VANILLA, Arrays.asList(vanilla((int) seq)))));
	}

	private Verdict offer(BridgeListStore store, byte[] file) {
		return store.offer(file, ours.sign(file), false);
	}

	private long currentSeq(BridgeListStore store) {
		BridgeList l = store.current();
		return l == null ? -1 : l.getSeq();
	}

	@Test
	public void anAcceptedListIsTheCurrentList() {
		BridgeListStore store = store();
		assertNull(store.current());
		assertEquals(Verdict.ACCEPTED, offer(store, list(2)));
		BridgeList current = store.current();
		assertNotNull(current);
		assertEquals(2, current.getSeq());
		assertEquals(Arrays.asList(obfs4(12), obfs4(13)),
				current.lines(BridgeType.DEFAULT_OBFS4));
		assertEquals(2, store.highestSeq());
	}

	@Test
	public void theBundledListIsTheFloorForEveryInstall() {
		BridgeListStore store = store();
		assertEquals(Verdict.NOT_NEWER,
				offer(store, list(BridgeListStore.BUNDLED_SEQ)));
		assertNull(store.current());
		assertEquals(0, store.highestSeq());
	}

	@Test
	public void anOlderOrEqualSequenceIsRefusedAlsoAfterARestart() {
		BridgeListStore store = store();
		assertEquals(Verdict.ACCEPTED, offer(store, list(5)));
		assertEquals(Verdict.NOT_NEWER, offer(store, list(5)));
		assertEquals(Verdict.NOT_NEWER, offer(store, list(4)));
		assertEquals(Verdict.NOT_NEWER, offer(store, list(2)));
		assertEquals(5, currentSeq(store));

		BridgeListStore restarted = store();
		assertEquals(5, currentSeq(restarted));
		assertEquals(Verdict.NOT_NEWER, offer(restarted, list(3)));
		assertEquals(Verdict.NOT_NEWER, offer(restarted, list(5)));
		assertEquals(5, currentSeq(restarted));
		assertEquals(Verdict.ACCEPTED, offer(restarted, list(6)));
		assertEquals(6, currentSeq(store()));
	}

	@Test
	public void theRollbackFloorOutlivesAnExpiredList() {
		BridgeListStore store = store();
		assertEquals(Verdict.ACCEPTED,
				offer(store, list(5, TODAY, TODAY + 3)));
		clock.now += 4 * DAY_MS;
		assertNull("an expired list is not used", store.current());
		long today = TODAY + 4;
		assertEquals(Verdict.NOT_NEWER,
				offer(store, list(4, today, today + 90)));
		assertNull(store.current());
		assertEquals(Verdict.ACCEPTED,
				offer(store, list(6, today, today + 90)));
		assertEquals(6, currentSeq(store));
	}

	@Test
	public void expiryAndIssueDatesAreChecked() {
		BridgeListStore store = store();
		assertEquals(Verdict.EXPIRED,
				offer(store, list(9, TODAY - 30, TODAY - 1)));
		assertEquals(Verdict.NOT_YET_VALID,
				offer(store, list(9, TODAY + 2, TODAY + 60)));
		assertNull(store.current());
		assertEquals(0, store.highestSeq());
		assertEquals(Verdict.ACCEPTED,
				offer(store, list(9, TODAY + 1, TODAY + 60)));
		assertEquals(Verdict.ACCEPTED,
				offer(store, list(10, TODAY - 30, TODAY)));
		assertEquals("valid to the end of its last day", 10,
				currentSeq(store));
		clock.now = (TODAY + 1) * DAY_MS;
		assertNull(store.current());
	}

	@Test
	public void everyRejectionKeepsTheAcceptedList() {
		BridgeListStore store = store();
		assertEquals(Verdict.ACCEPTED, offer(store, list(5)));
		byte[] newer = list(8);
		byte[] changed = newer.clone();
		changed[changed.length - 10] ^= 1;
		assertEquals(Verdict.BAD_SIGNATURE,
				store.offer(changed, ours.sign(newer), false));
		assertEquals(Verdict.BAD_SIGNATURE,
				store.offer(newer, theirs.sign(newer), false));
		assertEquals(Verdict.BAD_SIGNATURE,
				store.offer(newer, bytes("not a signature"), false));
		byte[] malformed = bytes(new String(newer,
				java.nio.charset.StandardCharsets.US_ASCII)
				.replace("iat-mode=0", "iat-mode=0 url=https://x.example/"));
		assertEquals(Verdict.MALFORMED, offer(store, malformed));
		assertEquals(Verdict.EXPIRED,
				offer(store, list(8, TODAY - 40, TODAY - 2)));
		assertEquals(Verdict.NOT_YET_VALID,
				offer(store, list(8, TODAY + 5, TODAY + 40)));
		assertEquals(Verdict.NOT_NEWER, offer(store, list(4)));
		byte[] huge = new byte[BridgeList.MAX_FILE_BYTES + 1];
		Arrays.fill(huge, (byte) ' ');
		assertEquals(Verdict.MALFORMED, offer(store, huge));
		assertEquals(5, currentSeq(store));
		assertEquals(5, store.highestSeq());
	}

	@Test
	public void aDevelopmentKeyOnlyCountsForALocalFile() {
		BridgeListStore store = store();
		byte[] file = list(3);
		byte[] sig = localOnly.sign(file);
		assertEquals(Verdict.BAD_SIGNATURE, store.offer(file, sig, false));
		assertNull(store.current());
		assertEquals(Verdict.ACCEPTED, store.offer(file, sig, true));
		assertEquals(3, currentSeq(store));

		BridgeListStore pinnedOnly = new BridgeListStore(settings, clock,
				new BridgeListKeys(Collections.singletonList(ours.publicKey()),
						Collections.<byte[]>emptyList()), () -> "account");
		assertNull("without the development key the stored copy is unused",
				pinnedOnly.current());
		byte[] other = list(4);
		assertEquals(Verdict.BAD_SIGNATURE,
				pinnedOnly.offer(other, localOnly.sign(other), true));
	}

	@Test
	public void aKeyRotationCarriesTheSequenceAndARemovedKeyLosesIt() {
		Signer next = new Signer();
		BridgeListStore before = store();
		byte[] old = list(900);
		assertEquals(Verdict.ACCEPTED, before.offer(old, ours.sign(old),
				false));

		BridgeListStore both = new BridgeListStore(settings, clock,
				new BridgeListKeys(Arrays.asList(ours.publicKey(),
						next.publicKey()), Collections.<byte[]>emptyList()), () -> "account");
		assertEquals(900, currentSeq(both));
		byte[] low = list(5);
		assertEquals("while both keys are pinned the numbering goes on",
				Verdict.NOT_NEWER, both.offer(low, next.sign(low), false));
		byte[] higher = list(901);
		assertEquals(Verdict.ACCEPTED,
				both.offer(higher, next.sign(higher), false));

		byte[] attack = list(950);
		assertEquals(Verdict.ACCEPTED,
				both.offer(attack, ours.sign(attack), false));
		BridgeListStore after = new BridgeListStore(settings, clock,
				new BridgeListKeys(Collections.singletonList(next.publicKey()),
						Collections.<byte[]>emptyList()), () -> "account");
		assertNull("a list signed with a removed key is not used",
				after.current());
		assertEquals("nor does its sequence block the new key", 901,
				after.highestSeq());
		byte[] fresh = list(906);
		assertEquals(Verdict.ACCEPTED,
				after.offer(fresh, next.sign(fresh), false));
		assertEquals(906, currentSeq(after));
		byte[] replay = list(990);
		assertEquals(Verdict.BAD_SIGNATURE,
				after.offer(replay, ours.sign(replay), false));
	}

	@Test
	public void theFloorIsTheHighestSequenceOfEveryKeyStillPinned() {
		Signer next = new Signer();
		BridgeListStore both = new BridgeListStore(settings, clock,
				new BridgeListKeys(Arrays.asList(ours.publicKey(),
						next.publicKey()), Collections.<byte[]>emptyList()), () -> "account");
		byte[] a = list(900);
		assertEquals(Verdict.ACCEPTED, both.offer(a, ours.sign(a), false));
		byte[] b = list(901);
		assertEquals(Verdict.ACCEPTED, both.offer(b, next.sign(b), false));
		byte[] c = list(950);
		assertEquals(Verdict.ACCEPTED, both.offer(c, ours.sign(c), false));

		BridgeListStore after = new BridgeListStore(settings, clock,
				new BridgeListKeys(Collections.singletonList(next.publicKey()),
						Collections.<byte[]>emptyList()), () -> "account");
		assertEquals("the new key keeps its own floor", 901,
				after.highestSeq());
		byte[] old = list(901);
		assertEquals(Verdict.NOT_NEWER,
				after.offer(old, next.sign(old), false));
		byte[] low = list(5);
		assertEquals(Verdict.NOT_NEWER,
				after.offer(low, next.sign(low), false));
		byte[] fresh = list(902);
		assertEquals(Verdict.ACCEPTED,
				after.offer(fresh, next.sign(fresh), false));
		assertEquals(902, currentSeq(after));
		assertEquals("the floor over both keys", 950, both.highestSeq());
	}

	@Test
	public void aDamagedStoredCopyIsNotUsed() {
		BridgeListStore store = store();
		assertEquals(Verdict.ACCEPTED, offer(store, list(5)));
		byte[] file = Base64.getDecoder().decode(settings
				.namespace(BridgeListStore.NAMESPACE)
				.get(BridgeListStore.KEY_LIST));
		file[file.length / 2] ^= 1;
		settings.namespace(BridgeListStore.NAMESPACE).put(
				BridgeListStore.KEY_LIST,
				Base64.getEncoder().encodeToString(file));
		assertNull(store.current());
		assertNull(store().current());
		settings.namespace(BridgeListStore.NAMESPACE).put(
				BridgeListStore.KEY_LIST, "%%%");
		assertNull(store().current());
	}

	@Test
	public void withoutTheDatabaseNothingIsAcceptedOrUsed() {
		BridgeListStore store = store();
		assertEquals(Verdict.ACCEPTED, offer(store, list(5)));
		settings.failing = true;
		assertNull(store.current());
		assertEquals(Verdict.UNAVAILABLE, offer(store, list(6)));
		assertNull(store.fetchState());
		settings.failing = false;
		assertEquals(5, currentSeq(store));
	}

	@Test
	public void theListAndItsStateLiveOutsideTheTorSettings() {
		BridgeListStore store = store();
		assertEquals(Verdict.ACCEPTED, offer(store, list(5)));
		store.saveFetchState(1234, 77);
		assertEquals(new HashSet<>(Collections.singletonList(
				BridgeListStore.NAMESPACE)), new HashSet<>(settings.written));
		assertNull(settings.byNamespace.get(TorConstants.ID.getString()));
		BridgeListStore.FetchState s = store().fetchState();
		assertNotNull(s);
		assertEquals(1234, s.nextFetchMs);
		assertEquals(77, s.fetchedVersion);
	}

	@Test
	public void aWipedAccountStartsFromTheBundledList() {
		BridgeListStore store = store();
		assertEquals(Verdict.ACCEPTED, offer(store, list(5)));
		settings.byNamespace.clear();
		assertNull(store.current());
		assertEquals(0, store.highestSeq());
		BridgeListStore.FetchState s = store.fetchState();
		assertNotNull(s);
		assertEquals(0, s.nextFetchMs);
		assertEquals(0, s.fetchedVersion);
	}

	@Test
	public void bridgeUseCountsWhileOnAndForThirtyDaysAfter() {
		BridgeListStore store = store();
		assertFalse(store.builtInBridgesRecent());
		store.noteBuiltInBridges(false);
		assertFalse(store.builtInBridgesRecent());
		store.noteBuiltInBridges(true);
		assertTrue(store.builtInBridgesRecent());
		int writes = settings.written.size();
		store.noteBuiltInBridges(true);
		assertEquals("one write a day", writes, settings.written.size());
		store.noteBuiltInBridges(false);
		assertTrue("used today", store.builtInBridgesRecent());
		assertTrue("remembered across a restart",
				store().builtInBridgesRecent());
		clock.now += BridgeListStore.RECENT_BRIDGE_DAYS * DAY_MS;
		assertTrue(store().builtInBridgesRecent());
		clock.now += DAY_MS;
		assertFalse(store().builtInBridgesRecent());
		BridgeListStore on = store();
		on.noteBuiltInBridges(true);
		assertTrue(on.builtInBridgesRecent());

		NamespacedSettings.TestClock behind =
				new NamespacedSettings.TestClock(clock.now - 10 * DAY_MS);
		BridgeListStore wrongClock = new BridgeListStore(settings, behind,
				BridgeListKeys.pinnedOnly(), () -> "account");
		assertFalse("a use dated in the future does not count",
				wrongClock.builtInBridgesRecent());
	}

	@Test
	public void theDayOfBridgeUseIsDeletedOnceItIsTooOld() {
		BridgeListStore store = store();
		store.noteBuiltInBridges(true);
		store.noteBuiltInBridges(false);
		assertTrue(settings.namespace(BridgeListStore.NAMESPACE)
				.containsKey(BridgeListStore.KEY_BUILT_IN_DAY));
		clock.now += (BridgeListStore.RECENT_BRIDGE_DAYS + 1) * DAY_MS;
		assertFalse(store().builtInBridgesRecent());
		assertFalse("not kept beyond its use",
				settings.namespace(BridgeListStore.NAMESPACE)
						.containsKey(BridgeListStore.KEY_BUILT_IN_DAY));
	}

	@Test
	public void aRestoredCopyStopsCountingAsBridgeUse() {
		store().noteBuiltInBridges(true);
		assertTrue(store().builtInBridgesRecent());
		account = "imported-on-a-phone-that-connects-directly";
		assertFalse("a copy of another profile's use does not count",
				store().builtInBridgesRecent());
		assertFalse(settings.namespace(BridgeListStore.NAMESPACE)
				.containsKey(BridgeListStore.KEY_BUILT_IN_DAY));
		assertFalse(settings.namespace(BridgeListStore.NAMESPACE)
				.containsKey(BridgeListStore.KEY_BUILT_IN_OWNER));
		BridgeListStore own = store();
		own.noteBuiltInBridges(true);
		own.noteBuiltInBridges(false);
		assertTrue("its own use counts again", store().builtInBridgesRecent());
	}

	@Test
	public void theUtcDayIsCountedFromMidnight() {
		assertEquals(0, BridgeListStore.utcDay(0));
		assertEquals(0, BridgeListStore.utcDay(DAY_MS - 1));
		assertEquals(1, BridgeListStore.utcDay(DAY_MS));
		assertEquals(-1, BridgeListStore.utcDay(-1));
		assertEquals(TODAY, BridgeListStore.utcDay(
				TODAY * DAY_MS + 23 * HOUR_MS));
		assertEquals(BridgeListFixtures.day(clock.now),
				BridgeListStore.utcDay(clock.now));
	}
}
