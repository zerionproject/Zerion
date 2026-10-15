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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class CallKeyWipeTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_ID = "zt-wipe-call";

	private final CallRig rig = new CallRig(CALL_ID);

	@After
	public void tearDown() {
		rig.tearDown();
	}

	private static SecretKey callKey() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x44);
		return new SecretKey(raw);
	}

	private static byte[] contribution(int b) {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) b);
		return raw;
	}

	private static boolean zero(byte[] b) {
		for (byte x : b) {
			if (x != 0) return false;
		}
		return true;
	}

	private VoiceCallService callerWith(KeyPair agreement) throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		CallRig.set(s, "voiceCallKey", callKey());
		CallRig.set(s, "localEphemeralSecret", contribution(0x0A));
		CallRig.set(s, "localAgreementKeyPair", agreement);
		return s;
	}

	@Test
	public void theAgreementPrivateKeyIsZeroedOnceTheCallSecretExists()
			throws Exception {
		VoiceCallCrypto crypto = rig.crypto();
		KeyPair ours = crypto.generateCallAgreementKeyPair();
		byte[] held = ours.getPrivate().getEncoded();
		assertFalse(zero(held));
		VoiceCallService s = callerWith(ours);
		KeyPair theirs = crypto.generateCallAgreementKeyPair();
		String onion = rig.realManager().expectedPeerOnion(CALL_ID, callKey(),
				true);
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER,
				CallSignalPayloads.formatAnswer(onion, 80, contribution(0x0B),
						crypto.encodeCallAgreementPublicKey(theirs)));
		CallRig.await(() -> !rig.dialled.isEmpty(), 10_000);
		assertTrue((Boolean) CallRig.get(s, "agreementNegotiated"));
		assertFalse(zero(held));

		CallRig.call(s, "deriveAudioEncryptionKeys");

		assertNull(CallRig.get(s, "localAgreementKeyPair"));
		assertTrue("the private scalar the call held is zero", zero(held));
		assertArrayEquals(held, ours.getPrivate().getEncoded());
		assertNull(CallRig.get(s, "localEphemeralSecret"));
		assertNull(CallRig.get(s, "remoteEphemeralSecret"));
	}

	@Test
	public void anUnusedAgreementKeyIsZeroedWhenTheCallEnds()
			throws Exception {
		KeyPair ours = rig.crypto().generateCallAgreementKeyPair();
		byte[] held = ours.getPrivate().getEncoded();
		VoiceCallService s = callerWith(ours);
		SecretKey callKey = (SecretKey) CallRig.get(s, "voiceCallKey");
		byte[] ephemeral = (byte[]) CallRig.get(s, "localEphemeralSecret");

		CallRig.call(s, "zeroizeKeyMaterial");

		assertTrue(zero(held));
		assertTrue(zero(callKey.getBytes()));
		assertTrue(zero(ephemeral));
		assertNull(CallRig.get(s, "localAgreementKeyPair"));
		assertNull(CallRig.get(s, "voiceCallKey"));
	}

	@Test
	public void anAnswerWithoutAgreementZeroesTheOfferedKey()
			throws Exception {
		KeyPair ours = rig.crypto().generateCallAgreementKeyPair();
		byte[] held = ours.getPrivate().getEncoded();
		VoiceCallService s = callerWith(ours);
		String anyOnion =
				"bcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwxy";
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER,
				anyOnion + ":80|" + CallHex.bytesToHex(contribution(0x0B)));
		CallRig.await(() -> !rig.dialled.isEmpty(), 10_000);
		assertFalse((Boolean) CallRig.get(s, "agreementNegotiated"));
		assertTrue(zero(held));
	}

	@Test
	public void theDerivedCallKeysAreZeroedWhenTheCallEnds()
			throws Exception {
		VoiceCallCrypto crypto = rig.crypto();
		KeyPair ours = crypto.generateCallAgreementKeyPair();
		VoiceCallService s = callerWith(ours);
		KeyPair theirs = crypto.generateCallAgreementKeyPair();
		String onion = rig.realManager().expectedPeerOnion(CALL_ID, callKey(),
				true);
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER,
				CallSignalPayloads.formatAnswer(onion, 80, contribution(0x0B),
						crypto.encodeCallAgreementPublicKey(theirs)));
		CallRig.await(() -> !rig.dialled.isEmpty(), 10_000);
		CallRig.call(s, "deriveAudioEncryptionKeys");
		SecretKey secret = (SecretKey) CallRig.get(s, "callSecret");
		VoiceCallCrypto.AudioKeys keys =
				(VoiceCallCrypto.AudioKeys) CallRig.get(s, "audioKeys");
		assertFalse(zero(secret.getBytes()));
		assertFalse(zero(keys.txKey.getBytes()));

		CallRig.call(s, "zeroizeKeyMaterial");

		assertTrue(zero(secret.getBytes()));
		assertTrue(zero(keys.txKey.getBytes()));
		assertTrue(zero(keys.rxKey.getBytes()));
		assertNull(CallRig.get(s, "callSecret"));
		assertNull(CallRig.get(s, "audioKeys"));
	}
}
