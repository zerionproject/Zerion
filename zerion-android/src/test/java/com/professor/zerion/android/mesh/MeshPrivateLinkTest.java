package com.professor.zerion.android.mesh;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MeshPrivateLinkTest {

	private static final long EPOCH = 2_900_000L;

	private final SecureRandom random = new SecureRandom();

	@Test
	public void anAdvertIsRecognisedForAboutHalfAnHourAndNotAfter() {
		byte[] key = bytes(32);
		UUID advert = MeshPrivateLink.advert(key, random, EPOCH, 0x85);

		for (long e = EPOCH - 3; e <= EPOCH + 3; e++) {
			assertTrue(MeshPrivateLink.recognises(key, advert, e));
		}
		assertFalse("a recorded advert never expires",
				MeshPrivateLink.recognises(key, advert, EPOCH + 4));
		assertFalse(MeshPrivateLink.recognises(key, advert, EPOCH - 4));
	}

	@Test
	public void onlyAHolderOfTheKeyReadsTheChannelFromTheAdvert() {
		byte[] key = bytes(32);
		java.util.Set<Long> lastBytes = new HashSet<>();
		for (int channel : new int[] {0x80, 0x85, 0xFF}) {
			for (int i = 0; i < 40; i++) {
				UUID advert = MeshPrivateLink.advert(key, random, EPOCH,
						channel);
				assertEquals(channel, MeshPrivateLink.channelOf(key, advert));
				assertTrue(MeshPrivateLink.recognises(key, advert, EPOCH));
				assertTrue(MeshPrivateLink.looksRandom(advert));
				lastBytes.add(advert.getMostSignificantBits() & 0xFFL);
			}
		}
		assertTrue("the channel shows in the advert",
				lastBytes.size() > 40);
	}

	@Test
	public void onlyAHolderOfTheKeyRecognisesTheAdvert() {
		byte[] key = bytes(32);
		byte[] other = bytes(32);

		UUID advert = MeshPrivateLink.advert(key, random, EPOCH, 0x85);

		assertTrue(MeshPrivateLink.recognises(key, advert, EPOCH));
		assertFalse(MeshPrivateLink.recognises(other, advert, EPOCH));
	}

	@Test
	public void everyAdvertIsNewAndLooksLikeARandomUuid() {
		byte[] key = bytes(32);
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			UUID advert = MeshPrivateLink.advert(key, random, EPOCH, 0x85);
			assertEquals(4, advert.version());
			assertEquals(2, advert.variant());
			assertTrue(MeshPrivateLink.looksRandom(advert));
			assertTrue(MeshPrivateLink.recognises(key, advert, EPOCH));
			assertTrue("an advert was repeated", seen.add(advert));
		}
	}

	@Test
	public void anAdvertChangedInAnyHalfIsNotRecognised() {
		byte[] key = bytes(32);
		UUID advert = MeshPrivateLink.advert(key, random, EPOCH, 0x85);

		assertFalse(MeshPrivateLink.recognises(key, new UUID(
				advert.getMostSignificantBits() ^ 1L,
				advert.getLeastSignificantBits()), EPOCH));
		assertFalse(MeshPrivateLink.recognises(key, new UUID(
				advert.getMostSignificantBits(),
				advert.getLeastSignificantBits() ^ 1L), EPOCH));
	}

	@Test
	public void aUuidOfAnotherKindIsNeverRecognised() {
		byte[] key = bytes(32);
		UUID fixed = UUID.fromString("9f2a7c14-6b38-1d0e-8a51-c3e7d924b6f8");

		assertFalse(MeshPrivateLink.looksRandom(fixed));
		assertFalse(MeshPrivateLink.recognises(key, fixed, EPOCH));
	}

	@Test
	public void theFilterBitsMatchEveryRandomUuid() {
		for (int i = 0; i < 50; i++) {
			assertTrue(MeshPrivateLink.looksRandom(UUID.randomUUID()));
		}
	}

	@Test
	public void bothSidesDeriveTheSameLinkKeyAndOthersDoNot() {
		byte[] mine = bytes(32);
		byte[] theirs = bytes(32);

		assertArrayEquals(MeshPrivateLink.linkKey(mine, theirs),
				MeshPrivateLink.linkKey(theirs, mine));
		assertFalse(java.util.Arrays.equals(
				MeshPrivateLink.linkKey(mine, theirs),
				MeshPrivateLink.linkKey(mine, bytes(32))));
	}

	@Test
	public void theHandshakeCompletesBetweenTwoHoldersOfTheLinkKey() {
		byte[] linkKey = bytes(32);
		UUID answererAdvert = MeshPrivateLink.advert(bytes(32), random, EPOCH, 0x85);
		byte[] dialerNonce = bytes(16);
		byte[] answererNonce = bytes(16);

		byte[] hello = MeshPrivateLink.hello(linkKey, answererAdvert,
				dialerNonce);
		assertTrue(MeshPrivateLink.isHandshake(hello));
		assertTrue(MeshPrivateLink.helloMatches(hello, linkKey,
				answererAdvert));
		assertArrayEquals(dialerNonce, MeshPrivateLink.helloNonce(hello));

		byte[] reply = MeshPrivateLink.reply(linkKey, dialerNonce,
				answererNonce);
		assertArrayEquals(answererNonce,
				MeshPrivateLink.replyNonce(reply, linkKey, dialerNonce));

		byte[] confirm = MeshPrivateLink.confirm(linkKey, dialerNonce,
				answererNonce);
		assertTrue(MeshPrivateLink.confirmMatches(confirm, linkKey,
				dialerNonce, answererNonce));
	}

	@Test
	public void aHelloForAnEarlierAdvertIsRefused() {
		byte[] linkKey = bytes(32);
		byte[] advertKey = bytes(32);
		UUID earlier = MeshPrivateLink.advert(advertKey, random, EPOCH, 0x85);
		UUID current = MeshPrivateLink.advert(advertKey, random, EPOCH, 0x85);

		byte[] captured = MeshPrivateLink.hello(linkKey, earlier, bytes(16));

		assertFalse("a hello recorded under an earlier advert is answered",
				MeshPrivateLink.helloMatches(captured, linkKey, current));
	}

	@Test
	public void aHelloUnderAnotherLinkKeyIsRefused() {
		UUID advert = MeshPrivateLink.advert(bytes(32), random, EPOCH, 0x85);
		byte[] hello = MeshPrivateLink.hello(bytes(32), advert, bytes(16));

		assertFalse(MeshPrivateLink.helloMatches(hello, bytes(32), advert));
	}

	@Test
	public void aReplyOrConfirmFromAnotherSessionIsRefused() {
		byte[] linkKey = bytes(32);
		byte[] dialerNonce = bytes(16);
		byte[] answererNonce = bytes(16);
		byte[] reply = MeshPrivateLink.reply(linkKey, dialerNonce,
				answererNonce);
		byte[] confirm = MeshPrivateLink.confirm(linkKey, dialerNonce,
				answererNonce);

		assertNull(MeshPrivateLink.replyNonce(reply, linkKey, bytes(16)));
		assertNull(MeshPrivateLink.replyNonce(reply, bytes(32),
				dialerNonce));
		assertFalse(MeshPrivateLink.confirmMatches(confirm, linkKey,
				dialerNonce, bytes(16)));
		assertFalse(MeshPrivateLink.confirmMatches(confirm, linkKey,
				bytes(16), answererNonce));
	}

	@Test
	public void aReplyIsNotTakenForAConfirmOrAHello() {
		byte[] linkKey = bytes(32);
		byte[] dialerNonce = bytes(16);
		byte[] answererNonce = bytes(16);
		byte[] reply = MeshPrivateLink.reply(linkKey, dialerNonce,
				answererNonce);

		assertNull(MeshPrivateLink.helloNonce(reply));
		assertFalse(MeshPrivateLink.confirmMatches(reply, linkKey,
				dialerNonce, answererNonce));
		assertNotNull(MeshPrivateLink.replyNonce(reply, linkKey,
				dialerNonce));
	}

	@Test
	public void framesOfTheWrongLengthAreRefused() {
		byte[] linkKey = bytes(32);
		UUID advert = MeshPrivateLink.advert(bytes(32), random, EPOCH, 0x85);
		byte[] hello = MeshPrivateLink.hello(linkKey, advert, bytes(16));
		byte[] shorter = java.util.Arrays.copyOf(hello, hello.length - 1);
		byte[] longer = java.util.Arrays.copyOf(hello, hello.length + 1);

		assertFalse(MeshPrivateLink.helloMatches(shorter, linkKey, advert));
		assertFalse(MeshPrivateLink.helloMatches(longer, linkKey, advert));
		assertFalse(MeshPrivateLink.isHandshake(new byte[] {0x7F}));
		assertFalse(MeshPrivateLink.isHandshake(new byte[] {0x7F, 0x01}));
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}
}
