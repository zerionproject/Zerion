package com.professor.zerion.android.conversation.voice;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.app.conversation.voice.VoiceCallCrypto;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;

import java.util.Arrays;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallKeyScheduleTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_ID = "zt-schedule-call";

	private final CallRig rig = new CallRig(CALL_ID);

	@After
	public void tearDown() {
		rig.tearDown();
	}

	private static SecretKey callKey() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x31);
		return new SecretKey(raw);
	}

	private static byte[] contribution(int b) {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) b);
		return raw;
	}

	private VoiceCallService caller(boolean offerAgreement)
			throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		CallRig.set(s, "voiceCallKey", callKey());
		CallRig.set(s, "localEphemeralSecret", contribution(0x0A));
		if (offerAgreement) {
			CallRig.set(s, "localAgreementKeyPair",
					rig.crypto().generateCallAgreementKeyPair());
		}
		return s;
	}

	private VoiceCallCrypto.AudioKeys derive(VoiceCallService s)
			throws Exception {
		CallRig.call(s, "deriveAudioEncryptionKeys");
		return (VoiceCallCrypto.AudioKeys) CallRig.get(s, "audioKeys");
	}

	@Test
	public void aCallOfThisVersionKeepsItsAgreedKeysAcrossReconnects()
			throws Exception {
		VoiceCallService s = caller(true);
		VoiceCallCrypto crypto = rig.crypto();
		KeyPair callerPair = (KeyPair) CallRig.get(s, "localAgreementKeyPair");
		byte[] callerPub = crypto.encodeCallAgreementPublicKey(callerPair);
		KeyPair calleePair = crypto.generateCallAgreementKeyPair();
		String calleeOnion = rig.realManager().expectedPeerOnion(CALL_ID,
				callKey(), true);
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER,
				CallSignalPayloads.formatAnswer(calleeOnion, 80,
						contribution(0x0B),
						crypto.encodeCallAgreementPublicKey(calleePair)));
		CallRig.await(() -> !rig.dialled.isEmpty(), 10_000);
		assertEquals(java.util.Collections.singletonList(calleeOnion),
				rig.dialled);
		assertTrue((Boolean) CallRig.get(s, "agreementNegotiated"));

		VoiceCallCrypto.AudioKeys first = derive(s);
		assertNull("the private agreement key outlived its use",
				CallRig.get(s, "localAgreementKeyPair"));
		SecretKey calleeSecret = crypto.deriveCallSecret(callKey(),
				calleePair, callerPub, false, CALL_ID);
		VoiceCallCrypto.AudioKeys callee = crypto.deriveEphemeralAudioKeys(
				calleeSecret, contribution(0x0B), contribution(0x0A), false);
		assertArrayEquals(first.txKey.getBytes(), callee.rxKey.getBytes());
		assertArrayEquals(first.rxKey.getBytes(), callee.txKey.getBytes());

		VoiceCallCrypto.AudioKeys afterReconnect = derive(s);
		assertSame(first, afterReconnect);
		VoiceCallCrypto.AudioKeys fromCallKeyAlone =
				crypto.deriveAudioKeys(callKey(), true);
		assertFalse("a reconnect fell back to keys from the call key alone",
				Arrays.equals(fromCallKeyAlone.txKey.getBytes(),
						afterReconnect.txKey.getBytes()));
		VoiceCallCrypto.AudioKeys fromSignalledValues =
				crypto.deriveEphemeralAudioKeys(callKey(), contribution(0x0A),
						contribution(0x0B), true);
		assertFalse("the keys follow from what was signalled",
				Arrays.equals(fromSignalledValues.txKey.getBytes(),
						first.txKey.getBytes()));
	}

	@Test
	public void anAnswerNamingAnotherEndpointIsNeverDialled()
			throws Exception {
		VoiceCallService s = caller(true);
		VoiceCallCrypto crypto = rig.crypto();
		KeyPair calleePair = crypto.generateCallAgreementKeyPair();
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER,
				CallSignalPayloads.formatAnswer(
						"bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxy",
						80, contribution(0x0B),
						crypto.encodeCallAgreementPublicKey(calleePair)));
		CallRig.await(() -> {
			try {
				return (Boolean) CallRig.get(s, "isShuttingDown");
			} catch (Exception e) {
				return false;
			}
		}, 10_000);
		assertTrue("the answer was dialled", rig.dialled.isEmpty());
		assertTrue("the call was not ended",
				(Boolean) CallRig.get(s, "isShuttingDown"));
	}

	@Test
	public void aCalleeOfAnEarlierVersionStillConnectsWithTheEarlierKeys()
			throws Exception {
		VoiceCallService s = caller(true);
		String anyOnion =
				"bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxy";
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER,
				anyOnion + ":80|" + CallHex.bytesToHex(contribution(0x0B)));
		CallRig.await(() -> !rig.dialled.isEmpty(), 10_000);
		assertEquals(java.util.Collections.singletonList(anyOnion),
				rig.dialled);
		assertFalse((Boolean) CallRig.get(s, "agreementNegotiated"));
		assertNull(CallRig.get(s, "localAgreementKeyPair"));
		VoiceCallCrypto crypto = rig.crypto();
		VoiceCallCrypto.AudioKeys first = derive(s);
		assertArrayEquals(crypto.deriveEphemeralAudioKeys(callKey(),
				contribution(0x0A), contribution(0x0B), true).txKey.getBytes(),
				first.txKey.getBytes());
		VoiceCallCrypto.AudioKeys second = derive(s);
		assertArrayEquals("an earlier peer derives these on a reconnect",
				crypto.deriveAudioKeys(callKey(), true).txKey.getBytes(),
				second.txKey.getBytes());
	}

	@Test
	public void theCalleeAnswersWithItsAgreementKeyOnlyWhenOffered()
			throws Exception {
		VoiceCallCrypto crypto = rig.crypto();
		KeyPair callerPair = crypto.generateCallAgreementKeyPair();
		String offer = CallSignalPayloads.formatOffer(
				CallHex.bytesToHex(callKey().getBytes()), contribution(0x0A),
				crypto.encodeCallAgreementPublicKey(callerPair), false);
		assertTrue(VoiceCallKeyHolder.holdOfferPayload(CallRig.CONTACT,
				CALL_ID, offer, new android.os.Handler(
						android.os.Looper.getMainLooper())));
		VoiceCallService s = rig.ringingIncoming();
		s.acceptCall();
		CallRig.await(() -> rig.lastSent("createCallAnswer") != null,
				10_000);
		CallRig.Sent answer = rig.lastSent("createCallAnswer");
		assertNotNull(answer);
		CallSignalPayloads.Answer parsed =
				CallSignalPayloads.parseAnswer(answer.payload);
		assertNotNull(parsed);
		assertNotNull("the callee did not agree", parsed.agreementKey);
		assertTrue((Boolean) CallRig.get(s, "agreementNegotiated"));
	}

	@Test
	public void aCallerOfAnEarlierVersionGetsTheAnswerItCanRead()
			throws Exception {
		String offer = CallHex.bytesToHex(callKey().getBytes()) + "|"
				+ CallHex.bytesToHex(contribution(0x0A));
		assertTrue(VoiceCallKeyHolder.holdOfferPayload(CallRig.CONTACT,
				CALL_ID, offer, new android.os.Handler(
						android.os.Looper.getMainLooper())));
		VoiceCallService s = rig.ringingIncoming();
		s.acceptCall();
		CallRig.await(() -> rig.lastSent("createCallAnswer") != null,
				10_000);
		CallRig.Sent answer = rig.lastSent("createCallAnswer");
		assertNotNull(answer);
		String payload = answer.payload;
		assertNotNull(payload);
		int bar = payload.indexOf('|');
		assertTrue(bar > 0);
		assertEquals("an earlier caller reads everything after the bar as"
				+ " its contribution", 64, payload.substring(bar + 1).length());
		assertFalse((Boolean) CallRig.get(s, "agreementNegotiated"));
	}

	@Test
	public void framesAreAdmittedOnlyInOrderAcrossReconnects() {
		AudioFrameReplayGuard g = new AudioFrameReplayGuard();
		assertTrue(g.admit(0));
		assertTrue(g.admit(1));
		assertFalse("a replayed frame", g.admit(1));
		assertFalse("an older frame", g.admit(0));
		assertTrue("a reconnect moves the sequence forward", g.admit(1001));
		assertFalse("a frame of the earlier connection", g.admit(500));
	}
}
