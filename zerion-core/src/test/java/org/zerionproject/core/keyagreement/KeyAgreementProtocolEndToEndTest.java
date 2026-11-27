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
import org.zerionproject.core.api.keyagreement.RecordTypes;
import org.zerionproject.core.api.plugin.LanTcpConstants;
import org.zerionproject.core.api.record.RecordReaderFactory;
import org.zerionproject.core.api.record.RecordWriterFactory;
import org.zerionproject.core.test.TestDuplexTransportConnection;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.annotation.Nullable;

import static java.util.Collections.emptyList;
import static org.zerionproject.core.api.keyagreement.KeyAgreementConstants.PROTOCOL_VERSION;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
		volatile SecretKey aliceExchangeKey, bobExchangeKey;
		@Nullable
		volatile Throwable aliceError, bobError;
	}

	private Payload payloadFor(KeyPair keys) {
		return payloadFor(keys, true);
	}

	private Payload payloadFor(KeyPair keys, boolean current) {
		return new Payload(
				keyAgreementCrypto.deriveKeyCommitment(keys.getPublic()),
				emptyList(), current);
	}

	private Payload scan(Payload shown, boolean scannerIsCurrent)
			throws Exception {
		byte[] qr = payloadEncoder.encode(shown);
		Payload scanned = payloadParser.parse(
				new String(qr, StandardCharsets.ISO_8859_1));
		return scannerIsCurrent ? scanned : scanned.withoutCapabilities();
	}

	private KeyAgreementTransport transport(
			TestDuplexTransportConnection connection, boolean current,
			boolean missesTheCapability) throws Exception {
		return new KeyAgreementTransport(recordReaderFactory,
				recordWriterFactory,
				new KeyAgreementConnection(connection, LanTcpConstants.ID)) {
			@Override
			boolean peerOffersKeySeparation() {
				return current && !missesTheCapability
						&& super.peerOffersKeySeparation();
			}
		};
	}

	private Outcome run(KeyPair aliceKeys, KeyPair aliceUsed, KeyPair bobKeys,
			KeyPair bobUsed) throws Exception {
		return run(aliceKeys, aliceUsed, bobKeys, bobUsed, true, true, false,
				false);
	}

	private Outcome run(KeyPair aliceKeys, KeyPair aliceUsed, KeyPair bobKeys,
			KeyPair bobUsed, boolean aliceCurrent, boolean bobCurrent,
			boolean aliceMissesTheCapability, boolean bobMissesTheCapability)
			throws Exception {
		Payload alicePayload = payloadFor(aliceKeys, aliceCurrent);
		Payload bobPayload = payloadFor(bobKeys, bobCurrent);
		boolean aliceIsAlice = alicePayload.compareTo(bobPayload) < 0;
		TestDuplexTransportConnection[] pair =
				TestDuplexTransportConnection.createPair();
		KeyAgreementTransport aliceTransport = transport(pair[0],
				aliceCurrent, aliceMissesTheCapability);
		KeyAgreementTransport bobTransport = transport(pair[1], bobCurrent,
				bobMissesTheCapability);
		KeyAgreementProtocol alice = new KeyAgreementProtocol(NO_CALLBACKS,
				crypto, keyAgreementCrypto, payloadEncoder, aliceTransport,
				scan(bobPayload, aliceCurrent), alicePayload, aliceUsed,
				aliceIsAlice);
		KeyAgreementProtocol bob = new KeyAgreementProtocol(NO_CALLBACKS,
				crypto, keyAgreementCrypto, payloadEncoder, bobTransport,
				scan(alicePayload, bobCurrent), bobPayload, bobUsed,
				!aliceIsAlice);
		Outcome o = new Outcome();
		Thread ta = new Thread(() -> {
			try {
				o.aliceKey = alice.perform();
				o.aliceExchangeKey = alice.getExchangeKey();
			} catch (Throwable t) {
				o.aliceError = t;
			}
		});
		Thread tb = new Thread(() -> {
			try {
				o.bobKey = bob.perform();
				o.bobExchangeKey = bob.getExchangeKey();
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

	@Test(timeout = 60_000)
	public void testCurrentPeersSeparateTheExchangeKeyFromTheRoot()
			throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		Outcome o = run(aliceKeys, aliceKeys, bobKeys, bobKeys);
		assertNull(o.aliceError);
		assertNull(o.bobError);
		assertNotNull(o.aliceExchangeKey);
		assertNotNull(o.bobExchangeKey);
		assertArrayEquals(o.aliceKey.getBytes(), o.bobKey.getBytes());
		assertArrayEquals(o.aliceExchangeKey.getBytes(),
				o.bobExchangeKey.getBytes());
		assertFalse(Arrays.equals(o.aliceKey.getBytes(),
				o.aliceExchangeKey.getBytes()));
	}

	@Test(timeout = 120_000)
	public void testAnEarlierPeerPairsWithTheEarlierDerivation()
			throws Exception {
		boolean[][] offers = {{false, true}, {true, false}, {false, false}};
		for (boolean[] offer : offers) {
			KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
			KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
			Outcome o = run(aliceKeys, aliceKeys, bobKeys, bobKeys,
					offer[0], offer[1], false, false);
			assertNull(String.valueOf(o.aliceError), o.aliceError);
			assertNull(String.valueOf(o.bobError), o.bobError);
			assertArrayEquals(o.aliceKey.getBytes(), o.bobKey.getBytes());
			assertNull(o.aliceExchangeKey);
			assertNull(o.bobExchangeKey);
		}
	}

	@Test(timeout = 60_000)
	public void testACapabilityLostOnOneSideAbortsThePairing()
			throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		Outcome o = run(aliceKeys, aliceKeys, bobKeys, bobKeys, true, true,
				false, true);
		assertNull(o.aliceKey);
		assertNull(o.bobKey);
		assertTrue(o.aliceError instanceof AbortException);
		assertTrue(o.bobError instanceof AbortException);
	}

	@Test(timeout = 60_000)
	public void testCapabilitiesStrippedBothWaysAbortThePairing()
			throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		Outcome o = run(aliceKeys, aliceKeys, bobKeys, bobKeys, true, true,
				true, true);
		assertNull("a stripped pairing fell back to the earlier keys",
				o.aliceKey);
		assertNull(o.bobKey);
		assertTrue(o.aliceError instanceof AbortException);
		assertTrue(o.bobError instanceof AbortException);
	}

	private interface Mutator {
		java.util.List<org.zerionproject.core.api.record.Record> apply(
				org.zerionproject.core.api.record.Record r);
	}

	private static final Mutator PASS = java.util.Collections::singletonList;

	private static final Mutator DROP_CAPABILITIES = r ->
			r.getRecordType() == RecordTypes.CAPABILITIES
					? java.util.Collections.emptyList()
					: java.util.Collections.singletonList(r);

	private static final Mutator INJECT_CAPABILITIES = r -> {
		if (r.getRecordType() != RecordTypes.KEY) {
			return java.util.Collections.singletonList(r);
		}
		return java.util.Arrays.asList(
				new org.zerionproject.core.api.record.Record(PROTOCOL_VERSION,
						RecordTypes.CAPABILITIES, new byte[] {0x01}), r);
	};

	private void relay(java.io.InputStream from, java.io.OutputStream to,
			Mutator mutator) {
		try {
			org.zerionproject.core.api.record.RecordReader r =
					recordReaderFactory.createRecordReader(from, 1 << 16);
			org.zerionproject.core.api.record.RecordWriter w =
					recordWriterFactory.createRecordWriter(to);
			org.zerionproject.core.api.record.Record rec;
			while ((rec = r.readRecord()) != null) {
				for (org.zerionproject.core.api.record.Record out :
						mutator.apply(rec)) {
					w.writeRecord(out);
					w.flush();
				}
			}
		} catch (java.io.IOException ignored) {
		} finally {
			try {
				to.close();
			} catch (java.io.IOException ignored) {
			}
		}
	}

	private Outcome runThroughRelay(boolean aliceCurrent, boolean bobCurrent,
			Mutator fromAlice, Mutator fromBob) throws Exception {
		KeyPair aliceKeys = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeys = crypto.generateHybridAgreementKeyPair();
		Payload alicePayload = payloadFor(aliceKeys, aliceCurrent);
		Payload bobPayload = payloadFor(bobKeys, bobCurrent);
		boolean aliceIsAlice = alicePayload.compareTo(bobPayload) < 0;
		java.io.PipedInputStream relayFromAlice =
				new java.io.PipedInputStream(1 << 16);
		java.io.PipedInputStream bobIn = new java.io.PipedInputStream(1 << 16);
		java.io.PipedInputStream relayFromBob =
				new java.io.PipedInputStream(1 << 16);
		java.io.PipedInputStream aliceIn = new java.io.PipedInputStream(1 << 16);
		java.io.PipedOutputStream aliceOut =
				new java.io.PipedOutputStream(relayFromAlice);
		java.io.PipedOutputStream toBob = new java.io.PipedOutputStream(bobIn);
		java.io.PipedOutputStream bobOut =
				new java.io.PipedOutputStream(relayFromBob);
		java.io.PipedOutputStream toAlice =
				new java.io.PipedOutputStream(aliceIn);
		KeyAgreementProtocol alice = new KeyAgreementProtocol(NO_CALLBACKS,
				crypto, keyAgreementCrypto, payloadEncoder,
				transport(new TestDuplexTransportConnection(aliceIn, aliceOut),
						aliceCurrent, false),
				scan(bobPayload, aliceCurrent), alicePayload, aliceKeys,
				aliceIsAlice);
		KeyAgreementProtocol bob = new KeyAgreementProtocol(NO_CALLBACKS,
				crypto, keyAgreementCrypto, payloadEncoder,
				transport(new TestDuplexTransportConnection(bobIn, bobOut),
						bobCurrent, false),
				scan(alicePayload, bobCurrent), bobPayload, bobKeys,
				!aliceIsAlice);
		Outcome o = new Outcome();
		Thread ta = new Thread(() -> {
			try {
				o.aliceKey = alice.perform();
				o.aliceExchangeKey = alice.getExchangeKey();
			} catch (Throwable t) {
				o.aliceError = t;
			}
		});
		Thread tb = new Thread(() -> {
			try {
				o.bobKey = bob.perform();
				o.bobExchangeKey = bob.getExchangeKey();
			} catch (Throwable t) {
				o.bobError = t;
			}
		});
		Thread r1 = new Thread(() -> relay(relayFromAlice, toBob, fromAlice));
		Thread r2 = new Thread(() -> relay(relayFromBob, toAlice, fromBob));
		ta.start();
		tb.start();
		r1.start();
		r2.start();
		ta.join(60_000);
		tb.join(60_000);
		closeQuietly(aliceOut);
		closeQuietly(bobOut);
		r1.join(10_000);
		r2.join(10_000);
		assertTrue(!ta.isAlive() && !tb.isAlive());
		return o;
	}

	private static void closeQuietly(java.io.OutputStream out) {
		try {
			out.close();
		} catch (java.io.IOException ignored) {
		}
	}

	private static boolean failed(@Nullable Throwable t) {
		return t instanceof AbortException || t instanceof java.io.IOException;
	}

	private static void assertAborted(Outcome o) {
		assertNull(o.aliceKey);
		assertNull(o.bobKey);
		assertTrue(String.valueOf(o.aliceError) + " / " + o.bobError,
				failed(o.aliceError) && failed(o.bobError));
		assertTrue(o.aliceError instanceof AbortException
				|| o.bobError instanceof AbortException);
	}

	@Test(timeout = 120_000)
	public void testAPassiveRelayKeepsTheSeparatedKeys() throws Exception {
		Outcome o = runThroughRelay(true, true, PASS, PASS);
		assertNull(String.valueOf(o.aliceError), o.aliceError);
		assertNull(String.valueOf(o.bobError), o.bobError);
		assertNotNull(o.aliceExchangeKey);
		assertArrayEquals(o.aliceKey.getBytes(), o.bobKey.getBytes());
	}

	@Test(timeout = 240_000)
	public void testCapabilityRecordsDroppedOnTheWireAbort() throws Exception {
		assertAborted(runThroughRelay(true, true, DROP_CAPABILITIES, PASS));
		assertAborted(runThroughRelay(true, true, PASS, DROP_CAPABILITIES));
		assertAborted(runThroughRelay(true, true, DROP_CAPABILITIES,
				DROP_CAPABILITIES));
	}

	@Test(timeout = 240_000)
	public void testInjectedCapabilityRecordsOnTheWire() throws Exception {
		assertAborted(runThroughRelay(true, false, PASS, INJECT_CAPABILITIES));
		assertAborted(runThroughRelay(false, true, INJECT_CAPABILITIES, PASS));
		Outcome duplicate = runThroughRelay(true, true, INJECT_CAPABILITIES,
				INJECT_CAPABILITIES);
		assertNull(String.valueOf(duplicate.aliceError), duplicate.aliceError);
		assertNull(String.valueOf(duplicate.bobError), duplicate.bobError);
		assertNotNull(duplicate.aliceExchangeKey);
		Outcome earlier = runThroughRelay(true, false, PASS, PASS);
		assertNull(String.valueOf(earlier.aliceError), earlier.aliceError);
		assertNull(earlier.aliceExchangeKey);
	}

	@Test
	public void testMarkerPositionDuplicatesAndMalformedMarkers()
			throws Exception {
		Object[][] cases = {
				{new Object[][] {{0x7FL, 1L}, {1L, "lan"}}, true, 1},
				{new Object[][] {{1L, "lan"}, {0x7FL, 1L}, {0x7FL, 1L}}, true,
						1},
				{new Object[][] {{0x7FL}}, false, 0},
				{new Object[][] {{0x7FL, null}}, false, 0},
				{new Object[][] {{0x7FL, 2L}}, false, 0},
				{new Object[][] {{0x7FL, 3L, "extra"}, {2L, "bt"}}, true, 1},
		};
		KeyPair keys = crypto.generateHybridAgreementKeyPair();
		byte[] commitment = keyAgreementCrypto.deriveKeyCommitment(
				keys.getPublic());
		for (Object[] c : cases) {
			java.util.List<org.zerionproject.core.api.keyagreement
					.TransportDescriptor> descriptors =
					new java.util.ArrayList<>();
			for (Object[] d : (Object[][]) c[0]) {
				descriptors.add(new org.zerionproject.core.api.keyagreement
						.TransportDescriptor(LanTcpConstants.ID,
						org.zerionproject.core.api.data.BdfList.of(d)));
			}
			byte[] qr = payloadEncoder.encode(new Payload(commitment,
					descriptors, false));
			Payload parsed = payloadParser.parse(
					new String(qr, StandardCharsets.ISO_8859_1));
			assertEquals(c[1], parsed.offersKeySeparation());
			assertEquals(c[2], parsed.getTransportDescriptors().size());
		}
	}

	@Test
	public void testUnknownDescriptorsAndBadMarkersAreDropped()
			throws Exception {
		KeyPair keys = crypto.generateHybridAgreementKeyPair();
		byte[] commitment = keyAgreementCrypto.deriveKeyCommitment(
				keys.getPublic());
		java.util.List<org.zerionproject.core.api.keyagreement
				.TransportDescriptor> descriptors = new java.util.ArrayList<>();
		descriptors.add(new org.zerionproject.core.api.keyagreement
				.TransportDescriptor(LanTcpConstants.ID,
				org.zerionproject.core.api.data.BdfList.of(0x7EL, 1L)));
		descriptors.add(new org.zerionproject.core.api.keyagreement
				.TransportDescriptor(LanTcpConstants.ID,
				org.zerionproject.core.api.data.BdfList.of(0x7FL, "x")));
		byte[] qr = payloadEncoder.encode(new Payload(commitment,
				descriptors, false));
		Payload parsed = payloadParser.parse(
				new String(qr, StandardCharsets.ISO_8859_1));
		assertTrue(parsed.getTransportDescriptors().isEmpty());
		assertFalse(parsed.offersKeySeparation());
	}

	@Test
	public void testTheQrMarkerStaysOutOfTheConfirmationAndEarlierParsers()
			throws Exception {
		KeyPair keys = crypto.generateHybridAgreementKeyPair();
		Payload marked = payloadFor(keys, true);
		Payload unmarked = payloadFor(keys, false);
		byte[] markedQr = payloadEncoder.encode(marked);
		byte[] unmarkedQr = payloadEncoder.encode(unmarked);
		assertTrue(markedQr.length > unmarkedQr.length);
		assertEquals(6, markedQr.length - unmarkedQr.length);
		assertArrayEquals(unmarkedQr,
				payloadEncoder.encode(marked.withoutCapabilities()));
		Payload parsed = payloadParser.parse(
				new String(markedQr, StandardCharsets.ISO_8859_1));
		assertTrue(parsed.offersKeySeparation());
		assertTrue(parsed.getTransportDescriptors().isEmpty());
		assertArrayEquals("a parser that drops the marker sees the earlier "
						+ "encoding", unmarkedQr,
				payloadEncoder.encode(parsed.withoutCapabilities()));
		Payload old = payloadParser.parse(
				new String(unmarkedQr, StandardCharsets.ISO_8859_1));
		assertFalse(old.offersKeySeparation());
	}

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
