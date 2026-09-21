package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.KeyAgreementCrypto;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;
import org.zerionproject.core.api.keyagreement.Payload;
import org.zerionproject.core.api.keyagreement.PayloadEncoder;
import org.zerionproject.core.api.plugin.LanTcpConstants;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.test.TestDuplexTransportConnection;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

import static java.util.Collections.emptyList;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.PROTOCOL_VERSION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Runs the nearby pairing protocol between two real parties over piped
 * connections with the production crypto. Both derive the same master key
 * from hybrid keys, the key depends on an ML-KEM secret that only the
 * decapsulating party can recover, a wrong X25519 half aborts on either
 * side, and a QR payload of the classical protocol version is refused.
 */
public class KeyAgreementProtocolEndToEndTest {

	private CryptoComponent crypto;
	private KeyAgreementCrypto keyAgreementCrypto;
	private PayloadEncoder payloadEncoder;
	private PayloadParserImpl payloadParser;
	private RecordReaderFactory recordReaderFactory;
	private RecordWriterFactory recordWriterFactory;

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
		Constructor<?> kc = Class.forName(
				"org.zerionproject.core.crypto.KeyAgreementCryptoImpl")
				.getDeclaredConstructor(CryptoComponent.class);
		kc.setAccessible(true);
		keyAgreementCrypto = (KeyAgreementCrypto) kc.newInstance(crypto);
		BdfWriterFactory bdfWriterFactory = (BdfWriterFactory) newInstance(
				"org.zerionproject.core.data.BdfWriterFactoryImpl");
		BdfReaderFactory bdfReaderFactory = (BdfReaderFactory) newInstance(
				"org.zerionproject.core.data.BdfReaderFactoryImpl");
		payloadEncoder = new PayloadEncoderImpl(bdfWriterFactory);
		payloadParser = new PayloadParserImpl(bdfReaderFactory);
		recordReaderFactory = (RecordReaderFactory) newInstance(
				"org.zerionproject.core.record.RecordReaderFactoryImpl");
		recordWriterFactory = (RecordWriterFactory) newInstance(
				"org.zerionproject.core.record.RecordWriterFactoryImpl");
	}

	private static Object newInstance(String className) throws Exception {
		Constructor<?> c = Class.forName(className).getDeclaredConstructor();
		c.setAccessible(true);
		return c.newInstance();
	}

	private static final KeyAgreementProtocol.Callbacks NO_CALLBACKS =
			new KeyAgreementProtocol.Callbacks() {
				@Override
				public void connectionWaiting() {
				}

				@Override
				public void initialRecordReceived() {
				}
			};

	private static final class Outcome {
		@Nullable
		volatile SecretKey aliceKey, bobKey;
		@Nullable
		volatile Throwable aliceError, bobError;
	}

	private Payload payloadFor(KeyPair keys) {
		return new Payload(
				keyAgreementCrypto.deriveKeyCommitment(keys.getPublic()),
				emptyList());
	}

	private Outcome run(KeyPair aliceKeys, KeyPair aliceUsed, KeyPair bobKeys,
			KeyPair bobUsed) throws Exception {
		Payload alicePayload = payloadFor(aliceKeys);
		Payload bobPayload = payloadFor(bobKeys);
		boolean aliceIsAlice = alicePayload.compareTo(bobPayload) < 0;
		TestDuplexTransportConnection[] pair =
				TestDuplexTransportConnection.createPair();
		KeyAgreementTransport aliceTransport = new KeyAgreementTransport(
				recordReaderFactory, recordWriterFactory,
				new KeyAgreementConnection(pair[0], LanTcpConstants.ID));
		KeyAgreementTransport bobTransport = new KeyAgreementTransport(
				recordReaderFactory, recordWriterFactory,
				new KeyAgreementConnection(pair[1], LanTcpConstants.ID));
		KeyAgreementProtocol alice = new KeyAgreementProtocol(NO_CALLBACKS,
				crypto, keyAgreementCrypto, payloadEncoder, aliceTransport,
				bobPayload, alicePayload, aliceUsed, aliceIsAlice);
		KeyAgreementProtocol bob = new KeyAgreementProtocol(NO_CALLBACKS,
				crypto, keyAgreementCrypto, payloadEncoder, bobTransport,
				alicePayload, bobPayload, bobUsed, !aliceIsAlice);
		Outcome o = new Outcome();
		Thread ta = new Thread(() -> {
			try {
				o.aliceKey = alice.perform();
			} catch (Throwable t) {
				o.aliceError = t;
			}
		});
		Thread tb = new Thread(() -> {
			try {
				o.bobKey = bob.perform();
			} catch (Throwable t) {
				o.bobError = t;
			}
		});
		ta.start();
		tb.start();
		ta.join(60_000);
		tb.join(60_000);
		assertTrue(!ta.isAlive() && !tb.isAlive());
		return o;
	}

	private static KeyPair withoutMlKemPrivateKey(KeyPair real,
			KeyPair unrelated) {
		HybridAgreementPrivateKey realPriv =
				(HybridAgreementPrivateKey) real.getPrivate();
		HybridAgreementPrivateKey otherPriv =
				(HybridAgreementPrivateKey) unrelated.getPrivate();
		return new KeyPair(real.getPublic(), new HybridAgreementPrivateKey(
				realPriv.getX25519PrivateKey(), otherPriv.getMlKemPrivateKey()));
	}

	@Test(timeout = 60_000)
	public void testHonestPartiesAgreeOnAHybridMasterKey() throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		Outcome o = run(aliceKeys, aliceKeys, bobKeys, bobKeys);
		assertNull(o.aliceError);
		assertNull(o.bobError);
		assertNotNull(o.aliceKey);
		assertNotNull(o.bobKey);
		assertArrayEquals(o.aliceKey.getBytes(), o.bobKey.getBytes());
	}

	/**
	 * The master key depends on the ML-KEM secret that only the decapsulating
	 * party can recover, which is what protects a recorded pairing against a
	 * later quantum adversary: a party that presents the committed public key
	 * and holds its X25519 private half but not its ML-KEM private half is
	 * aborted whenever it has to decapsulate. Which side decapsulates is
	 * decided by the commitment order, so the test fixes the impostor on that
	 * side.
	 */
	@Test(timeout = 120_000)
	public void testDecapsulatorWithoutMlKemPrivateKeyIsAborted()
			throws Exception {
		int checked = 0;
		while (checked < 2) {
			KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
			KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
			KeyPair unrelated = crypto.generateHybridAgreementKeyPair();
			boolean aliceEncapsulates =
					payloadFor(aliceKeys).compareTo(payloadFor(bobKeys)) < 0;
			KeyPair aliceUsed = aliceEncapsulates ? aliceKeys
					: withoutMlKemPrivateKey(aliceKeys, unrelated);
			KeyPair bobUsed = aliceEncapsulates
					? withoutMlKemPrivateKey(bobKeys, unrelated) : bobKeys;
			Outcome o = run(aliceKeys, aliceUsed, bobKeys, bobUsed);
			assertNull(o.aliceKey);
			assertNull(o.bobKey);
			assertTrue(String.valueOf(o.aliceError),
					o.aliceError instanceof AbortException);
			assertTrue(String.valueOf(o.bobError),
					o.bobError instanceof AbortException);
			checked++;
		}
	}

	/**
	 * The encapsulating side contributes the ML-KEM secret it generated, so
	 * its own ML-KEM private key never enters the protocol; the classical
	 * halves still have to match on both sides.
	 */
	@Test(timeout = 60_000)
	public void testWrongX25519PrivateKeyIsAbortedOnEitherSide()
			throws Exception {
		for (int impostor = 0; impostor < 2; impostor++) {
			KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
			KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
			KeyPair unrelated = crypto.generateHybridAgreementKeyPair();
			KeyPair aliceUsed = impostor == 0
					? withoutX25519PrivateKey(aliceKeys, unrelated) : aliceKeys;
			KeyPair bobUsed = impostor == 1
					? withoutX25519PrivateKey(bobKeys, unrelated) : bobKeys;
			Outcome o = run(aliceKeys, aliceUsed, bobKeys, bobUsed);
			assertNull(o.aliceKey);
			assertNull(o.bobKey);
			assertTrue(o.aliceError instanceof AbortException);
			assertTrue(o.bobError instanceof AbortException);
		}
	}

	private static KeyPair withoutX25519PrivateKey(KeyPair real,
			KeyPair unrelated) {
		HybridAgreementPrivateKey realPriv =
				(HybridAgreementPrivateKey) real.getPrivate();
		HybridAgreementPrivateKey otherPriv =
				(HybridAgreementPrivateKey) unrelated.getPrivate();
		return new KeyPair(real.getPublic(), new HybridAgreementPrivateKey(
				otherPriv.getX25519PrivateKey(), realPriv.getMlKemPrivateKey()));
	}

	@Test
	public void testQrPayloadCarriesVersionFiveAndRejectsVersionFour()
			throws Exception {
		KeyPair keys = crypto.generateHybridAgreementKeyPair();
		byte[] encoded = payloadEncoder.encode(payloadFor(keys));
		assertEquals(5, encoded[0]);
		assertEquals(5, PROTOCOL_VERSION);
		Payload parsed = payloadParser.parse(
				new String(encoded, StandardCharsets.ISO_8859_1));
		assertArrayEquals(keyAgreementCrypto.deriveKeyCommitment(
				keys.getPublic()), parsed.getCommitment());
		byte[] classical = encoded.clone();
		classical[0] = 4;
		try {
			payloadParser.parse(new String(classical,
					StandardCharsets.ISO_8859_1));
			fail("a classical nearby pairing payload must be refused");
		} catch (UnsupportedVersionException expected) {
			assertTrue(expected.isTooOld());
		}
	}
}
