package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.jmock.lib.concurrent.Synchroniser;
import org.hamcrest.Description;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every video session must run under keys that no other session of the
 * same call can share. The keys are derived from the call key and one
 * fresh random contribution from each peer, so two sessions differ as soon
 * as either contribution differs, and both peers derive mirrored transmit
 * and receive keys from the same pair of contributions.
 */
public class VideoSessionKeyDerivationTest {

	private final Mockery context = new Mockery() {{
		setThreadingPolicy(new Synchroniser());
	}};
	private final CryptoComponent crypto = context.mock(CryptoComponent.class);
	private VoiceCallCryptoImpl impl;
	private final SecretKey callKey = new SecretKey(random(32, 1));

	private static byte[] random(int len, long seed) {
		byte[] b = new byte[len];
		new SecureRandom(new byte[] {(byte) seed, (byte) (seed >> 8)})
				.nextBytes(b);
		b[0] = (byte) seed;
		return b;
	}

	/**
	 * Keyed-hash key derivation over the label and every input, standing
	 * in for the production BLAKE2b derivation.
	 */
	private static final class HmacDerive implements Action {
		@Override
		public Object invoke(Invocation invocation) throws Throwable {
			String label = (String) invocation.getParameter(0);
			SecretKey key = (SecretKey) invocation.getParameter(1);
			byte[][] inputs = (byte[][]) invocation.getParameter(2);
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key.getBytes(), "HmacSHA256"));
			mac.update(label.getBytes("UTF-8"));
			for (byte[] in : inputs) {
				mac.update((byte) in.length);
				mac.update(in);
			}
			return new SecretKey(mac.doFinal());
		}

		@Override
		public void describeTo(Description description) {
			description.appendText("derives a key with HMAC");
		}
	}

	private void expectDerivation() {
		context.checking(new Expectations() {{
			allowing(crypto).getSecureRandom();
			will(returnValue(new SecureRandom()));
			allowing(crypto).deriveKey(with(any(String.class)),
					with(any(SecretKey.class)), with(any(byte[][].class)));
			will(new HmacDerive());
		}});
		impl = new VoiceCallCryptoImpl(crypto);
	}

	@Test
	public void testPeersDeriveMirroredKeysFromTheSameContributions() {
		expectDerivation();
		byte[] aliceNonce = random(16, 2), bobNonce = random(16, 3);
		VoiceCallCrypto.VideoKeys alice =
				impl.deriveEphemeralVideoKeys(callKey, aliceNonce, bobNonce, true);
		VoiceCallCrypto.VideoKeys bob =
				impl.deriveEphemeralVideoKeys(callKey, bobNonce, aliceNonce, false);
		assertArrayEquals(alice.txKey.getBytes(), bob.rxKey.getBytes());
		assertArrayEquals(alice.rxKey.getBytes(), bob.txKey.getBytes());
		assertFalse(Arrays.equals(alice.txKey.getBytes(),
				alice.rxKey.getBytes()));
	}

	@Test
	public void testAnyChangedContributionChangesEveryKey() {
		expectDerivation();
		byte[] a = random(16, 2), b = random(16, 3);
		VoiceCallCrypto.VideoKeys base =
				impl.deriveEphemeralVideoKeys(callKey, a, b, true);
		VoiceCallCrypto.VideoKeys localChanged =
				impl.deriveEphemeralVideoKeys(callKey, random(16, 4), b, true);
		VoiceCallCrypto.VideoKeys remoteChanged =
				impl.deriveEphemeralVideoKeys(callKey, a, random(16, 5), true);
		for (VoiceCallCrypto.VideoKeys other : new VoiceCallCrypto.VideoKeys[] {
				localChanged, remoteChanged}) {
			assertFalse(Arrays.equals(base.txKey.getBytes(),
					other.txKey.getBytes()));
			assertFalse(Arrays.equals(base.rxKey.getBytes(),
					other.rxKey.getBytes()));
		}
	}

	@Test
	public void testRepeatedSessionsOfOneCallNeverShareAKey() {
		expectDerivation();
		Set<String> keys = new HashSet<>();
		for (int session = 0; session < 500; session++) {
			byte[] a = random(16, 100 + session), b = random(16, 5000 + session);
			VoiceCallCrypto.VideoKeys k =
					impl.deriveEphemeralVideoKeys(callKey, a, b, true);
			assertTrue(keys.add(Arrays.toString(k.txKey.getBytes())));
			assertTrue(keys.add(Arrays.toString(k.rxKey.getBytes())));
		}
	}

	@Test
	public void testSessionKeysDependOnTheCallKey() {
		expectDerivation();
		byte[] a = random(16, 2), b = random(16, 3);
		VoiceCallCrypto.VideoKeys one =
				impl.deriveEphemeralVideoKeys(callKey, a, b, true);
		VoiceCallCrypto.VideoKeys two = impl.deriveEphemeralVideoKeys(
				new SecretKey(random(32, 9)), a, b, true);
		assertFalse(Arrays.equals(one.txKey.getBytes(), two.txKey.getBytes()));
	}

	@Test
	public void testDerivedKeysSurviveDerivationAndAreNotAllZero() {
		expectDerivation();
		byte[] zero = new byte[32];
		VoiceCallCrypto.VideoKeys video = impl.deriveEphemeralVideoKeys(
				callKey, random(16, 2), random(16, 3), true);
		assertFalse(Arrays.equals(zero, video.txKey.getBytes()));
		assertFalse(Arrays.equals(zero, video.rxKey.getBytes()));
		VoiceCallCrypto.AudioKeys audio = impl.deriveAudioKeys(callKey, true);
		assertFalse(Arrays.equals(zero, audio.txKey.getBytes()));
		assertFalse(Arrays.equals(zero, audio.rxKey.getBytes()));
		VoiceCallCrypto.AudioKeys ephemeralAudio = impl.deriveEphemeralAudioKeys(
				callKey, random(16, 6), random(16, 7), false);
		assertFalse(Arrays.equals(zero, ephemeralAudio.txKey.getBytes()));
		assertFalse(Arrays.equals(zero, ephemeralAudio.rxKey.getBytes()));
	}

	@Test
	public void testAudioKeysAreMirroredBetweenPeers() {
		expectDerivation();
		byte[] a = random(16, 2), b = random(16, 3);
		VoiceCallCrypto.AudioKeys alice =
				impl.deriveEphemeralAudioKeys(callKey, a, b, true);
		VoiceCallCrypto.AudioKeys bob =
				impl.deriveEphemeralAudioKeys(callKey, b, a, false);
		assertArrayEquals(alice.txKey.getBytes(), bob.rxKey.getBytes());
		assertArrayEquals(alice.rxKey.getBytes(), bob.txKey.getBytes());
		VoiceCallCrypto.AudioKeys alice2 = impl.deriveAudioKeys(callKey, true);
		VoiceCallCrypto.AudioKeys bob2 = impl.deriveAudioKeys(callKey, false);
		assertArrayEquals(alice2.txKey.getBytes(), bob2.rxKey.getBytes());
		assertArrayEquals(alice2.rxKey.getBytes(), bob2.txKey.getBytes());
	}
}
