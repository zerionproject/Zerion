package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * A voice memo opens only with the key derived from the pairing secret and
 * only under the identity of the message it was recorded for. Nothing in the
 * payload opens it, a replay under another timestamp fails, a memo reflected
 * back to its sender fails, and a memo moved to another conversation fails.
 */
public class VoiceMemoCryptoTest {

	private final SecureRandom rnd = new SecureRandom();
	private final byte[] groupId = random(32);
	private final byte[] alice = random(32);
	private final byte[] bob = random(32);
	private final byte[] audio = random(9_000);
	private final long timestamp = 1_700_000_000_000L;
	private final int durationMs = 1_125;
	private final FakeKeys keys = new FakeKeys(random(32), alice, bob);

	/** Derives wrap keys from a pairing secret the way both peers would. */
	private static final class FakeKeys implements VoiceMemoKeys {
		private final byte[] secret;
		private final byte[] local;
		private final byte[] remote;

		FakeKeys(byte[] secret, byte[] local, byte[] remote) {
			this.secret = secret;
			this.local = local;
			this.remote = remote;
		}

		@Override
		public byte[] deriveWrapKey(byte[] salt) throws Exception {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(salt);
		}

		@Override
		public byte[] localAuthorId() {
			return local;
		}

		@Override
		public byte[] remoteAuthorId() {
			return remote;
		}

		@Override
		public long nextOutgoingTimestamp() {
			return 0;
		}
	}

	/** What an attacker holding only the payload can do: use its bytes as the key. */
	private static final class PayloadOnlyKeys implements VoiceMemoKeys {
		@Override
		public byte[] deriveWrapKey(byte[] salt) {
			return salt;
		}

		@Override
		public byte[] localAuthorId() {
			throw new AssertionError();
		}

		@Override
		public byte[] remoteAuthorId() {
			throw new AssertionError();
		}

		@Override
		public long nextOutgoingTimestamp() {
			throw new AssertionError();
		}
	}

	@Test
	public void memoOpensWithTheDerivedKeyUnderItsOwnIdentity()
			throws Exception {
		byte[] payload = seal(keys, groupId, timestamp, alice, bob);
		assertEquals(VoiceMemoCrypto.FORMAT_VERSION, payload[0]);
		assertArrayEquals(audio, open(payload, keys, groupId, timestamp,
				alice, bob));
	}

	@Test
	public void memoDoesNotOpenFromItsOwnBytes() throws Exception {
		byte[] payload = seal(keys, groupId, timestamp, alice, bob);
		assertRefused(() -> open(payload, new PayloadOnlyKeys(), groupId,
				timestamp, alice, bob));
	}

	@Test
	public void otherPairingSecretIsRefused() throws Exception {
		byte[] payload = seal(keys, groupId, timestamp, alice, bob);
		FakeKeys other = new FakeKeys(random(32), alice, bob);
		assertRefused(() -> open(payload, other, groupId, timestamp, alice,
				bob));
	}

	@Test
	public void replayUnderAnotherTimestampIsRefused() throws Exception {
		byte[] payload = seal(keys, groupId, timestamp, alice, bob);
		assertRefused(() -> open(payload, keys, groupId, timestamp + 1,
				alice, bob));
	}

	@Test
	public void reflectedMemoIsRefused() throws Exception {
		byte[] payload = seal(keys, groupId, timestamp, alice, bob);
		assertRefused(() -> open(payload, keys, groupId, timestamp, bob,
				alice));
	}

	@Test
	public void memoMovedToAnotherConversationIsRefused() throws Exception {
		byte[] payload = seal(keys, groupId, timestamp, alice, bob);
		assertRefused(() -> open(payload, keys, random(32), timestamp, alice,
				bob));
	}

	@Test
	public void tamperedSaltIsRefused() throws Exception {
		byte[] payload = seal(keys, groupId, timestamp, alice, bob);
		payload[1 + 12] ^= 1;
		assertRefused(() -> open(payload, keys, groupId, timestamp, alice,
				bob));
	}

	@Test
	public void parserAcceptsOnlyTheTwoKnownVersions() throws Exception {
		byte[] payload = seal(keys, groupId, timestamp, alice, bob);
		payload[0] = 3;
		try {
			VoiceMessagePayloadParser.parse(payload);
			fail();
		} catch (IllegalArgumentException expected) {
		}
		payload[0] = 1;
		VoiceMessagePayloadParser.parse(payload);
	}

	/**
	 * A stored memo from before the format change carries its wrap key in
	 * the payload and binds no message identity; it is still opened for
	 * playback of history, without consulting the pairing secret.
	 */
	@Test
	public void storedLegacyMemoStillOpensForHistory() throws Exception {
		StreamingAudioEncryptor encryptor = new StreamingAudioEncryptor();
		encryptor.setAADContext(new byte[] {1}, groupId, new byte[0]);
		byte[] iv = encryptor.getIV();
		byte[] clearWrapKey = random(32);
		byte[] sealed = encryptor.getEncryptedKey(
				new SecretKeySpec(clearWrapKey, "AES"));
		byte[] payload = build(encryptor, iv,
				VoiceMemoCrypto.wrappedKeyField(clearWrapKey, sealed));
		payload[0] = 1;
		assertArrayEquals(audio, open(payload, new PayloadOnlyKeys(), groupId,
				0, new byte[32], new byte[32]));
	}

	private byte[] seal(VoiceMemoKeys k, byte[] group, long ts, byte[] sender,
			byte[] recipient) throws Exception {
		StreamingAudioEncryptor encryptor = new StreamingAudioEncryptor();
		encryptor.setAADContext(new byte[] {VoiceMemoCrypto.FORMAT_VERSION},
				group, VoiceMemoCrypto.messageBinding(ts, sender, recipient));
		byte[] iv = encryptor.getIV();
		byte[] salt = VoiceMemoCrypto.newSalt(rnd);
		byte[] sealed = encryptor.getEncryptedKey(
				new SecretKeySpec(k.deriveWrapKey(salt), "AES"));
		return build(encryptor, iv, VoiceMemoCrypto.wrappedKeyField(salt,
				sealed));
	}

	private byte[] build(StreamingAudioEncryptor encryptor, byte[] iv,
			byte[] wrappedKey) throws Exception {
		List<byte[]> chunks = new ArrayList<>();
		List<byte[]> tags = new ArrayList<>();
		for (int off = 0; off < audio.length; off += 4096) {
			int len = Math.min(4096, audio.length - off);
			StreamingAudioEncryptor.EncryptedChunk c = encryptor.encryptChunk(
					Arrays.copyOfRange(audio, off, off + len), len);
			chunks.add(c.ciphertext);
			tags.add(c.tag);
		}
		byte[] mac = encryptor.computeGlobalMAC(chunks.size(), durationMs);
		return VoiceMessagePayloadBuilder.build(iv, wrappedKey, chunks, tags,
				durationMs, mac);
	}

	private static byte[] open(byte[] payload, VoiceMemoKeys k, byte[] group,
			long ts, byte[] sender, byte[] recipient) throws Exception {
		return StreamingAudioDecryptor.decryptAll(
				VoiceMessagePayloadParser.parse(payload), group, ts, sender,
				recipient, k);
	}

	private static void assertRefused(Callable<byte[]> open) {
		try {
			open.call();
		} catch (Exception expected) {
			return;
		}
		fail("memo opened although it should have been refused");
	}

	private byte[] random(int n) {
		byte[] b = new byte[n];
		rnd.nextBytes(b);
		return b;
	}
}
