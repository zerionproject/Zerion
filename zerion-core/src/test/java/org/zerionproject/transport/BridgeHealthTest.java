package org.zerionproject.transport;

import org.junit.Test;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.tor.BridgeLines;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.tor.BridgeListFixtures.DAY_MS;
import static org.zerionproject.tor.BridgeListFixtures.HOUR_MS;
import static org.zerionproject.tor.BridgeListFixtures.obfs4;
import static org.zerionproject.tor.BridgeListFixtures.vanilla;

public class BridgeHealthTest {

	private final NamespacedSettings settings = new NamespacedSettings();
	private final NamespacedSettings.TestClock clock =
			new NamespacedSettings.TestClock(20_000 * DAY_MS + 10 * HOUR_MS
					+ 30 * 60 * 1000);
	private String account = "account-a";
	private final BridgeHealth health =
			new BridgeHealth(settings, clock, () -> account);

	private static List<String> lines(int n) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(i % 3 == 2 ? "Bridge " + vanilla(i) : "Bridge " + obfs4(i));
		}
		return out;
	}

	private static String fp(String line) {
		return BridgeLines.fingerprint(line);
	}

	private static List<String> without(List<String> all, String... gone) {
		List<String> out = new ArrayList<>(all);
		out.removeAll(Arrays.asList(gone));
		return out;
	}

	@Test
	public void withoutAnyRecordTheListIsUnchanged() {
		List<String> all = lines(6);
		assertEquals(all, health.select(all));
		assertTrue(settings.written.isEmpty());
	}

	@Test
	public void aFailingBridgeIsLeftOutUntilItsBackoffEndsAndThenTriedLast() {
		List<String> all = lines(6);
		String bad = all.get(1);
		health.record(fp(bad), false);
		assertEquals(without(all, bad), health.select(all));
		clock.now += HOUR_MS;
		assertEquals("still resting within the first hour",
				without(all, bad), health.select(all));
		clock.now += HOUR_MS;
		List<String> retried = without(all, bad);
		retried.add(bad);
		assertEquals("tried again, after the healthy ones", retried,
				health.select(all));
	}

	@Test
	public void theBackoffDoublesUpToADayAndASuccessClearsIt() {
		assertEquals(1, BridgeHealth.backoffHours(1));
		assertEquals(2, BridgeHealth.backoffHours(2));
		assertEquals(4, BridgeHealth.backoffHours(3));
		assertEquals(8, BridgeHealth.backoffHours(4));
		assertEquals(16, BridgeHealth.backoffHours(5));
		assertEquals(24, BridgeHealth.backoffHours(6));
		assertEquals(24, BridgeHealth.backoffHours(60));

		List<String> all = lines(6);
		String bad = all.get(0);
		long[] rest = new long[8];
		for (int round = 0; round < rest.length; round++) {
			health.record(fp(bad), false);
			long start = clock.now;
			while (!health.select(all).contains(bad)) clock.now += HOUR_MS;
			rest[round] = (clock.now - start) / HOUR_MS;
		}
		assertEquals(Arrays.toString(rest), 2, rest[0]);
		assertEquals(Arrays.toString(rest), 3, rest[1]);
		assertEquals(Arrays.toString(rest), 5, rest[2]);
		assertEquals(Arrays.toString(rest), 9, rest[3]);
		assertEquals(Arrays.toString(rest), 17, rest[4]);
		assertEquals(Arrays.toString(rest), 25, rest[5]);
		assertEquals("never more than about a day", 25, rest[7]);

		health.record(fp(bad), true);
		assertEquals(all, health.select(all));
		health.record(fp(bad), false);
		assertEquals("a success starts the backoff again from an hour",
				without(all, bad), health.select(all));
		clock.now += 2 * HOUR_MS;
		assertTrue(health.select(all).contains(bad));
	}

	@Test
	public void repeatedFailuresWhileRestingCountOnce() {
		List<String> all = lines(6);
		String bad = all.get(2);
		for (int i = 0; i < 50; i++) health.record(fp(bad), false);
		clock.now += 2 * HOUR_MS;
		assertTrue("one failure, one hour of rest",
				health.select(all).contains(bad));
	}

	@Test
	public void neverFewerThanTheSafeMinimumAreHandedToTor() {
		List<String> all = lines(5);
		health.record(fp(all.get(3)), false);
		clock.now += HOUR_MS;
		for (int i = 0; i < 3; i++) health.record(fp(all.get(i)), false);
		List<String> chosen = health.select(all);
		assertEquals(BridgeHealth.SAFE_MINIMUM, chosen.size());
		assertEquals(all.get(4), chosen.get(0));
		assertEquals("the ones that rest the shortest come back first",
				Arrays.asList(all.get(4), all.get(3), all.get(0)), chosen);
		assertTrue(new HashSet<>(all).containsAll(chosen));

		List<String> two = lines(2);
		health.record(fp(two.get(0)), false);
		assertEquals(two.size(), health.select(two).size());
	}

	@Test
	public void whenEveryBridgeFailsAllOfThemAreUsed() {
		List<String> all = lines(9);
		for (String line : all) health.record(fp(line), false);
		assertEquals(all, health.select(all));
	}

	@Test
	public void linesWithoutAFingerprintAreAlwaysKept() {
		List<String> all = new ArrayList<>(lines(4));
		all.add("Bridge snowflake 192.0.2.3:80 "
				+ "2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=x");
		health.record(fp(all.get(0)), false);
		List<String> chosen = health.select(all);
		assertTrue(chosen.contains(all.get(4)));
		assertFalse(chosen.contains(all.get(0)));
	}

	@Test
	public void theStateIsCoarseAndLivesOnlyInItsOwnDatabaseNamespace() {
		List<String> all = lines(4);
		health.record(fp(all.get(0)), false);
		health.record(fp(all.get(1)), true);
		assertEquals(new HashSet<>(Collections.singletonList(
				BridgeHealth.NAMESPACE)), new HashSet<>(settings.written));
		assertTrue(settings.byNamespace.get(
				TorConstants.ID.getString()) == null);
		String stored = settings.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_RECORDS);
		for (String line : all) {
			assertFalse(stored.contains(fp(line)));
			assertFalse(stored.contains(line.split(" ")[2].split(":")[0]));
		}
		long hour = clock.now / HOUR_MS;
		long day = clock.now / DAY_MS;
		assertEquals(health.id(fp(all.get(0))) + ":1:" + hour + ":0;"
				+ health.id(fp(all.get(1))) + ":0:0:" + day, stored);
	}

	@Test
	public void aSuccessOnTheSameDayIsWrittenOnce() {
		String line = lines(1).get(0);
		health.record(fp(line), true);
		health.record(fp(line), true);
		clock.now += HOUR_MS;
		health.record(fp(line), true);
		assertEquals(1, settings.written.size());
		clock.now += DAY_MS;
		health.record(fp(line), true);
		assertEquals(2, settings.written.size());
	}

	@Test
	public void aWipedAccountForgetsEveryRecord() {
		List<String> all = lines(6);
		health.record(fp(all.get(0)), false);
		assertFalse(health.select(all).contains(all.get(0)));
		settings.byNamespace.clear();
		assertEquals(all, health.select(all));
		BridgeHealth fresh = new BridgeHealth(new NamespacedSettings(), clock,
				() -> "account-a");
		assertEquals(all, fresh.select(all));
	}

	@Test
	public void withoutTheDatabaseEveryBridgeIsUsed() {
		List<String> all = lines(6);
		health.record(fp(all.get(0)), false);
		settings.failing = true;
		assertEquals(all, health.select(all));
		health.record(fp(all.get(1)), false);
		settings.failing = false;
		assertFalse(health.select(all).contains(all.get(0)));
		assertTrue(health.select(all).contains(all.get(1)));
	}

	@Test
	public void oldAndExcessRecordsAreDropped() {
		List<String> many = lines(BridgeHealth.MAX_RECORDS + 10);
		for (String line : many) health.record(fp(line), true);
		assertEquals(BridgeHealth.MAX_RECORDS, BridgeHealth.decode(settings
				.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_RECORDS)).size());
		clock.now += (BridgeHealth.FORGET_AFTER_DAYS + 1) * DAY_MS;
		String one = lines(1).get(0);
		health.record(fp(one), false);
		assertEquals(1, BridgeHealth.decode(settings
				.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_RECORDS)).size());
	}

	@Test
	public void recordsFromAClockThatRanAheadAreDroppedAndPruned() {
		List<String> all = lines(6);
		long real = clock.now;
		clock.now = real + 10 * DAY_MS;
		health.record(fp(all.get(0)), false);
		health.record(fp(all.get(1)), true);
		clock.now = real;
		assertEquals("a failure dated in the future is ignored", all,
				health.select(all));
		assertFalse(health.allFailing(Collections.singletonList(all.get(0))));
		health.record(fp(all.get(2)), false);
		assertEquals("and pruned at the next write", 1, BridgeHealth.decode(
				settings.namespace(BridgeHealth.NAMESPACE)
						.get(BridgeHealth.KEY_RECORDS)).size());
		clock.now = real + 30 * 60 * 1000;
		health.record(fp(all.get(3)), false);
		clock.now = real;
		assertFalse("up to an hour ahead still counts",
				health.select(all).contains(all.get(3)));
	}

	@Test
	public void idsAreKeyedWithASecretOfTheAccount() {
		String line = lines(1).get(0);
		health.record(fp(line), false);
		NamespacedSettings other = new NamespacedSettings();
		BridgeHealth elsewhere = new BridgeHealth(other, clock,
				() -> "account-b");
		elsewhere.record(fp(line), false);
		String here = health.id(fp(line));
		assertTrue(here.matches("[0-9a-f]{16}"));
		assertFalse("another account names the same bridge differently",
				here.equals(elsewhere.id(fp(line))));
		String secret = settings.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_SECRET);
		assertEquals(32, java.util.Base64.getDecoder().decode(secret).length);
		assertFalse(settings.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_RECORDS).contains(unkeyedId(fp(line))));
	}

	@Test
	public void aRestoredOrTransferredAccountStartsWithoutHealth() {
		List<String> all = lines(6);
		health.record(fp(all.get(0)), false);
		String secret = settings.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_SECRET);
		assertFalse(health.select(all).contains(all.get(0)));

		account = "account-imported";
		assertEquals("the copied record is not used", all,
				health.select(all));
		String records = settings.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_RECORDS);
		assertTrue("and is deleted", records == null || records.isEmpty());
		String cleared = settings.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_SECRET);
		assertTrue("with its secret", cleared == null || cleared.isEmpty());
		int writes = settings.written.size();
		assertEquals(all, health.select(all));
		assertEquals("cleared once", writes, settings.written.size());

		health.record(fp(all.get(1)), false);
		assertFalse(health.select(all).contains(all.get(1)));
		assertTrue(health.select(all).contains(all.get(0)));
		assertFalse("a new secret", secret.equals(settings
				.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_SECRET)));
	}

	private static String unkeyedId(String fingerprint) {
		try {
			java.security.MessageDigest md =
					java.security.MessageDigest.getInstance("SHA-256");
			md.update("zerion-bridge-health-v1\n".getBytes(
					java.nio.charset.StandardCharsets.US_ASCII));
			byte[] d = md.digest(fingerprint.getBytes(
					java.nio.charset.StandardCharsets.US_ASCII));
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8; i++) {
				sb.append(String.format("%02x", d[i]));
			}
			return sb.toString();
		} catch (java.security.GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	@Test
	public void oldRecordsAreDeletedWhenReadNotOnlyWhenWritten() {
		List<String> all = lines(6);
		health.record(fp(all.get(0)), false);
		health.record(fp(all.get(1)), true);
		clock.now += (BridgeHealth.FORGET_AFTER_DAYS + 1) * DAY_MS;
		assertEquals(all, health.select(all));
		String stored = settings.namespace(BridgeHealth.NAMESPACE)
				.get(BridgeHealth.KEY_RECORDS);
		assertTrue("deleted at the first read: " + stored,
				stored == null || stored.isEmpty());
	}

	@Test
	public void aRecordWithNothingLeftIsDeletedWithItsSecret() {
		List<String> all = lines(6);
		health.record(fp(all.get(0)), true);
		assertTrue(settings.namespace(BridgeHealth.NAMESPACE)
				.containsKey(BridgeHealth.KEY_SECRET));
		clock.now += (BridgeHealth.FORGET_AFTER_DAYS + 1) * DAY_MS;
		health.maintain();
		assertFalse(settings.namespace(BridgeHealth.NAMESPACE)
				.containsKey(BridgeHealth.KEY_RECORDS));
		assertFalse(settings.namespace(BridgeHealth.NAMESPACE)
				.containsKey(BridgeHealth.KEY_SECRET));
		assertFalse(settings.namespace(BridgeHealth.NAMESPACE)
				.containsKey(BridgeHealth.KEY_OWNER));
		health.record(fp(all.get(1)), false);
		assertFalse(health.select(all).contains(all.get(1)));
	}

	@Test
	public void damagedRecordsAreIgnored() {
		assertTrue(BridgeHealth.decode("nonsense;;a:b:c:d").isEmpty());
		assertTrue(BridgeHealth.decode("0123456789abcdef:99:1:1").isEmpty());
		assertEquals(1, BridgeHealth.decode(
				"0123456789abcdef:2:1:1;zz").size());
	}
}
