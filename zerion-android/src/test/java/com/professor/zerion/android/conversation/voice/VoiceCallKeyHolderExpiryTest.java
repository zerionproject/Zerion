package com.professor.zerion.android.conversation.voice;

import android.os.Handler;
import android.os.Looper;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.crypto.SecretKey;

import java.time.Duration;
import java.util.Arrays;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallKeyHolderExpiryTest {

	private final Handler main = new Handler(Looper.getMainLooper());

	@After
	public void tearDown() {
		VoiceCallKeyHolder.clear();
	}

	private static SecretKey key(int b) {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) b);
		return new SecretKey(raw);
	}

	private static boolean wiped(SecretKey k) {
		return Arrays.equals(new byte[32], k.getBytes());
	}

	private static void advance(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	@Test
	public void anUntakenOfferIsWipedWhenItsRingIsOver() {
		SecretKey k = key(1);
		byte[] eph = new byte[32];
		Arrays.fill(eph, (byte) 2);
		VoiceCallKeyHolder.holdOffer(7, "call-a", k, eph, null, main);
		advance(VoiceCallKeyHolder.OFFER_LIFETIME_MS - 1_000);
		assertTrue(VoiceCallKeyHolder.isHeld(7, "call-a"));
		assertFalse(wiped(k));
		advance(2_000);
		assertFalse("the offer outlived its ring",
				VoiceCallKeyHolder.isHeld(7, "call-a"));
		assertTrue("the key was not wiped", wiped(k));
		assertTrue(Arrays.equals(new byte[32], eph));
	}

	@Test
	public void anOlderOffersTimerLeavesANewerOfferAlone() {
		SecretKey first = key(1);
		VoiceCallKeyHolder.holdOffer(7, "call-a", first, null, null, main);
		advance(VoiceCallKeyHolder.OFFER_LIFETIME_MS / 2);
		SecretKey second = key(3);
		VoiceCallKeyHolder.holdOffer(7, "call-b", second, null, null, main);
		assertTrue(wiped(first));
		advance(VoiceCallKeyHolder.OFFER_LIFETIME_MS / 2 + 1_000);
		assertTrue(VoiceCallKeyHolder.isHeld(7, "call-b"));
		assertFalse(wiped(second));
		advance(VoiceCallKeyHolder.OFFER_LIFETIME_MS);
		assertTrue(wiped(second));
	}

	@Test
	public void materialACallTookIsNotWipedByTheTimer() {
		VoiceCallKeyHolder.holdOffer(7, "call-a", key(1), null, null, main);
		SecretKey taken = VoiceCallKeyHolder.consumeKey(7, "call-a");
		assertNotNull(taken);
		advance(VoiceCallKeyHolder.OFFER_LIFETIME_MS + 1_000);
		assertArrayEquals(key(1).getBytes(), taken.getBytes());
	}

	@Test
	public void anOfferThatMustNotRingIsNotHeld() {
		VoiceCallKeyHolder.holdOffer(7, "call-a", key(1), null, null, main);
		assertFalse(VoiceCallKeyHolder.holdOfferPayload(7, "call-b", null,
				main));
		assertFalse("a malformed offer left the earlier one",
				VoiceCallKeyHolder.isHeld(7, "call-a"));
		assertFalse(VoiceCallKeyHolder.holdOfferPayload(7, "call-c",
				CallHex.bytesToHex(key(5).getBytes()) + "|00ff", main));
		assertFalse(VoiceCallKeyHolder.isHeld(7, "call-c"));
		assertTrue("an offer without a contribution is still held",
				VoiceCallKeyHolder.holdOfferPayload(7, "call-d",
						CallHex.bytesToHex(key(5).getBytes()), main));
		assertTrue(VoiceCallKeyHolder.isHeld(7, "call-d"));
	}
}
