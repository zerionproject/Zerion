package com.professor.zerion.android.conversation.voice;

import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.api.crypto.SecretKey;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class VoiceCallKeyHolderTest {

	@After
	public void tearDown() {
		VoiceCallKeyHolder.clear();
	}

	private static SecretKey key(int b) {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) b);
		return new SecretKey(raw);
	}

	private static byte[] eph(int b) {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) b);
		return raw;
	}

	private static boolean wiped(SecretKey k) {
		return Arrays.equals(new byte[32], k.getBytes());
	}

	@Test
	public void onlyTheSameContactAndCallTakeTheMaterial() {
		SecretKey k = key(1);
		VoiceCallKeyHolder.setOffer(7, "call-a", k, eph(2));
		assertNull(VoiceCallKeyHolder.consumeKey(7, "call-b"));
		assertNull(VoiceCallKeyHolder.consumeKey(8, "call-a"));
		assertNull(VoiceCallKeyHolder.consumeKey(7, null));
		assertNull(VoiceCallKeyHolder.consumeRemoteEphemeral(8, "call-a"));
		assertSame(k, VoiceCallKeyHolder.consumeKey(7, "call-a"));
		assertArrayEquals(eph(2),
				VoiceCallKeyHolder.consumeRemoteEphemeral(7, "call-a"));
		assertNull(VoiceCallKeyHolder.consumeKey(7, "call-a"));
	}

	@Test
	public void aNewerOfferFromTheSameContactWipesTheOlderOne() {
		SecretKey first = key(1);
		byte[] firstEph = eph(2);
		VoiceCallKeyHolder.setOffer(7, "call-a", first, firstEph);
		VoiceCallKeyHolder.setOffer(7, "call-b", key(3), null);
		assertTrue(wiped(first));
		assertTrue(Arrays.equals(new byte[32], firstEph));
		assertNull(VoiceCallKeyHolder.consumeKey(7, "call-a"));
		assertNull("no ephemeral is inherited from the older offer",
				VoiceCallKeyHolder.consumeRemoteEphemeral(7, "call-b"));
	}

	@Test
	public void anOfferFromAnotherContactLeavesTheFirstCallersMaterial() {
		SecretKey first = key(1);
		VoiceCallKeyHolder.setOffer(7, "call-a", first, eph(2));
		SecretKey second = key(3);
		VoiceCallKeyHolder.setOffer(9, "call-b", second, null);
		assertSame(first, VoiceCallKeyHolder.consumeKey(7, "call-a"));
		assertArrayEquals(eph(2),
				VoiceCallKeyHolder.consumeRemoteEphemeral(7, "call-a"));
		assertSame(second, VoiceCallKeyHolder.consumeKey(9, "call-b"));
	}

	@Test
	public void clearingForAnotherCallLeavesTheMaterial() {
		SecretKey k = key(1);
		VoiceCallKeyHolder.setOffer(7, "call-a", k, eph(2));
		VoiceCallKeyHolder.clearFor(7, "call-old");
		assertSame(k, VoiceCallKeyHolder.consumeKey(7, "call-a"));
		VoiceCallKeyHolder.setOffer(7, "call-c", key(4), null);
		VoiceCallKeyHolder.clearFor(7, "call-c");
		assertNull(VoiceCallKeyHolder.consumeKey(7, "call-c"));
	}

	@Test
	public void clearingAContactWipesOnlyThatContactsOffer() {
		SecretKey mine = key(1);
		SecretKey other = key(2);
		VoiceCallKeyHolder.setOffer(7, "call-a", mine, null);
		VoiceCallKeyHolder.setOffer(9, "call-b", other, null);
		VoiceCallKeyHolder.clearContact(7);
		assertTrue(wiped(mine));
		assertNull(VoiceCallKeyHolder.consumeKey(7, "call-a"));
		assertSame(other, VoiceCallKeyHolder.consumeKey(9, "call-b"));
	}

	@Test
	public void anOfferThatIsNeverTakenIsWipedAfterARing() {
		SecretKey k = key(1);
		byte[] e = eph(2);
		VoiceCallKeyHolder.setOffer(7, "call-a", k, e);
		VoiceCallKeyHolder.purgeExpired(System.currentTimeMillis()
				+ VoiceCallKeyHolder.OFFER_LIFETIME_MS / 2);
		assertTrue("still held during the ring", !wiped(k));
		VoiceCallKeyHolder.purgeExpired(System.currentTimeMillis()
				+ VoiceCallKeyHolder.OFFER_LIFETIME_MS + 1_000);
		assertTrue(wiped(k));
		assertTrue(Arrays.equals(new byte[32], e));
		assertNull(VoiceCallKeyHolder.consumeKey(7, "call-a"));
	}

	@Test
	public void theNumberOfPendingOffersIsBounded() {
		SecretKey oldest = key(1);
		VoiceCallKeyHolder.setOffer(100, "c", oldest, null);
		for (int i = 1; i <= VoiceCallKeyHolder.MAX_PENDING_OFFERS; i++) {
			VoiceCallKeyHolder.setOffer(100 + i, "c", key(2), null);
		}
		assertTrue("the oldest offer is dropped and wiped", wiped(oldest));
		assertNull(VoiceCallKeyHolder.consumeKey(100, "c"));
	}
}
