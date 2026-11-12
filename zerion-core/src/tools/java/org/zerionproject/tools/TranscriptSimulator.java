package org.zerionproject.tools;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.system.SecureRandomProvider;
import org.zerionproject.core.contact.B3PqProof;
import org.zerionproject.core.crypto.AuthenticatedCipher;
import org.zerionproject.core.crypto.XSalsa20Poly1305AuthenticatedCipher;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.crypto.pcs.PcsRatchetImpl;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.crypto.ZwfTagRecogniser;
import org.zerionproject.transport.ZwfDuplexConnection;
import org.zerionproject.transport.ZwfSession;
import org.zerionproject.transport.ZwfSessionFactory;
import org.zerionproject.wire.StreamCounterStore;
import org.zerionproject.wire.ZwfStreamCounter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import static org.zerionproject.core.api.contact.B3Constants.B3_PQ_PUB_LEN;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.zerionproject.wire.ZwfConstants.FRAME_LENGTH;
import static org.zerionproject.wire.ZwfConstants.REPLAY_WINDOW_SIZE;
import static org.zerionproject.wire.ZwfConstants.STREAM_HEADER_LENGTH;
import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;

@NotNullByDefault
public final class TranscriptSimulator {

	private static final int CONTACT_ALICE_SEES_BOB = 1;
	private static final int CONTACT_BOB_SEES_ALICE = 2;
	private static final int X25519_PUBLIC_KEY_BYTES = 32;
	private static final int ED25519_PUBLIC_KEY_BYTES = 32;
	private static final int FIRST_FRAME_BYTES =
			TAG_LENGTH + STREAM_HEADER_LENGTH + FRAME_LENGTH;

	public static final class Message {

		public final boolean fromAlice;
		public final String text;

		public Message(boolean fromAlice, String text) {
			this.fromAlice = fromAlice;
			this.text = text;
		}
	}

	public static final class Frame {

		public final boolean fromAlice;
		public final String text;
		public final byte[] bytes;
		public final String received;
		public final boolean carriedPqSecret;
		public final long streamId;

		public Frame(boolean fromAlice, String text, byte[] bytes,
				String received, boolean carriedPqSecret, long streamId) {
			this.fromAlice = fromAlice;
			this.text = text;
			this.bytes = bytes;
			this.received = received;
			this.carriedPqSecret = carriedPqSecret;
			this.streamId = streamId;
		}
	}

	public static final class OneToOneTranscript {

		public final byte[] rootKey;
		public final byte[] aliceTagKey;
		public final byte[] bobTagKey;
		public final byte[] aliceHeaderKey;
		public final byte[] bobHeaderKey;
		public final List<Frame> frames;

		public OneToOneTranscript(byte[] rootKey, byte[] aliceTagKey,
				byte[] bobTagKey, byte[] aliceHeaderKey, byte[] bobHeaderKey,
				List<Frame> frames) {
			this.rootKey = rootKey;
			this.aliceTagKey = aliceTagKey;
			this.bobTagKey = bobTagKey;
			this.aliceHeaderKey = aliceHeaderKey;
			this.bobHeaderKey = bobHeaderKey;
			this.frames = Collections.unmodifiableList(new ArrayList<>(frames));
		}
	}

	public static final class MeshInputs {

		public byte[] linkKey = new byte[0];
		public byte[] text = new byte[0];
		public byte[] senderIdentitySigPub = new byte[0];
		public long sendTimestamp;
		public int messageType = 1;
		public long ttl = 3600;
		public byte[] recipientIdentitySigPub = new byte[0];
		public byte[] recipientIdentityAgreePub = new byte[0];
		@Nullable
		public KeyPair recipientPrekey;
		public int prekeyKind = AsyncEnvelope.PREKEY_KIND_ONE_TIME;
		public byte[] prekeyId = new byte[0];
		public long signedPrekeyId = 1;
		public byte[] dedupId = new byte[0];
	}

	public static final class PairingParty {

		public final byte[] author;
		public final byte[] mlDsaSigPub;
		public final byte[] properties;
		public final long timestamp;
		public final byte[] staticPqPub;
		public final byte[] ephemeral;

		public PairingParty(byte[] author, byte[] mlDsaSigPub,
				byte[] properties, long timestamp, byte[] staticPqPub,
				byte[] ephemeral) {
			this.author = author;
			this.mlDsaSigPub = mlDsaSigPub;
			this.properties = properties;
			this.timestamp = timestamp;
			this.staticPqPub = staticPqPub;
			this.ephemeral = ephemeral;
		}
	}

	public static final class PairingValues {

		public final byte[] exchangeKey;
		public final PairingParty alice;
		public final PairingParty bob;
		public final byte[] sessionId;
		public final byte[] aliceTag;
		public final byte[] bobTag;

		public PairingValues(byte[] exchangeKey, PairingParty alice,
				PairingParty bob, byte[] sessionId, byte[] aliceTag,
				byte[] bobTag) {
			this.exchangeKey = exchangeKey;
			this.alice = alice;
			this.bob = bob;
			this.sessionId = sessionId;
			this.aliceTag = aliceTag;
			this.bobTag = bobTag;
		}
	}

	private final CryptoComponent crypto;
	private final PcsRatchet ratchet;
	private final Mode3FullRatchet mode3FullRatchet;
	private final ZwfSessionFactory sessionFactory;
	private final AsyncSealedSender sealer;
	private final BdfWriterFactory bdfWriterFactory;
	private final ExchangeTranscripts exchangeTranscripts;

	public TranscriptSimulator() throws ReflectiveOperationException {
		crypto = newCryptoComponent();
		ratchet = new PcsRatchetImpl(crypto);
		mode3FullRatchet = newMode3FullRatchet(crypto);
		sessionFactory = new ZwfSessionFactory(crypto, mode3FullRatchet);
		sealer = new AsyncSealedSender(crypto);
		bdfWriterFactory = newBdfWriterFactory();
		exchangeTranscripts = new ExchangeTranscripts();
	}

	public CryptoComponent getCrypto() {
		return crypto;
	}

	public byte[] randomBytes(int length) {
		byte[] b = new byte[length];
		crypto.getSecureRandom().nextBytes(b);
		return b;
	}

	public OneToOneTranscript forgeOneToOne(byte[] rootKey,
			List<Message> messages) throws IOException {
		requireLength(rootKey, SecretKey.LENGTH, "root key");
		if (messages.isEmpty()) {
			throw new IllegalArgumentException("at least one message");
		}
		SecretKey root = new SecretKey(rootKey.clone());
		ZwfSession aliceSession = sessionFactory.deriveSession(root, true);
		ZwfSession bobSession = sessionFactory.deriveSession(root, false);
		byte[] aliceTagKey = aliceSession.getSendTagKey().getBytes().clone();
		byte[] bobTagKey = bobSession.getSendTagKey().getBytes().clone();
		byte[] aliceHeaderKey =
				aliceSession.getSendHeaderKey().getBytes().clone();
		byte[] bobHeaderKey = bobSession.getSendHeaderKey().getBytes().clone();
		ByteQueue toAlice = new ByteQueue();
		ByteQueue toBob = new ByteQueue();
		Capture aliceOut = new Capture(toBob);
		Capture bobOut = new Capture(toAlice);
		Supplier<AuthenticatedCipher> ciphers =
				XSalsa20Poly1305AuthenticatedCipher::new;
		ZwfDuplexConnection alice = new ZwfDuplexConnection(
				CONTACT_ALICE_SEES_BOB, aliceSession,
				new ZwfStreamCounter(new MemoryCounterStore()), crypto,
				ratchet, mode3FullRatchet, ciphers, toAlice, aliceOut);
		ZwfDuplexConnection bob = new ZwfDuplexConnection(
				CONTACT_BOB_SEES_ALICE, bobSession,
				new ZwfStreamCounter(new MemoryCounterStore()), crypto,
				ratchet, mode3FullRatchet, ciphers, toBob, bobOut);
		ZwfTagRecogniser recogniser = rootRecogniser(rootKey);
		List<Frame> frames = new ArrayList<>();
		try {
			for (Message m : messages) {
				ZwfDuplexConnection sender = m.fromAlice ? alice : bob;
				ZwfDuplexConnection receiver = m.fromAlice ? bob : alice;
				Capture capture = m.fromAlice ? aliceOut : bobOut;
				byte[] payload = m.text.getBytes(StandardCharsets.UTF_8);
				if (payload.length > sender.getMaxMessageLength()) {
					throw new IllegalArgumentException(
							"a message must fit in one frame: at most "
									+ sender.getMaxMessageLength() + " bytes");
				}
				sender.sendMessage(payload);
				byte[] bytes = capture.take();
				byte[] got = receiver.receiveMessage();
				String received = got == null ? ""
						: new String(got, StandardCharsets.UTF_8);
				long streamId = -1;
				if (bytes.length == FIRST_FRAME_BYTES) {
					ZwfTagRecogniser.Match match = recogniser.recognise(
							Arrays.copyOf(bytes, TAG_LENGTH));
					if (match != null) streamId = match.streamId;
				}
				frames.add(new Frame(m.fromAlice, m.text, bytes, received,
						receiver.lastFrameCarriedPqSecret(), streamId));
			}
		} finally {
			alice.destroyKeyMaterial();
			bob.destroyKeyMaterial();
		}
		return new OneToOneTranscript(rootKey.clone(), aliceTagKey, bobTagKey,
				aliceHeaderKey, bobHeaderKey, frames);
	}

	public boolean verifyOneToOne(OneToOneTranscript t) {
		if (t.frames.isEmpty()) return false;
		ZwfTagRecogniser recogniser = rootRecogniser(t.rootKey);
		SecretKey root = new SecretKey(t.rootKey.clone());
		ZwfSession aliceSession = sessionFactory.deriveSession(root, true);
		ZwfSession bobSession = sessionFactory.deriveSession(root, false);
		boolean ok = MessageDigest.isEqual(t.aliceTagKey,
				aliceSession.getSendTagKey().getBytes());
		ok &= MessageDigest.isEqual(t.bobTagKey,
				bobSession.getSendTagKey().getBytes());
		ok &= MessageDigest.isEqual(t.aliceHeaderKey,
				aliceSession.getSendHeaderKey().getBytes());
		ok &= MessageDigest.isEqual(t.bobHeaderKey,
				bobSession.getSendHeaderKey().getBytes());
		aliceSession.clear();
		bobSession.clear();
		boolean aliceStarted = false, bobStarted = false;
		for (Frame f : t.frames) {
			ok &= f.text.equals(f.received);
			boolean first = f.fromAlice ? !aliceStarted : !bobStarted;
			if (first) {
				ok &= f.bytes.length == FIRST_FRAME_BYTES;
				if (!ok) return false;
				ZwfTagRecogniser.Match match = recogniser.recognise(
						Arrays.copyOf(f.bytes, TAG_LENGTH));
				int expected = f.fromAlice ? CONTACT_BOB_SEES_ALICE
						: CONTACT_ALICE_SEES_BOB;
				ok &= match != null && match.contactId == expected
						&& match.streamId == f.streamId;
				if (f.fromAlice) aliceStarted = true;
				else bobStarted = true;
			} else {
				ok &= f.bytes.length == FRAME_LENGTH;
			}
		}
		return ok;
	}

	public MeshInputs randomMeshInputs(byte[] text) {
		MeshInputs in = new MeshInputs();
		in.linkKey = randomBytes(AsyncSealedSender.LINK_KEY_BYTES);
		in.text = text.clone();
		in.senderIdentitySigPub = crypto.generateHybridSignatureKeyPair()
				.getPublic().getEncoded();
		in.sendTimestamp = System.currentTimeMillis();
		in.recipientIdentitySigPub = crypto.generateHybridSignatureKeyPair()
				.getPublic().getEncoded();
		in.recipientIdentityAgreePub = crypto.generateHybridAgreementKeyPair()
				.getPublic().getEncoded();
		in.recipientPrekey = crypto.generateHybridAgreementKeyPair();
		in.prekeyId = randomBytes(AsyncEnvelope.PREKEY_ID_BYTES);
		in.dedupId = randomBytes(AsyncEnvelope.DEDUP_ID_BYTES);
		return in;
	}

	public byte[] forgeMesh(MeshInputs in) throws GeneralSecurityException {
		KeyPair prekey = in.recipientPrekey;
		if (prekey == null) throw new IllegalArgumentException("prekey");
		requireLength(in.linkKey, AsyncSealedSender.LINK_KEY_BYTES,
				"link key");
		requireLength(in.senderIdentitySigPub,
				HYBRID_SIGNATURE_PUBLIC_KEY_BYTES, "sender identity key");
		AsyncSealedSender.SealRequest r = new AsyncSealedSender.SealRequest();
		r.recipientAgreementPub = prekey.getPublic();
		r.prekeyKind = in.prekeyKind;
		r.prekeyId = in.prekeyId;
		r.signedPrekeyId = in.signedPrekeyId;
		r.recipientIdentitySigPub = in.recipientIdentitySigPub;
		r.recipientIdentityAgreePub = in.recipientIdentityAgreePub;
		r.senderIdentitySigPub = in.senderIdentitySigPub;
		r.messageType = in.messageType;
		r.payload = in.text;
		r.ttl = in.ttl;
		r.dedupId = in.dedupId;
		r.sendTimestamp = in.sendTimestamp;
		r.senderLinkKey = in.linkKey;
		return sealer.seal(r);
	}

	public boolean verifyMesh(MeshInputs in, byte[] envelope) {
		KeyPair prekey = in.recipientPrekey;
		if (prekey == null) return false;
		AsyncSealedSender.OpenRequest o = new AsyncSealedSender.OpenRequest();
		o.recipientAgreementKeyPair = prekey;
		o.recipientIdentitySigPub = in.recipientIdentitySigPub;
		o.recipientIdentityAgreePub = in.recipientIdentityAgreePub;
		byte[] senderPub = in.senderIdentitySigPub;
		byte[] linkKey = in.linkKey;
		o.senderLinkKeys = peer ->
				Arrays.equals(peer, senderPub) ? linkKey : null;
		try {
			AsyncSealedSender.OpenedMessage m = sealer.open(envelope, o);
			return m.isKeyed()
					&& Arrays.equals(m.getPayload(), in.text)
					&& Arrays.equals(m.getSenderIdentitySigPub(), senderPub)
					&& m.getSendTimestamp() == in.sendTimestamp
					&& m.getMessageType() == in.messageType;
		} catch (GeneralSecurityException | FormatException
				| IllegalArgumentException e) {
			return false;
		}
	}

	public byte[] encodeAuthor(String name, byte[] ed25519PublicKey)
			throws FormatException {
		requireLength(ed25519PublicKey, ED25519_PUBLIC_KEY_BYTES,
				"Ed25519 public key");
		return encode(BdfList.of(Author.FORMAT_VERSION, name,
				ed25519PublicKey), null);
	}

	public byte[] encodeEmptyProperties() throws FormatException {
		return encode(null, new BdfDictionary());
	}

	public PairingParty randomPairingParty(String name, long timestamp)
			throws FormatException {
		byte[] ed = crypto.generateSignatureKeyPair().getPublic().getEncoded();
		byte[] hybridSig = crypto.generateHybridSignatureKeyPair().getPublic()
				.getEncoded();
		byte[] mlDsa = Arrays.copyOfRange(hybridSig,
				hybridSig.length - ML_DSA_65_PUBLIC_KEY_BYTES,
				hybridSig.length);
		return new PairingParty(encodeAuthor(name, ed), mlDsa,
				encodeEmptyProperties(), timestamp, randomMlKemPublicKey(),
				crypto.generateAgreementKeyPair().getPublic().getEncoded());
	}

	public PairingValues forgePairing(byte[] exchangeKey, PairingParty alice,
			PairingParty bob) throws ReflectiveOperationException {
		requireLength(exchangeKey, SecretKey.LENGTH, "exchange key");
		requireParty(alice, "Alice");
		requireParty(bob, "Bob");
		SecretKey k = new SecretKey(exchangeKey.clone());
		try {
			byte[] sessionId = B3PqProof.computeSessionId(alice.ephemeral,
					bob.ephemeral);
			byte[] aliceTag = crypto.mac(exchangeTranscripts.label, k,
					exchangeTranscripts.inputs(alice, sessionId, true));
			byte[] bobTag = crypto.mac(exchangeTranscripts.label, k,
					exchangeTranscripts.inputs(bob, sessionId, false));
			return new PairingValues(exchangeKey.clone(), alice, bob, sessionId,
					aliceTag, bobTag);
		} finally {
			k.clear();
		}
	}

	public boolean verifyPairing(PairingValues v)
			throws ReflectiveOperationException {
		SecretKey k = new SecretKey(v.exchangeKey.clone());
		try {
			byte[] sessionId = B3PqProof.computeSessionId(v.alice.ephemeral,
					v.bob.ephemeral);
			boolean ok = MessageDigest.isEqual(sessionId, v.sessionId);
			ok &= v.aliceTag.length == exchangeTranscripts.tagLength;
			ok &= v.bobTag.length == exchangeTranscripts.tagLength;
			ok &= crypto.verifyMac(v.aliceTag, exchangeTranscripts.label, k,
					exchangeTranscripts.inputs(v.alice, sessionId, true));
			ok &= crypto.verifyMac(v.bobTag, exchangeTranscripts.label, k,
					exchangeTranscripts.inputs(v.bob, sessionId, false));
			return ok;
		} finally {
			k.clear();
		}
	}

	public static void main(String[] args) {
		int code;
		try {
			code = run(args, System.out);
		} catch (Exception e) {
			System.out.println("error: " + e);
			code = 2;
		}
		System.exit(code);
	}

	public static int run(String[] args, PrintStream out) throws Exception {
		if (args.length == 0) {
			usage(out);
			return 2;
		}
		Map<String, String> options = new HashMap<>();
		List<String> positional = new ArrayList<>();
		boolean verify = false;
		for (int i = 1; i < args.length; i++) {
			String a = args[i];
			if (a.equals("--verify")) {
				verify = true;
			} else if (a.startsWith("--")) {
				if (i + 1 >= args.length) {
					throw new IllegalArgumentException(a + " needs a value");
				}
				options.put(a.substring(2), args[++i]);
			} else {
				positional.add(a);
			}
		}
		TranscriptSimulator sim = new TranscriptSimulator();
		switch (args[0]) {
			case "one-to-one":
				return sim.runOneToOne(options, positional, verify, out);
			case "mesh":
				return sim.runMesh(options, positional, verify, out);
			case "pairing":
				return sim.runPairing(options, verify, out);
			default:
				usage(out);
				return 2;
		}
	}

	private static void usage(PrintStream out) {
		out.println("Zerion transcript simulator: forges records under a key"
				+ " both peers hold and checks them with the production code.");
		out.println();
		out.println("one-to-one [--root HEX] [--verify] a:TEXT b:TEXT ...");
		out.println("    ZWF Mode 3-Full frames of a Tor connection; a: is a"
				+ " message from Alice, b: one from Bob, a word without a"
				+ " prefix continues the previous message");
		out.println("mesh [--link-key HEX] [--sender-pub HEX]"
				+ " [--send-time MILLIS] [--message-type N] [--ttl SECONDS]"
				+ " [--recipient-sig-pub HEX] [--recipient-agree-pub HEX]"
				+ " [--recipient-prekey-pub HEX] [--recipient-prekey-priv HEX]"
				+ " [--prekey-id HEX] [--dedup-id HEX] [--verify] TEXT ...");
		out.println("    a V2 mesh envelope with the keyed trailer, sealed as"
				+ " AsyncSealedSender does");
		out.println("pairing [--exchange-key HEX] [--verify] and for each"
				+ " party P in alice, bob: [--P-name NAME] [--P-ed HEX]"
				+ " [--P-author HEX] [--P-mldsa HEX] [--P-props HEX]"
				+ " [--P-time MILLIS] [--P-pq HEX] [--P-eph HEX]");
		out.println("    the tag each contact record carries between two"
				+ " 3.0.19 phones, computed from the exchange key and the"
				+ " public contact information");
		out.println();
		out.println("Values that are not given are generated and printed.");
	}

	private int runOneToOne(Map<String, String> options,
			List<String> positional, boolean verify, PrintStream out)
			throws Exception {
		byte[] root = optionBytes(options, "root", SecretKey.LENGTH);
		List<Message> messages = new ArrayList<>();
		for (String p : positional) {
			if (p.startsWith("a:") || p.startsWith("b:")) {
				messages.add(new Message(p.startsWith("a:"), p.substring(2)));
			} else if (messages.isEmpty()) {
				throw new IllegalArgumentException(
						"the first message needs an a: or b: prefix");
			} else {
				Message last = messages.remove(messages.size() - 1);
				messages.add(new Message(last.fromAlice, last.text + " " + p));
			}
		}
		if (messages.isEmpty()) {
			messages.add(new Message(true, "Hello Bob"));
			messages.add(new Message(false, "Hello Alice"));
		}
		OneToOneTranscript t = forgeOneToOne(root, messages);
		out.println("Scenario one-to-one: ZWF frames of a Tor connection"
				+ " between two paired phones");
		out.println("Root key both phones hold: " + hex(t.rootKey));
		out.println("Keys either phone derives from it (ZwfSessionFactory):");
		out.println("  Alice to Bob: tag key " + hex(t.aliceTagKey)
				+ " header key " + hex(t.aliceHeaderKey));
		out.println("  Bob to Alice: tag key " + hex(t.bobTagKey)
				+ " header key " + hex(t.bobHeaderKey));
		int n = 0;
		for (Frame f : t.frames) {
			n++;
			StringBuilder line = new StringBuilder();
			line.append("Frame ").append(n).append(", ")
					.append(f.fromAlice ? "Alice to Bob" : "Bob to Alice")
					.append(", ").append(f.bytes.length).append(" bytes");
			if (f.streamId >= 0) {
				line.append(" (tag, stream header and frame of stream ")
						.append(f.streamId).append(")");
			}
			line.append(", ML-KEM secret to the peer's connection key: ")
					.append(f.carriedPqSecret ? "yes" : "no")
					.append(", text \"").append(f.text).append("\"");
			out.println(line);
			out.println(hex(f.bytes));
		}
		out.println("Anyone who holds the root key can produce these bytes:"
				+ " Alice, Bob, or anyone either of them gives the key to."
				+ " A frame therefore proves nothing about who wrote it.");
		if (!verify) return 0;
		boolean ok = verifyOneToOne(t);
		out.println("Verification with the production receiver"
				+ " (ZwfDuplexConnection, ZwfMode3FullStreamDecrypter,"
				+ " ZwfTagRecogniser): every frame was accepted and decrypted"
				+ " to its text, and a recogniser holding only the root key"
				+ " recognised the first frame of each direction: "
				+ (ok ? "OK" : "FAILED"));
		return ok ? 0 : 1;
	}

	private int runMesh(Map<String, String> options, List<String> positional,
			boolean verify, PrintStream out) throws Exception {
		String text = positional.isEmpty() ? "Hello over the mesh"
				: String.join(" ", positional);
		MeshInputs in = randomMeshInputs(
				text.getBytes(StandardCharsets.UTF_8));
		in.linkKey = optionBytes(options, "link-key",
				AsyncSealedSender.LINK_KEY_BYTES, in.linkKey);
		in.senderIdentitySigPub = optionBytes(options, "sender-pub",
				HYBRID_SIGNATURE_PUBLIC_KEY_BYTES, in.senderIdentitySigPub);
		in.recipientIdentitySigPub = optionBytes(options, "recipient-sig-pub",
				HYBRID_SIGNATURE_PUBLIC_KEY_BYTES, in.recipientIdentitySigPub);
		in.recipientIdentityAgreePub = optionBytes(options,
				"recipient-agree-pub", HYBRID_AGREEMENT_PUBLIC_KEY_BYTES,
				in.recipientIdentityAgreePub);
		in.prekeyId = optionBytes(options, "prekey-id",
				AsyncEnvelope.PREKEY_ID_BYTES, in.prekeyId);
		in.dedupId = optionBytes(options, "dedup-id",
				AsyncEnvelope.DEDUP_ID_BYTES, in.dedupId);
		if (options.containsKey("send-time")) {
			in.sendTimestamp = Long.parseLong(options.get("send-time"));
		}
		if (options.containsKey("message-type")) {
			in.messageType = Integer.parseInt(options.get("message-type"));
		}
		if (options.containsKey("ttl")) {
			in.ttl = Long.parseLong(options.get("ttl"));
		}
		if (options.containsKey("recipient-prekey-pub")
				|| options.containsKey("recipient-prekey-priv")) {
			String pub = options.get("recipient-prekey-pub");
			String priv = options.get("recipient-prekey-priv");
			if (pub == null || priv == null) {
				throw new IllegalArgumentException("give both halves of the"
						+ " recipient prekey, the public one to seal and the"
						+ " private one to open");
			}
			KeyParser parser = crypto.getHybridAgreementKeyParser();
			in.recipientPrekey = new KeyPair(
					parser.parsePublicKey(StringUtils.fromHexString(pub)),
					parser.parsePrivateKey(StringUtils.fromHexString(priv)));
		}
		KeyPair prekey = in.recipientPrekey;
		if (prekey == null) throw new IllegalStateException();
		byte[] envelope = forgeMesh(in);
		out.println("Scenario mesh: a V2 sealed-sender envelope with the keyed"
				+ " trailer, as AsyncSealedSender seals it");
		out.println("Mesh link key both phones hold: " + hex(in.linkKey));
		out.println("Sender identity public key named in the record (no"
				+ " private key is used): " + hex(in.senderIdentitySigPub));
		out.println("Send time (milliseconds): " + in.sendTimestamp
				+ ", message type: " + in.messageType + ", time to live: "
				+ in.ttl + " seconds");
		out.println("Text: \"" + text + "\"");
		out.println("Recipient identity signature key: "
				+ hex(in.recipientIdentitySigPub));
		out.println("Recipient identity agreement key: "
				+ hex(in.recipientIdentityAgreePub));
		out.println("Recipient prekey, public: "
				+ hex(prekey.getPublic().getEncoded()));
		out.println("Recipient prekey, private (needed to open): "
				+ hex(prekey.getPrivate().getEncoded()));
		out.println("Prekey kind " + in.prekeyKind + ", prekey id "
				+ hex(in.prekeyId) + ", signed prekey id " + in.signedPrekeyId
				+ ", deduplication id " + hex(in.dedupId));
		out.println("Envelope (" + envelope.length + " bytes):");
		out.println(hex(envelope));
		out.println("The trailer is a MAC under a key derived from the link"
				+ " key. Anyone who holds the link key can seal this envelope"
				+ " naming either phone as the sender, so the envelope proves"
				+ " nothing about who sent it.");
		if (!verify) return 0;
		boolean ok = verifyMesh(in, envelope);
		out.println("Verification with the production code"
				+ " (AsyncSealedSender.open with the link key): the envelope"
				+ " opened as a keyed record with this sender, text, type and"
				+ " send time: " + (ok ? "OK" : "FAILED"));
		return ok ? 0 : 1;
	}

	private int runPairing(Map<String, String> options, boolean verify,
			PrintStream out) throws Exception {
		byte[] exchangeKey = optionBytes(options, "exchange-key",
				SecretKey.LENGTH);
		PairingParty alice = party(options, "alice", "Alice");
		PairingParty bob = party(options, "bob", "Bob");
		PairingValues v = forgePairing(exchangeKey, alice, bob);
		out.println("Scenario pairing: the tag each contact record carries"
				+ " between two 3.0.19 phones (" + exchangeTranscripts.label
				+ ")");
		out.println("Exchange key: " + hex(v.exchangeKey));
		printParty(out, "Alice", v.alice);
		printParty(out, "Bob", v.bob);
		out.println("Session id (from the two ephemeral keys): "
				+ hex(v.sessionId));
		out.println("Alice's tag, MAC(exchange key, role 01, author, ML-DSA"
				+ " key, properties, time, ML-KEM key, session id): "
				+ hex(v.aliceTag));
		out.println("Bob's tag, the same with role 02 and Bob's values: "
				+ hex(v.bobTag));
		out.println("The contact record carries this tag where older phones"
				+ " carried identity signatures; the identity keys sign"
				+ " nothing. Anyone who holds the exchange key computes both"
				+ " tags, so a contact record proves nothing about who paired"
				+ " with whom, and both phones wipe the exchange key when the"
				+ " exchange ends.");
		if (!verify) return 0;
		boolean ok = verifyPairing(v);
		out.println("Verification with the production code"
				+ " (ExchangeTranscript inputs and CryptoComponent.verifyMac"
				+ " under the production label): both tags verify: "
				+ (ok ? "OK" : "FAILED"));
		return ok ? 0 : 1;
	}

	private PairingParty party(Map<String, String> options, String prefix,
			String defaultName) throws FormatException {
		long now = System.currentTimeMillis();
		PairingParty generated = randomPairingParty(
				options.getOrDefault(prefix + "-name", defaultName), now);
		byte[] author = generated.author;
		if (options.containsKey(prefix + "-author")) {
			author = StringUtils.fromHexString(options.get(prefix + "-author"));
		} else if (options.containsKey(prefix + "-ed")) {
			author = encodeAuthor(
					options.getOrDefault(prefix + "-name", defaultName),
					optionBytes(options, prefix + "-ed",
							ED25519_PUBLIC_KEY_BYTES, new byte[0]));
		}
		byte[] properties = options.containsKey(prefix + "-props")
				? StringUtils.fromHexString(options.get(prefix + "-props"))
				: generated.properties;
		long timestamp = options.containsKey(prefix + "-time")
				? Long.parseLong(options.get(prefix + "-time")) : now;
		return new PairingParty(author,
				optionBytes(options, prefix + "-mldsa",
						ML_DSA_65_PUBLIC_KEY_BYTES, generated.mlDsaSigPub),
				properties, timestamp,
				optionBytes(options, prefix + "-pq", B3_PQ_PUB_LEN,
						generated.staticPqPub),
				optionBytes(options, prefix + "-eph", X25519_PUBLIC_KEY_BYTES,
						generated.ephemeral));
	}

	private static void printParty(PrintStream out, String name,
			PairingParty p) {
		out.println(name + "'s author record (BDF list of format version,"
				+ " name and Ed25519 key): " + hex(p.author));
		out.println(name + "'s ML-DSA-65 identity key: " + hex(p.mlDsaSigPub));
		out.println(name + "'s transport properties (BDF dictionary): "
				+ hex(p.properties));
		out.println(name + "'s timestamp (milliseconds): " + p.timestamp);
		out.println(name + "'s static ML-KEM-768 key: " + hex(p.staticPqPub));
		out.println(name + "'s ephemeral X25519 key: " + hex(p.ephemeral));
	}

	private static void requireParty(PairingParty p, String name) {
		requireLength(p.mlDsaSigPub, ML_DSA_65_PUBLIC_KEY_BYTES,
				name + "'s ML-DSA key");
		requireLength(p.staticPqPub, B3_PQ_PUB_LEN, name + "'s ML-KEM key");
		requireLength(p.ephemeral, X25519_PUBLIC_KEY_BYTES,
				name + "'s ephemeral key");
	}

	private byte[] encode(@Nullable BdfList list,
			@Nullable BdfDictionary dictionary) throws FormatException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter writer = bdfWriterFactory.createWriter(out);
		try {
			if (list != null) writer.writeList(list);
			if (dictionary != null) writer.writeDictionary(dictionary);
			writer.flush();
		} catch (FormatException e) {
			throw e;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return out.toByteArray();
	}

	private byte[] randomMlKemPublicKey() {
		byte[] hybrid = crypto.generateHybridAgreementKeyPair().getPublic()
				.getEncoded();
		return Arrays.copyOfRange(hybrid, hybrid.length - B3_PQ_PUB_LEN,
				hybrid.length);
	}

	private byte[] optionBytes(Map<String, String> options, String name,
			int length) throws FormatException {
		return optionBytes(options, name, length, randomBytes(length));
	}

	private static byte[] optionBytes(Map<String, String> options,
			String name, int length, byte[] fallback) throws FormatException {
		String hex = options.get(name);
		if (hex == null) return fallback;
		byte[] b = StringUtils.fromHexString(hex);
		requireLength(b, length, "--" + name);
		return b;
	}

	private static String hex(byte[] b) {
		return StringUtils.toHexString(b).toLowerCase();
	}

	private static void requireLength(byte[] b, int length, String name) {
		if (b.length != length) {
			throw new IllegalArgumentException(name + " must be " + length
					+ " bytes, got " + b.length);
		}
	}

	private ZwfTagRecogniser rootRecogniser(byte[] rootKey) {
		SecretKey root = new SecretKey(rootKey.clone());
		ZwfTagRecogniser recogniser =
				new ZwfTagRecogniser(crypto, REPLAY_WINDOW_SIZE);
		recogniser.register(CONTACT_BOB_SEES_ALICE,
				sessionFactory.deriveRecvTagKey(root, false), 0);
		recogniser.register(CONTACT_ALICE_SEES_BOB,
				sessionFactory.deriveRecvTagKey(root, true), 0);
		return recogniser;
	}

	private static CryptoComponent newCryptoComponent()
			throws ReflectiveOperationException {
		Class<?> impl = Class.forName(
				"org.zerionproject.core.crypto.CryptoComponentImpl");
		Constructor<?> c = impl.getDeclaredConstructor(
				SecureRandomProvider.class,
				Class.forName("org.zerionproject.core.crypto.PasswordBasedKdf"));
		c.setAccessible(true);
		SecureRandomProvider provider = () -> null;
		Object kdf = null;
		return (CryptoComponent) c.newInstance(provider, kdf);
	}

	private static Mode3FullRatchet newMode3FullRatchet(CryptoComponent crypto)
			throws ReflectiveOperationException {
		Class<?> providerImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.MlKemProviderImpl");
		Constructor<?> providerCtor =
				providerImpl.getDeclaredConstructor(SecureRandom.class);
		providerCtor.setAccessible(true);
		MlKemProvider mlKemProvider = (MlKemProvider) providerCtor
				.newInstance(crypto.getSecureRandom());
		Class<?> ratchetImpl = Class.forName(
				"org.zerionproject.core.crypto.pcs.Mode3FullRatchetImpl");
		Constructor<?> ratchetCtor = ratchetImpl.getDeclaredConstructor(
				CryptoComponent.class, MlKemProvider.class);
		ratchetCtor.setAccessible(true);
		return (Mode3FullRatchet) ratchetCtor.newInstance(crypto,
				mlKemProvider);
	}

	private static BdfWriterFactory newBdfWriterFactory()
			throws ReflectiveOperationException {
		Class<?> impl = Class.forName(
				"org.zerionproject.core.data.BdfWriterFactoryImpl");
		Constructor<?> c = impl.getDeclaredConstructor();
		c.setAccessible(true);
		return (BdfWriterFactory) c.newInstance();
	}

	private static final class ExchangeTranscripts {

		private final Constructor<?> constructor;
		private final Method inputs;
		final String label;
		final int tagLength;

		ExchangeTranscripts() throws ReflectiveOperationException {
			Class<?> transcript = Class.forName(
					"org.zerionproject.core.contact.ExchangeTranscript");
			constructor = transcript.getDeclaredConstructor(byte[].class,
					byte[].class, byte[].class, long.class, byte[].class,
					byte[].class);
			constructor.setAccessible(true);
			inputs = transcript.getDeclaredMethod("inputs", boolean.class);
			inputs.setAccessible(true);
			Class<?> constants = Class.forName(
					"org.zerionproject.core.contact.ContactExchangeConstants");
			Field labelField = constants.getDeclaredField("EXCHANGE_TAG_LABEL");
			labelField.setAccessible(true);
			label = (String) labelField.get(null);
			Field lengthField =
					constants.getDeclaredField("EXCHANGE_TAG_LENGTH");
			lengthField.setAccessible(true);
			tagLength = (Integer) lengthField.get(null);
		}

		byte[][] inputs(PairingParty p, byte[] sessionId, boolean alice)
				throws ReflectiveOperationException {
			Object transcript = constructor.newInstance(p.author,
					p.mlDsaSigPub, p.properties, p.timestamp, p.staticPqPub,
					sessionId);
			return (byte[][]) inputs.invoke(transcript, alice);
		}
	}

	private static final class MemoryCounterStore implements StreamCounterStore {

		private final Map<Long, Long> highWater = new HashMap<>();

		@Override
		public synchronized long loadHighWater(int contactId, int direction) {
			Long v = highWater.get(key(contactId, direction));
			return v == null ? 0 : v;
		}

		@Override
		public synchronized void storeHighWater(int contactId, int direction,
				long value) {
			highWater.put(key(contactId, direction), value);
		}

		private static long key(int contactId, int direction) {
			return (((long) contactId) << 1) | (direction & 1L);
		}
	}

	private static final class ByteQueue extends InputStream {

		private byte[] buf = new byte[FIRST_FRAME_BYTES];
		private int head, tail;

		synchronized void append(byte[] b, int off, int len) {
			if (tail + len > buf.length) {
				int pending = tail - head;
				byte[] grown = new byte[Math.max(buf.length * 2, pending + len)];
				System.arraycopy(buf, head, grown, 0, pending);
				buf = grown;
				head = 0;
				tail = pending;
			}
			System.arraycopy(b, off, buf, tail, len);
			tail += len;
		}

		@Override
		public synchronized int read() {
			if (head == tail) return -1;
			return buf[head++] & 0xFF;
		}

		@Override
		public synchronized int read(byte[] b, int off, int len) {
			if (len == 0) return 0;
			if (head == tail) return -1;
			int n = Math.min(len, tail - head);
			System.arraycopy(buf, head, b, off, n);
			head += n;
			return n;
		}

		@Override
		public synchronized int available() {
			return tail - head;
		}
	}

	private static final class Capture extends OutputStream {

		private final ByteQueue queue;
		private final ByteArrayOutputStream copy = new ByteArrayOutputStream();

		Capture(ByteQueue queue) {
			this.queue = queue;
		}

		@Override
		public void write(int b) {
			queue.append(new byte[] {(byte) b}, 0, 1);
			copy.write(b);
		}

		@Override
		public void write(byte[] b, int off, int len) {
			queue.append(b, off, len);
			copy.write(b, off, len);
		}

		byte[] take() {
			byte[] out = copy.toByteArray();
			copy.reset();
			return out;
		}
	}
}
