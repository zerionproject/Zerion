package org.zerionproject.tools;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorFactory;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.contact.B3PqProof;
import org.zerionproject.tools.TranscriptSimulator.Frame;
import org.zerionproject.tools.TranscriptSimulator.MeshInputs;
import org.zerionproject.tools.TranscriptSimulator.Message;
import org.zerionproject.tools.TranscriptSimulator.OneToOneTranscript;
import org.zerionproject.tools.TranscriptSimulator.PairingParty;
import org.zerionproject.tools.TranscriptSimulator.PairingValues;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TranscriptSimulatorTest {

	private TranscriptSimulator simulator;

	@Before
	public void setUp() throws Exception {
		simulator = new TranscriptSimulator();
	}

	private List<Message> conversation() {
		List<Message> messages = new ArrayList<>();
		messages.add(new Message(true, "Hello Bob"));
		messages.add(new Message(false, "Hello Alice"));
		messages.add(new Message(true, "I never wrote this"));
		messages.add(new Message(true, "Nor this"));
		messages.add(new Message(false, "Neither did I"));
		return messages;
	}

	@Test
	public void oneToOneFramesForgedFromTheRootKeyPassAFreshReceiver()
			throws Exception {
		byte[] root = simulator.randomBytes(SecretKey.LENGTH);
		OneToOneTranscript t = simulator.forgeOneToOne(root, conversation());
		assertEquals(5, t.frames.size());
		assertEquals(TAG_LENGTH + STREAM_HEADER_LENGTH + FRAME_LENGTH,
				t.frames.get(0).bytes.length);
		assertEquals(TAG_LENGTH + STREAM_HEADER_LENGTH + FRAME_LENGTH,
				t.frames.get(1).bytes.length);
		assertEquals(FRAME_LENGTH, t.frames.get(2).bytes.length);
		boolean pq = false;
		for (Frame f : t.frames) pq |= f.carriedPqSecret;
		assertTrue(pq);
		assertTrue(simulator.verifyOneToOne(t));
	}

	@Test
	public void oneToOneVerificationRunsLongEnoughToRotateTheConnectionKeys()
			throws Exception {
		List<Message> messages = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			messages.add(new Message(i % 3 != 0, "message " + i));
		}
		byte[] root = simulator.randomBytes(SecretKey.LENGTH);
		OneToOneTranscript t = simulator.forgeOneToOne(root, messages);
		assertEquals(40, t.frames.size());
		assertTrue(simulator.verifyOneToOne(t));
	}

	@Test
	public void oneToOneVerificationRejectsATamperedFrame() throws Exception {
		byte[] root = simulator.randomBytes(SecretKey.LENGTH);
		OneToOneTranscript t = simulator.forgeOneToOne(root, conversation());
		List<Frame> frames = new ArrayList<>(t.frames);
		Frame third = frames.get(2);
		byte[] tampered = third.bytes.clone();
		tampered[tampered.length - 1] ^= 0x01;
		frames.set(2, new Frame(third.fromAlice, third.text, tampered,
				third.carriedPqSecret, third.streamId));
		assertFalse(simulator.verifyOneToOne(new OneToOneTranscript(t.rootKey,
				t.connectionSeed, t.aliceTagKey, t.bobTagKey, t.aliceHeaderKey,
				t.bobHeaderKey, frames)));
		frames = new ArrayList<>(t.frames);
		frames.set(2, new Frame(third.fromAlice, "Something else",
				third.bytes, third.carriedPqSecret, third.streamId));
		assertFalse(simulator.verifyOneToOne(new OneToOneTranscript(t.rootKey,
				t.connectionSeed, t.aliceTagKey, t.bobTagKey, t.aliceHeaderKey,
				t.bobHeaderKey, frames)));
	}

	@Test
	public void oneToOneFramesAreNotRecognisedUnderAnotherRootKey()
			throws Exception {
		byte[] root = simulator.randomBytes(SecretKey.LENGTH);
		OneToOneTranscript t = simulator.forgeOneToOne(root, conversation());
		byte[] other = simulator.randomBytes(SecretKey.LENGTH);
		OneToOneTranscript wrongRoot = new OneToOneTranscript(other,
				t.connectionSeed, t.aliceTagKey, t.bobTagKey, t.aliceHeaderKey,
				t.bobHeaderKey, t.frames);
		assertFalse(simulator.verifyOneToOne(wrongRoot));
	}

	@Test
	public void meshEnvelopeForgedUnderTheLinkKeyOpensAsKeyed()
			throws Exception {
		MeshInputs in = simulator.randomMeshInputs(
				"Hello over the mesh".getBytes(StandardCharsets.UTF_8));
		in.sendTimestamp = 1_700_000_000_000L;
		byte[] envelope = simulator.forgeMesh(in);
		assertTrue(simulator.verifyMesh(in, envelope));
	}

	@Test
	public void meshEnvelopeDoesNotOpenUnderAnotherLinkKey() throws Exception {
		MeshInputs in = simulator.randomMeshInputs(
				"Hello over the mesh".getBytes(StandardCharsets.UTF_8));
		byte[] envelope = simulator.forgeMesh(in);
		in.linkKey = simulator.randomBytes(in.linkKey.length);
		assertFalse(simulator.verifyMesh(in, envelope));
	}

	@Test
	public void pairingTagsForgedUnderTheExchangeKeyVerify()
			throws Exception {
		PairingValues v = randomPairing();
		assertEquals(32, v.aliceTag.length);
		assertFalse(Arrays.equals(v.aliceTag, v.bobTag));
		assertTrue(simulator.verifyPairing(v));
	}

	@Test
	public void pairingTagsDoNotVerifyUnderAnotherExchangeKey()
			throws Exception {
		PairingValues v = randomPairing();
		PairingValues other = new PairingValues(
				simulator.randomBytes(SecretKey.LENGTH), v.alice, v.bob,
				v.sessionId, v.aliceTag, v.bobTag);
		assertFalse(simulator.verifyPairing(other));
	}

	@Test
	public void pairingTagsCoverTheContactInformation() throws Exception {
		PairingValues v = randomPairing();
		PairingParty renamed = new PairingParty(
				simulator.encodeAuthor("Carol", Arrays.copyOfRange(
						v.alice.author, v.alice.author.length - 32,
						v.alice.author.length)),
				v.alice.mlDsaSigPub, v.alice.properties, v.alice.timestamp,
				v.alice.staticPqPub, v.alice.ephemeral);
		assertFalse(simulator.verifyPairing(new PairingValues(v.exchangeKey,
				renamed, v.bob, v.sessionId, v.aliceTag, v.bobTag)));
		PairingParty later = new PairingParty(v.bob.author, v.bob.mlDsaSigPub,
				v.bob.properties, v.bob.timestamp + 1, v.bob.staticPqPub,
				v.bob.ephemeral);
		assertFalse(simulator.verifyPairing(new PairingValues(v.exchangeKey,
				v.alice, later, v.sessionId, v.aliceTag, v.bobTag)));
	}

	@Test
	public void pairingTagEqualsTheProductionExchangeComputation()
			throws Exception {
		CryptoComponent crypto = simulator.getCrypto();
		AuthorFactory authorFactory = authorFactory(crypto);
		KeyPair aliceKeys = crypto.generateSignatureKeyPair();
		KeyPair bobKeys = crypto.generateSignatureKeyPair();
		Author alice = authorFactory.createAuthor("Alice", aliceKeys.getPublic());
		Author bob = authorFactory.createAuthor("Bob", bobKeys.getPublic());
		Map<TransportId, TransportProperties> aliceProps =
				new LinkedHashMap<>();
		TransportProperties tor = new TransportProperties();
		tor.put("onion", "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrst");
		aliceProps.put(new TransportId("org.zerionproject.tor"), tor);
		Map<TransportId, TransportProperties> bobProps = new LinkedHashMap<>();
		byte[] exchangeKey = simulator.randomBytes(SecretKey.LENGTH);
		long aliceTime = 1_700_000_000_000L;
		long bobTime = 1_700_000_000_250L;
		PairingParty aliceParty = new PairingParty(
				simulator.encodeAuthor("Alice",
						aliceKeys.getPublic().getEncoded()),
				randomMlDsaKey(crypto), productionProperties(aliceProps),
				aliceTime, randomMlKemKey(crypto),
				crypto.generateAgreementKeyPair().getPublic().getEncoded());
		PairingParty bobParty = new PairingParty(
				simulator.encodeAuthor("Bob", bobKeys.getPublic().getEncoded()),
				randomMlDsaKey(crypto), productionProperties(bobProps),
				bobTime, randomMlKemKey(crypto),
				crypto.generateAgreementKeyPair().getPublic().getEncoded());
		PairingValues v = simulator.forgePairing(exchangeKey, aliceParty,
				bobParty);

		assertArrayEquals(productionAuthor(alice), aliceParty.author);
		assertArrayEquals(productionAuthor(bob), bobParty.author);
		byte[] sessionId = B3PqProof
				.computeSessionId(aliceParty.ephemeral, bobParty.ephemeral);
		SecretKey k = new SecretKey(exchangeKey.clone());
		byte[] aliceTag = productionTag(crypto, k, true,
				productionAuthor(alice), aliceParty.mlDsaSigPub,
				productionProperties(aliceProps), aliceTime,
				aliceParty.staticPqPub, sessionId);
		byte[] bobTag = productionTag(crypto, k, false, productionAuthor(bob),
				bobParty.mlDsaSigPub, productionProperties(bobProps), bobTime,
				bobParty.staticPqPub, sessionId);
		assertArrayEquals(aliceTag, v.aliceTag);
		assertArrayEquals(bobTag, v.bobTag);

		byte[] time = ByteBuffer.allocate(8).putLong(aliceTime).array();
		assertArrayEquals(crypto.mac(exchangeTagLabel(), k, new byte[] {0x01},
				productionAuthor(alice), aliceParty.mlDsaSigPub,
				productionProperties(aliceProps), time, aliceParty.staticPqPub,
				sessionId), v.aliceTag);
	}

	@Test
	public void commandLineVerifiesEveryScenario() throws Exception {
		assertRunsOk("one-to-one", "--verify", "a:Hello", "Bob", "b:Hello",
				"Alice");
		assertRunsOk("mesh", "--verify", "--send-time", "1700000000000",
				"Hello", "over", "the", "mesh");
		assertRunsOk("pairing", "--verify", "--alice-name", "Carol",
				"--bob-time", "1700000000000");
	}

	@Test
	public void commandLineAcceptsKeysAsHex() throws Exception {
		String root = "00112233445566778899aabbccddeeff"
				+ "00112233445566778899aabbccddeeff";
		String seed = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
				+ "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf";
		String out = assertRunsOk("one-to-one", "--root", root, "--seed",
				seed, "--verify", "a:Hi");
		assertTrue(out.contains(root));
		assertTrue(out.contains(seed));
		String linkKey = "ffeeddccbbaa99887766554433221100"
				+ "ffeeddccbbaa99887766554433221100";
		out = assertRunsOk("mesh", "--link-key", linkKey, "--verify", "Hi");
		assertTrue(out.contains(linkKey));
		out = assertRunsOk("pairing", "--exchange-key", root, "--verify");
		assertTrue(out.contains(root));
	}

	private PairingValues randomPairing() throws Exception {
		byte[] exchangeKey = simulator.randomBytes(SecretKey.LENGTH);
		PairingParty alice =
				simulator.randomPairingParty("Alice", 1_700_000_000_000L);
		PairingParty bob =
				simulator.randomPairingParty("Bob", 1_700_000_000_500L);
		return simulator.forgePairing(exchangeKey, alice, bob);
	}

	private static AuthorFactory authorFactory(CryptoComponent crypto)
			throws Exception {
		Class<?> impl = Class.forName(
				"org.zerionproject.core.identity.AuthorFactoryImpl");
		Constructor<?> c = impl.getDeclaredConstructor(CryptoComponent.class);
		c.setAccessible(true);
		return (AuthorFactory) c.newInstance(crypto);
	}

	private static BdfWriterFactory bdfWriterFactory() throws Exception {
		Class<?> impl = Class.forName(
				"org.zerionproject.core.data.BdfWriterFactoryImpl");
		Constructor<?> c = impl.getDeclaredConstructor();
		c.setAccessible(true);
		return (BdfWriterFactory) c.newInstance();
	}

	private static byte[] productionAuthor(Author a) throws Exception {
		BdfList list = BdfList.of(a.getFormatVersion(), a.getName(),
				a.getPublicKey());
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter writer = bdfWriterFactory().createWriter(out);
		writer.writeList(list);
		writer.flush();
		return out.toByteArray();
	}

	private static byte[] productionProperties(
			Map<TransportId, TransportProperties> map) throws Exception {
		BdfDictionary d = new BdfDictionary();
		for (Map.Entry<TransportId, TransportProperties> e : map.entrySet()) {
			d.put(e.getKey().getString(), new BdfDictionary(e.getValue()));
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter writer = bdfWriterFactory().createWriter(out);
		writer.writeDictionary(d);
		writer.flush();
		return out.toByteArray();
	}

	private static String exchangeTagLabel() throws Exception {
		Class<?> constants = Class.forName(
				"org.zerionproject.core.contact.ContactExchangeConstants");
		Field f = constants.getDeclaredField("EXCHANGE_TAG_LABEL");
		f.setAccessible(true);
		return (String) f.get(null);
	}

	private static byte[] productionTag(CryptoComponent crypto, SecretKey k,
			boolean alice, byte[] author, byte[] mlDsaSigPub,
			byte[] properties, long timestamp, byte[] staticPqPub,
			byte[] sessionId) throws Exception {
		Class<?> transcriptClass = Class.forName(
				"org.zerionproject.core.contact.ExchangeTranscript");
		Constructor<?> c = transcriptClass.getDeclaredConstructor(byte[].class,
				byte[].class, byte[].class, long.class, byte[].class,
				byte[].class);
		c.setAccessible(true);
		Object transcript = c.newInstance(author, mlDsaSigPub, properties,
				timestamp, staticPqPub, sessionId);
		Method inputs = transcriptClass.getDeclaredMethod("inputs",
				boolean.class);
		inputs.setAccessible(true);
		return crypto.mac(exchangeTagLabel(), k,
				(byte[][]) inputs.invoke(transcript, alice));
	}

	private static byte[] randomMlDsaKey(CryptoComponent crypto) {
		byte[] hybrid = crypto.generateHybridSignatureKeyPair().getPublic()
				.getEncoded();
		return Arrays.copyOfRange(hybrid, 32, hybrid.length);
	}

	private static byte[] randomMlKemKey(CryptoComponent crypto) {
		byte[] hybrid = crypto.generateHybridAgreementKeyPair().getPublic()
				.getEncoded();
		return Arrays.copyOfRange(hybrid, 32, hybrid.length);
	}

	private String assertRunsOk(String... args) throws Exception {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		String out = run(buf, args);
		assertTrue(out, out.contains(": OK"));
		return out;
	}

	private String run(ByteArrayOutputStream buf, String... args)
			throws Exception {
		PrintStream out = new PrintStream(buf, true, "UTF-8");
		int code = TranscriptSimulator.run(args, out);
		String text = buf.toString("UTF-8");
		assertEquals(text, 0, code);
		return text;
	}
}
