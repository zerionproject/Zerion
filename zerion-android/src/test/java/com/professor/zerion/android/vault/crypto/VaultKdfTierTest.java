package com.professor.zerion.android.vault.crypto;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VaultKdfTierTest {

	private static final long GB = 1_000_000_000L;
	private static final VaultKdfTier.NativeCheck WORKS = () -> true;
	private static final VaultKdfTier.NativeCheck BROKEN = () -> false;

	private static String name(Argon2.Argon2Params p) {
		if (p == VaultKdfTier.TIER_A) return "A";
		if (p == VaultKdfTier.TIER_B) return "B";
		if (p == VaultKdfTier.TIER_C) return "C";
		return "?";
	}

	private static String tier(long ram) {
		return name(VaultKdfTier.choose(ram, true, false, WORKS));
	}

	@Test
	public void theTierFollowsTotalMemory() {
		assertEquals("A", tier(16 * GB));
		assertEquals("A", tier(VaultKdfTier.TIER_A_MIN_TOTAL_RAM_BYTES));
		assertEquals("B", tier(VaultKdfTier.TIER_A_MIN_TOTAL_RAM_BYTES - 1));
		assertEquals("B", tier(3_913_000_000L));
		assertEquals("B", tier(VaultKdfTier.TIER_B_MIN_TOTAL_RAM_BYTES));
		assertEquals("C", tier(VaultKdfTier.TIER_B_MIN_TOTAL_RAM_BYTES - 1));
		assertEquals("C", tier(2 * GB));
		assertEquals("C", tier(0));
		assertEquals("C", tier(-1));
	}

	@Test
	public void theThresholdsAreTheDocumentedOnes() {
		assertEquals(6_000_000_000L, VaultKdfTier.TIER_A_MIN_TOTAL_RAM_BYTES);
		assertEquals(3_500_000_000L, VaultKdfTier.TIER_B_MIN_TOTAL_RAM_BYTES);
	}

	@Test
	public void eachConditionOnItsOwnFallsBackToTierC() {
		assertEquals("C", name(VaultKdfTier.choose(16 * GB, false, false,
				WORKS)));
		assertEquals("C", name(VaultKdfTier.choose(16 * GB, true, true,
				WORKS)));
		assertEquals("C", name(VaultKdfTier.choose(16 * GB, true, false,
				BROKEN)));
		assertEquals("C", name(VaultKdfTier.choose(4 * GB, false, false,
				WORKS)));
		assertEquals("C", name(VaultKdfTier.choose(4 * GB, true, true,
				WORKS)));
		assertEquals("C", name(VaultKdfTier.choose(4 * GB, true, false,
				BROKEN)));
	}

	@Test
	public void theNativeSelfTestRunsOnlyWhenItCanChangeTheAnswer() {
		AtomicInteger runs = new AtomicInteger();
		VaultKdfTier.NativeCheck counting = () -> {
			runs.incrementAndGet();
			return true;
		};
		VaultKdfTier.choose(2 * GB, true, false, counting);
		VaultKdfTier.choose(16 * GB, false, false, counting);
		VaultKdfTier.choose(16 * GB, true, true, counting);
		assertEquals(0, runs.get());
		VaultKdfTier.choose(16 * GB, true, false, counting);
		assertEquals(1, runs.get());
	}

	@Test
	public void theTierParametersAreTheDocumentedOnes() {
		assertEquals("1048576 1 1 32, 524288 2 1 32, 262144 3 1 32",
				text(VaultKdfTier.TIER_A) + ", " + text(VaultKdfTier.TIER_B)
						+ ", " + text(VaultKdfTier.TIER_C));
		assertTrue(VaultKdfTier.sameCost(VaultKdfTier.TIER_C,
				Argon2.Argon2Params.getDefault()));
	}

	private static String text(Argon2.Argon2Params p) {
		return p.memoryKb + " " + p.iterations + " " + p.parallelism + " "
				+ p.hashLength;
	}

	@Test
	public void strengthIsOrderedByMemoryFirst() {
		Argon2.Argon2Params reduced =
				new Argon2.Argon2Params(128 * 1024, 6, 1, 32);
		Argon2.Argon2Params[] order = {reduced, VaultKdfTier.TIER_C,
				VaultKdfTier.TIER_B, VaultKdfTier.TIER_A};
		for (int i = 0; i < order.length; i++) {
			for (int j = 0; j < order.length; j++) {
				assertEquals(i + " vs " + j, i >= j,
						VaultKdfTier.atLeast(order[i], order[j]));
			}
		}
		assertTrue(VaultKdfTier.atLeast(256 * 1024, 4, VaultKdfTier.TIER_C));
		assertFalse(VaultKdfTier.atLeast(256 * 1024, 2, VaultKdfTier.TIER_C));
	}

	@Test
	public void onlyTheThreeTiersAreKnown() {
		assertSame(VaultKdfTier.TIER_A,
				VaultKdfTier.knownTier(1024 * 1024, 1, 1));
		assertSame(VaultKdfTier.TIER_B,
				VaultKdfTier.knownTier(512 * 1024, 2, 1));
		assertSame(VaultKdfTier.TIER_C,
				VaultKdfTier.knownTier(256 * 1024, 3, 1));
		assertNull(VaultKdfTier.knownTier(128 * 1024, 6, 1));
		assertNull(VaultKdfTier.knownTier(1024 * 1024, 1, 4));
	}
}
