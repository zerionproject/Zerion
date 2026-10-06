package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.util.Arrays;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CallSignalPayloadsTest {

	private static final String KEY = hex(0x11);
	private static final String EPH = hex(0x22);
	private static final String AGREE = hex(0x33);
	private static final String ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";

	private static String hex(int b) {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) b);
		return CallHex.bytesToHex(raw);
	}

	private static byte[] bytes(String hex) {
		return CallHex.hexToBytes(hex);
	}

	@Test
	public void anOfferThatMustNotRingIsRefused() {
		assertNull(CallSignalPayloads.parseOffer(null));
		assertNull(CallSignalPayloads.parseOffer(""));
		assertNull(CallSignalPayloads.parseOffer("zz"));
		assertNull(CallSignalPayloads.parseOffer(KEY.substring(2)));
		assertNull(CallSignalPayloads.parseOffer(KEY + "|" + EPH + "00"));
		assertNull(CallSignalPayloads.parseOffer(KEY + "|nothex"));
		assertNull(CallSignalPayloads.parseOffer(KEY + "|" + EPH + "|XK1:00"));
		assertNull(CallSignalPayloads.parseOffer(KEY + "|" + EPH + "|XK1:"
				+ AGREE + "|XK1:" + AGREE));
	}

	@Test
	public void anOfferWithoutAContributionIsStillRead() {
		CallSignalPayloads.Offer o = CallSignalPayloads.parseOffer(KEY);
		assertNotNull(o);
		assertArrayEquals(bytes(KEY), o.key);
		assertNull(o.contribution);
		assertNull(o.agreementKey);
		assertFalse(o.video);
		o = CallSignalPayloads.parseOffer(KEY + "|VIDEO");
		assertNotNull(o);
		assertTrue(o.video);
		assertNull(o.contribution);
	}

	@Test
	public void offersRoundTrip() {
		for (boolean video : new boolean[] {false, true}) {
			for (String agree : new String[] {null, AGREE}) {
				String p = CallSignalPayloads.formatOffer(KEY, bytes(EPH),
						agree == null ? null : bytes(agree), video);
				CallSignalPayloads.Offer o = CallSignalPayloads.parseOffer(p);
				assertNotNull(p, o);
				assertArrayEquals(bytes(KEY), o.key);
				assertArrayEquals(bytes(EPH), o.contribution);
				assertEquals(video, o.video);
				if (agree == null) assertNull(o.agreementKey);
				else assertArrayEquals(bytes(agree), o.agreementKey);
			}
		}
	}

	@Test
	public void anEarlierAndroidCalleeReadsANewOfferUnchanged() {
		for (boolean video : new boolean[] {false, true}) {
			String p = CallSignalPayloads.formatOffer(KEY, bytes(EPH),
					bytes(AGREE), video);
			String[] earlier = earlierAndroidOffer(p);
			assertEquals(KEY, earlier[0]);
			assertEquals(EPH, earlier[1]);
			assertEquals(String.valueOf(video), earlier[2]);
		}
	}

	@Test
	public void theIosCalleeReadsANewOfferUnchanged() {
		for (boolean video : new boolean[] {false, true}) {
			String p = CallSignalPayloads.formatOffer(KEY, bytes(EPH),
					bytes(AGREE), video);
			String[] ios = iosOffer(p);
			assertEquals(KEY, ios[0]);
			assertEquals(EPH, ios[1]);
			assertEquals(String.valueOf(video), ios[2]);
		}
	}

	@Test
	public void anAnswerWithoutAnAgreementKeyIsWhatEarlierCallersRead() {
		String p = CallSignalPayloads.formatAnswer(ONION, 80, bytes(EPH),
				null);
		assertEquals(ONION + ":80|" + EPH, p);
		CallSignalPayloads.Answer a = CallSignalPayloads.parseAnswer(p);
		assertNotNull(a);
		assertEquals(ONION, a.onion);
		assertEquals(80, a.port);
		assertArrayEquals(bytes(EPH), a.contribution);
		assertNull(a.agreementKey);
	}

	@Test
	public void answersRoundTripAndMalformedOnesAreRefused() {
		String p = CallSignalPayloads.formatAnswer(ONION, 80, bytes(EPH),
				bytes(AGREE));
		CallSignalPayloads.Answer a = CallSignalPayloads.parseAnswer(p);
		assertNotNull(a);
		assertArrayEquals(bytes(AGREE), a.agreementKey);
		assertNotNull(CallSignalPayloads.parseAnswer(ONION + ":80"));
		assertNull(CallSignalPayloads.parseAnswer(null));
		assertNull(CallSignalPayloads.parseAnswer(ONION));
		assertNull(CallSignalPayloads.parseAnswer(ONION + ":0"));
		assertNull(CallSignalPayloads.parseAnswer(ONION + ":70000"));
		assertNull(CallSignalPayloads.parseAnswer(ONION + ":x"));
		assertNull(CallSignalPayloads.parseAnswer(ONION + ":80|zz"));
		assertNull(CallSignalPayloads.parseAnswer(ONION + ":80|" + EPH
				+ "|junk"));
		assertNull(CallSignalPayloads.parseAnswer(ONION + ":80|" + EPH
				+ "|XK1:00"));
	}

	private static String[] earlierAndroidOffer(String rawPayload) {
		String ephemeralHex = null;
		String[] parts = rawPayload.split("\\|");
		String voiceCallKeyHex = parts[0];
		if (parts.length >= 2) {
			if ("VIDEO".equals(parts[parts.length - 1])) {
				if (parts.length >= 3) ephemeralHex = parts[1];
			} else {
				ephemeralHex = parts[1];
			}
		}
		boolean video = parts.length >= 2
				&& "VIDEO".equals(parts[parts.length - 1]);
		return new String[] {voiceCallKeyHex, ephemeralHex,
				String.valueOf(video)};
	}

	private static String[] iosOffer(String payload) {
		String[] parts = payload.split("\\|", -1);
		String eph = null;
		boolean video = false;
		for (int i = 1; i < parts.length; i++) {
			if (parts[i].equals("VIDEO")) video = true;
			else if (iosHex32(parts[i]) != null) eph = parts[i];
		}
		return new String[] {parts[0], eph, String.valueOf(video)};
	}

	@Nullable
	private static byte[] iosHex32(String hex) {
		if (hex.isEmpty() || hex.length() % 2 != 0) return null;
		byte[] out = new byte[hex.length() / 2];
		for (int i = 0; i < hex.length(); i += 2) {
			int hi = Character.digit(hex.charAt(i), 16);
			int lo = Character.digit(hex.charAt(i + 1), 16);
			if (hi < 0 || lo < 0) return null;
			out[i / 2] = (byte) ((hi << 4) + lo);
		}
		return out.length == 32 ? out : null;
	}
}
