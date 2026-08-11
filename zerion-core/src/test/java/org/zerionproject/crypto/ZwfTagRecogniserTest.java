package org.zerionproject.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class ZwfTagRecogniserTest {

	private CryptoComponent crypto;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImplClass = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> constructor = cryptoImplClass.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName(
						"org.zerionproject.core.crypto.PasswordBasedKdf"));
		constructor.setAccessible(true);
		crypto = (CryptoComponent) constructor.newInstance(
				new TestSecureRandomProvider(), null);
	}

	private SecretKey randomKey() {
		byte[] k = new byte[SecretKey.LENGTH];
		crypto.getSecureRandom().nextBytes(k);
		return new SecretKey(k);
	}

	@Test
	public void recognisesTagsInWindow() {
		SecretKey keyA = randomKey();
		ZwfTagRecogniser r = new ZwfTagRecogniser(crypto, 8);
		r.register(1, keyA, 0);

		// streamId 1..8 are in the window; 0 and 9 are not
		for (long s = 1; s <= 8; s++) {
			ZwfTagRecogniser.Match m =
					r.recognise(ZwfTag.computeTag(crypto, keyA, s));
			assertNotNull("streamId " + s, m);
			assertEquals(1, m.contactId);
			assertEquals(s, m.streamId);
		}
		assertNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 9)));
	}

	@Test
	public void windowIsSymmetricAndSlides() {
		SecretKey keyA = randomKey();
		ZwfTagRecogniser r = new ZwfTagRecogniser(crypto, 8);
		r.register(1, keyA, 0); // window [1, 8]
		assertNotNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 1)));
		assertNotNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 8)));
		assertNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 9)));

		r.advanceTo(1, 20); // window [13, 28]
		assertNull("id below the window no longer recognised",
				r.recognise(ZwfTag.computeTag(crypto, keyA, 1)));
		assertNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 12)));
		// a reordered id below the high-water but inside the window IS recognised
		assertNotNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 13)));
		assertNotNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 20)));
		assertNotNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 28)));
		assertNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 29)));
	}

	@Test
	public void differentContactsDoNotCollide() {
		SecretKey keyA = randomKey();
		SecretKey keyB = randomKey();
		ZwfTagRecogniser r = new ZwfTagRecogniser(crypto, 8);
		r.register(1, keyA, 0);
		r.register(2, keyB, 0);

		ZwfTagRecogniser.Match a = r.recognise(ZwfTag.computeTag(crypto, keyA, 3));
		assertNotNull(a);
		assertEquals(1, a.contactId);
		assertEquals(3, a.streamId);

		ZwfTagRecogniser.Match b = r.recognise(ZwfTag.computeTag(crypto, keyB, 3));
		assertNotNull(b);
		assertEquals(2, b.contactId);
		assertEquals(3, b.streamId);

		// contact A's tag key never resolves to contact B
		assertEquals(1, r.recognise(ZwfTag.computeTag(crypto, keyA, 7)).contactId);
	}

	@Test
	public void unknownTagReturnsNull() {
		SecretKey keyA = randomKey();
		ZwfTagRecogniser r = new ZwfTagRecogniser(crypto, 8);
		r.register(1, keyA, 0);
		assertNull(r.recognise(ZwfTag.computeTag(crypto, randomKey(), 1)));
		byte[] rubbish = new byte[16];
		crypto.getSecureRandom().nextBytes(rubbish);
		assertNull(r.recognise(rubbish));
	}

	@Test
	public void removedContactIsNotRecognised() {
		SecretKey keyA = randomKey();
		ZwfTagRecogniser r = new ZwfTagRecogniser(crypto, 8);
		r.register(1, keyA, 0);
		assertNotNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 1)));
		r.remove(1);
		assertNull(r.recognise(ZwfTag.computeTag(crypto, keyA, 1)));
	}
}
