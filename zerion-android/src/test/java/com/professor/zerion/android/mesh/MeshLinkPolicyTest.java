package com.professor.zerion.android.mesh;

import org.zerionproject.core.util.StringUtils;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MeshLinkPolicyTest {

	private final MeshLinkPolicy policy =
			new MeshLinkPolicy(new SecureRandom());

	@Test
	public void everyNonceIsFreshAndSortsAboveAnEarlierReleasesNonce() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			byte[] n = policy.freshNonce();
			assertEquals(MeshLinkPolicy.NONCE_BYTES, n.length);
			assertTrue(seen.add(StringUtils.toHexString(n)));
			byte[] legacy = new byte[MeshLinkPolicy.NONCE_BYTES];
			new SecureRandom().nextBytes(legacy);
			legacy[0] = (byte) 0xFE;
			assertTrue(MeshDiscovery.compareNonce(n, legacy) > 0);
		}
	}

	@Test
	public void anAdvertisingSetEndsBeforeThePlatformCanChangeItsAddress() {
		long min = Long.MAX_VALUE;
		long max = Long.MIN_VALUE;
		for (int i = 0; i < 2000; i++) {
			long l = policy.nextAdvertisingSetLifetimeMs();
			min = Math.min(min, l);
			max = Math.max(max, l);
		}
		assertTrue(min >= 4 * 60_000L);
		assertTrue(max <= 6 * 60_000L);
		assertTrue(max - min > 60_000L);
	}

	@Test
	public void anAnnouncementRoundTripsAndOtherFramesAreNotAnnouncements() {
		byte[] nonce = policy.freshNonce();
		byte[] frame = MeshLinkPolicy.encodeAnnounce(nonce);
		assertTrue(MeshLinkPolicy.isControl(frame));
		assertArrayEquals(nonce, MeshLinkPolicy.decodeAnnounce(frame));
		byte[] meshFrame = new byte[frame.length];
		meshFrame[0] = 0x01;
		assertFalse(MeshLinkPolicy.isControl(meshFrame));
		assertNull(MeshLinkPolicy.decodeAnnounce(meshFrame));
		assertNull(MeshLinkPolicy.decodeAnnounce(
				Arrays.copyOf(frame, frame.length + 1)));
	}

	@Test
	public void onlyALinkedNeighboursLastAnnouncementsCount() {
		byte[] a = policy.freshNonce();
		byte[] b = policy.freshNonce();
		byte[] c = policy.freshNonce();
		policy.onAnnounce("c:stranger", a, 0L);
		assertFalse(policy.isAnnounced(StringUtils.toHexString(a), 0L));
		policy.onLinkUp("c:peer", 0L);
		policy.onAnnounce("c:peer", a, 0L);
		policy.onAnnounce("c:peer", b, 1L);
		assertTrue(policy.isAnnounced(StringUtils.toHexString(a), 2L));
		policy.onAnnounce("c:peer", c, 2L);
		assertFalse("only the last two count",
				policy.isAnnounced(StringUtils.toHexString(a), 3L));
		assertTrue(policy.isAnnounced(StringUtils.toHexString(c), 3L));
		assertFalse(policy.isAnnounced(StringUtils.toHexString(c),
				2L + MeshLinkPolicy.ANNOUNCED_NONCE_TTL_MS + 1));
		policy.onLinkDown("c:peer");
		assertFalse(policy.isAnnounced(StringUtils.toHexString(c), 3L));
	}

	@Test
	public void aSilentLinkGivesUpItsSlotAfterTheGracePeriod() {
		policy.onLinkUp("c:silent", 0L);
		policy.onLinkUp("c:useful", 0L);
		policy.onUseful("c:useful", 1_000L);
		Set<String> both = new HashSet<>(Arrays.asList("c:silent",
				"c:useful"));
		assertNull(policy.idleLinkToEvict(both,
				MeshLinkPolicy.NEW_LINK_GRACE_MS - 1));
		assertEquals("c:silent", policy.idleLinkToEvict(both,
				MeshLinkPolicy.NEW_LINK_GRACE_MS));
	}

	@Test
	public void aUsefulLinkKeepsItsSlotUntilItHasBeenIdleLong() {
		policy.onLinkUp("c:a", 0L);
		policy.onLinkUp("c:b", 0L);
		policy.onUseful("c:a", 10_000L);
		policy.onUseful("c:b", 20_000L);
		Set<String> both = new HashSet<>(Arrays.asList("c:a", "c:b"));
		assertNull(policy.idleLinkToEvict(both,
				10_000L + MeshLinkPolicy.IDLE_EVICT_MS - 1));
		assertEquals("c:a", policy.idleLinkToEvict(both,
				10_000L + MeshLinkPolicy.IDLE_EVICT_MS));
		policy.onUseful("c:a", 10_000L + MeshLinkPolicy.IDLE_EVICT_MS);
		assertEquals("c:b", policy.idleLinkToEvict(both,
				20_000L + MeshLinkPolicy.IDLE_EVICT_MS));
	}

	@Test
	public void aSilentLinkGoesBeforeAnIdleUsefulOne() {
		policy.onLinkUp("c:useful", 0L);
		policy.onUseful("c:useful", 1L);
		long later = 1L + MeshLinkPolicy.IDLE_EVICT_MS;
		policy.onLinkUp("c:silent", later - MeshLinkPolicy.NEW_LINK_GRACE_MS);
		Set<String> both = new HashSet<>(Arrays.asList("c:useful",
				"c:silent"));
		assertEquals("c:silent", policy.idleLinkToEvict(both, later));
		assertEquals("c:unknown", policy.idleLinkToEvict(
				new HashSet<>(Arrays.asList("c:useful", "c:unknown")), 2L));
	}
}
