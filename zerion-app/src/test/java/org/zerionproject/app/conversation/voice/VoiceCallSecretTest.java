package org.zerionproject.app.conversation.voice;

import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class VoiceCallSecretTest {

	private final CryptoComponent crypto =
			DaggerVoiceCryptoTestComponent.create().getCryptoComponent();
	private final VoiceCallCryptoImpl voice = new VoiceCallCryptoImpl(crypto);

	private static SecretKey key(int b) {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) b);
		return new SecretKey(raw);
	}

	@Test
	public void bothSidesOfACallAgreeOnTheCallSecret() throws Exception {
		SecretKey k = key(7);
		KeyPair caller = voice.generateCallAgreementKeyPair();
		KeyPair callee = voice.generateCallAgreementKeyPair();
		SecretKey a = voice.deriveCallSecret(k, caller,
				voice.encodeCallAgreementPublicKey(callee), true, "call-1");
		SecretKey b = voice.deriveCallSecret(k, callee,
				voice.encodeCallAgreementPublicKey(caller), false, "call-1");
		assertArrayEquals(a.getBytes(), b.getBytes());
	}

	@Test
	public void theSignalledValuesAloneDoNotYieldTheCallSecret()
			throws Exception {
		SecretKey k = key(7);
		KeyPair caller = voice.generateCallAgreementKeyPair();
		KeyPair callee = voice.generateCallAgreementKeyPair();
		byte[] callerPub = voice.encodeCallAgreementPublicKey(caller);
		byte[] calleePub = voice.encodeCallAgreementPublicKey(callee);
		SecretKey real = voice.deriveCallSecret(k, caller, calleePub, true,
				"call-1");
		KeyPair attacker = voice.generateCallAgreementKeyPair();
		SecretKey guess = voice.deriveCallSecret(k, attacker, calleePub,
				true, "call-1");
		assertFalse(Arrays.equals(real.getBytes(), guess.getBytes()));
		SecretKey legacy = voice.deriveEphemeralAudioKeys(k, new byte[32],
				new byte[32], true).txKey;
		assertFalse(Arrays.equals(real.getBytes(), legacy.getBytes()));
		assertFalse(Arrays.equals(real.getBytes(), k.getBytes()));
		assertTrue(callerPub.length == 32 && calleePub.length == 32);
	}

	@Test
	public void theCallSecretIsBoundToTheCallId() throws Exception {
		SecretKey k = key(7);
		KeyPair caller = voice.generateCallAgreementKeyPair();
		KeyPair callee = voice.generateCallAgreementKeyPair();
		byte[] calleePub = voice.encodeCallAgreementPublicKey(callee);
		SecretKey one = voice.deriveCallSecret(k, caller, calleePub, true,
				"call-1");
		SecretKey two = voice.deriveCallSecret(k, caller, calleePub, true,
				"call-2");
		assertFalse(Arrays.equals(one.getBytes(), two.getBytes()));
	}

	@Test
	public void theCallSecretBindsTheRoles() throws Exception {
		SecretKey k = key(7);
		KeyPair caller = voice.generateCallAgreementKeyPair();
		KeyPair callee = voice.generateCallAgreementKeyPair();
		SecretKey asCaller = voice.deriveCallSecret(k, caller,
				voice.encodeCallAgreementPublicKey(callee), true, "call-1");
		SecretKey bothCallers = voice.deriveCallSecret(k, callee,
				voice.encodeCallAgreementPublicKey(caller), true, "call-1");
		assertFalse(Arrays.equals(asCaller.getBytes(),
				bothCallers.getBytes()));
	}

	@Test
	public void aMalformedAgreementKeyIsRefused() {
		KeyPair caller = voice.generateCallAgreementKeyPair();
		try {
			voice.deriveCallSecret(key(7), caller, new byte[31], true, "c");
			fail();
		} catch (java.security.GeneralSecurityException expected) {
		}
		try {
			voice.deriveCallSecret(key(7), caller, new byte[32], true, "c");
			fail("an all-zero agreement must be refused");
		} catch (java.security.GeneralSecurityException expected) {
		}
	}

	@Test
	public void aFrameOpensOnlyUnderItsOwnSequenceNumberAndDirection() {
		SecretKey tx = key(9);
		byte[] pcm = new byte[640];
		Arrays.fill(pcm, (byte) 3);
		byte[] sealed = voice.encryptAudioFrame(pcm, tx,
				voice.audioFrameAssociatedData(true, 41));
		assertArrayEquals(pcm, voice.decryptAudioFrame(sealed, tx,
				voice.audioFrameAssociatedData(true, 41)));
		assertRefused(sealed, tx, voice.audioFrameAssociatedData(true, 42));
		assertRefused(sealed, tx, voice.audioFrameAssociatedData(true, 40));
		assertRefused(sealed, tx, voice.audioFrameAssociatedData(false, 41));
	}

	@Test
	public void framesOfTheTwoKeySchedulesNeverOpenAsEachOther() {
		SecretKey tx = key(9);
		byte[] pcm = new byte[640];
		byte[] withAad = voice.encryptAudioFrame(pcm, tx,
				voice.audioFrameAssociatedData(true, 1));
		try {
			voice.decryptAudioFrame(withAad, tx);
			fail("a frame with associated data opened without it");
		} catch (RuntimeException expected) {
		}
		byte[] withoutAad = voice.encryptAudioFrame(pcm, tx);
		assertRefused(withoutAad, tx, voice.audioFrameAssociatedData(true, 1));
	}

	private void assertRefused(byte[] sealed, SecretKey key, byte[] aad) {
		try {
			voice.decryptAudioFrame(sealed, key, aad);
			fail("a frame opened under the wrong associated data");
		} catch (RuntimeException expected) {
		}
	}
}
