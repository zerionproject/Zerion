package org.zerionproject.tools;

import org.zerionproject.core.api.crypto.SecretKey;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
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
	public void oneToOneFramesForgedFromTheRootKeyPassTheProductionReceiver()
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
		for (Frame f : t.frames) {
			assertEquals(f.text, f.received);
			pq |= f.carriedPqSecret;
		}
		assertTrue(pq);
		assertTrue(simulator.verifyOneToOne(t));
	}

	@Test
	public void oneToOneFramesAreNotRecognisedUnderAnotherRootKey()
			throws Exception {
		byte[] root = simulator.randomBytes(SecretKey.LENGTH);
		OneToOneTranscript t = simulator.forgeOneToOne(root, conversation());
		byte[] other = simulator.randomBytes(SecretKey.LENGTH);
		OneToOneTranscript wrongRoot = new OneToOneTranscript(other,
				t.aliceTagKey, t.bobTagKey, t.aliceHeaderKey, t.bobHeaderKey,
				t.frames);
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
		String out = assertRunsOk("one-to-one", "--root", root, "--verify",
				"a:Hi");
		assertTrue(out.contains(root));
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
