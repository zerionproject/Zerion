package org.zerionproject.core.contact;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.zerionproject.core.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.zerionproject.core.api.contact.B3Constants.B3_SESSION_ID_LEN;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.contact.ContactExchangeConstants.EXCHANGE_TAG_LABEL;
import static org.zerionproject.core.contact.ContactExchangeConstants.EXCHANGE_TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContactExchangeTagTest {

	private final Random random = new Random();

	private CryptoComponent crypto;
	private ContactExchangeCryptoImpl exchangeCrypto;
	private SecretKey exchangeKey, otherKey;
	private byte[] author, mlDsaPub, props, staticPq, sessionId;
	private long timestamp;

	@Before
	public void setUp() throws Exception {
		Class<?> cryptoImpl = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> cc = cryptoImpl.getDeclaredConstructor(
				Class.forName(
						"org.zerionproject.core.api.system.SecureRandomProvider"),
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		cc.setAccessible(true);
		crypto = (CryptoComponent) cc.newInstance(
				new TestSecureRandomProvider(), null);
		exchangeCrypto = new ContactExchangeCryptoImpl(crypto);
		exchangeKey = new SecretKey(randomBytes(SecretKey.LENGTH));
		otherKey = new SecretKey(randomBytes(SecretKey.LENGTH));
		author = randomBytes(40);
		mlDsaPub = randomBytes(ML_DSA_65_PUBLIC_KEY_BYTES);
		props = randomBytes(70);
		staticPq = randomBytes(B3_PQ_PUB_LEN);
		sessionId = randomBytes(B3_SESSION_ID_LEN);
		timestamp = 1_700_000_000_000L + random.nextInt(1_000_000);
	}

	private ExchangeTranscript transcript() {
		return new ExchangeTranscript(author, mlDsaPub, props, timestamp,
				staticPq, sessionId);
	}

	@Test
	public void testTagIsAMacOverTheLengthPrefixedTranscript() {
		byte[] tag = exchangeCrypto.exchangeTag(exchangeKey, true,
				transcript());
		assertEquals(EXCHANGE_TAG_LENGTH, tag.length);
		byte[] time = ByteBuffer.allocate(8).putLong(timestamp).array();
		assertArrayEquals(crypto.mac(EXCHANGE_TAG_LABEL, exchangeKey,
				new byte[] {0x01}, author, mlDsaPub, props, time, staticPq,
				sessionId), tag);
		assertArrayEquals(crypto.mac(EXCHANGE_TAG_LABEL, exchangeKey,
				new byte[] {0x02}, author, mlDsaPub, props, time, staticPq,
				sessionId), exchangeCrypto.exchangeTag(exchangeKey, false,
				transcript()));
	}

	@Test
	public void testBothSidesComputeTheSameTag() {
		byte[] sent = exchangeCrypto.exchangeTag(exchangeKey, false,
				transcript());
		ContactExchangeCryptoImpl receiver =
				new ContactExchangeCryptoImpl(crypto);
		byte[] recomputed = receiver.exchangeTag(
				new SecretKey(exchangeKey.getBytes().clone()), false,
				new ExchangeTranscript(author.clone(), mlDsaPub.clone(),
						props.clone(), timestamp, staticPq.clone(),
						sessionId.clone()));
		assertArrayEquals(sent, recomputed);
		assertTrue(receiver.verifyExchangeTag(sent, exchangeKey, false,
				transcript()));
	}

	@Test
	public void testTagChangesWithEveryField() {
		byte[] tag = exchangeCrypto.exchangeTag(exchangeKey, true,
				transcript());
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, otherKey, true,
				transcript()));
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, false,
				transcript()));
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, true,
				new ExchangeTranscript(flip(author), mlDsaPub, props,
						timestamp, staticPq, sessionId)));
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, true,
				new ExchangeTranscript(author, flip(mlDsaPub), props,
						timestamp, staticPq, sessionId)));
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, true,
				new ExchangeTranscript(author, mlDsaPub, flip(props),
						timestamp, staticPq, sessionId)));
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, true,
				new ExchangeTranscript(author, mlDsaPub, props,
						timestamp + 1, staticPq, sessionId)));
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, true,
				new ExchangeTranscript(author, mlDsaPub, props, timestamp,
						flip(staticPq), sessionId)));
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, true,
				new ExchangeTranscript(author, mlDsaPub, props, timestamp,
						staticPq, flip(sessionId))));
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, true,
				new ExchangeTranscript(author, mlDsaPub, props, timestamp,
						null, null)));
	}

	@Test
	public void testFieldBoundariesAreFixedByTheLengthPrefixes() {
		byte[] shifted = Arrays.copyOf(author, author.length + 1);
		shifted[author.length] = mlDsaPub[0];
		byte[] rest = Arrays.copyOfRange(mlDsaPub, 1, mlDsaPub.length);
		byte[] tag = exchangeCrypto.exchangeTag(exchangeKey, true,
				transcript());
		assertFalse(exchangeCrypto.verifyExchangeTag(tag, exchangeKey, true,
				new ExchangeTranscript(shifted, rest, props, timestamp,
						staticPq, sessionId)));
	}

	@Test
	public void testWithoutStaticKeyTheOptionalFieldsAreEmpty() {
		ExchangeTranscript nearby = new ExchangeTranscript(author, mlDsaPub,
				props, timestamp, null, null);
		byte[] time = ByteBuffer.allocate(8).putLong(timestamp).array();
		assertArrayEquals(crypto.mac(EXCHANGE_TAG_LABEL, exchangeKey,
				new byte[] {0x02}, author, mlDsaPub, props, time, new byte[0],
				new byte[0]), exchangeCrypto.exchangeTag(exchangeKey, false,
				nearby));
	}

	@Test
	public void testTagOfAnotherLengthIsRefused() {
		byte[] tag = exchangeCrypto.exchangeTag(exchangeKey, true,
				transcript());
		assertFalse(exchangeCrypto.verifyExchangeTag(
				Arrays.copyOf(tag, tag.length - 1), exchangeKey, true,
				transcript()));
		assertFalse(exchangeCrypto.verifyExchangeTag(
				Arrays.copyOf(tag, tag.length + 1), exchangeKey, true,
				transcript()));
		assertFalse(exchangeCrypto.verifyExchangeTag(flip(tag), exchangeKey,
				true, transcript()));
	}

	private static byte[] flip(byte[] in) {
		byte[] out = in.clone();
		out[out.length - 1] ^= 0x01;
		return out;
	}

	private byte[] randomBytes(int len) {
		byte[] b = new byte[len];
		random.nextBytes(b);
		return b;
	}
}
