package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfWriter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelRequestProofTest {

	private static final long HOUR = ChannelTestNode.HOUR;
	private static final String PROOF_LABEL =
			"org.zerionproject/CHANNEL_REQUEST_PROOF_V2";
	private static final String LEGACY_LABEL =
			"org.zerionproject/CHANNEL_HMAC_CHALLENGE";

	private final Random random = new Random(182);
	private CryptoComponent crypto;
	private ChannelTestNode publisher;
	private ChannelTestNode.TestChannel channel;
	private byte[] capability;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		channel = new ChannelTestNode.TestChannel(crypto, random);
		publisher = new ChannelTestNode(crypto,
				new ChannelTestNode.MutableClock(),
				ChannelTestNode.noTransport());
		capability = new byte[32];
		random.nextBytes(capability);
		byte[] kContent = new byte[32];
		random.nextBytes(kContent);
		publisher.seedPublisher(channel, false, capability, kContent, false,
				publisher.posts(channel, 2));
	}

	@After
	public void tearDown() {
		publisher.deleteFiles();
	}

	@Test
	public void aProofTakenFromOneRequestDoesNotAdmitAnother()
			throws Exception {
		byte[] nonce = nonce();
		KeyPair author = crypto.generateHybridSignatureKeyPair();
		KeyPair other = crypto.generateHybridSignatureKeyPair();
		BdfDictionary genuine = comment(author, "hello");
		byte[] legacy = legacyProof(nonce);
		byte[] covering = coveringProof(genuine, nonce);
		BdfDictionary substituted = comment(other, "taken over");
		addProofs(substituted, nonce, legacy, covering);
		assertFalse("a request with another body was admitted",
				ChannelTestNode.ackOk(publisher.handle(channel.channelId,
						write(substituted))));
		byte[] n2 = nonce();
		BdfDictionary fresh = comment(author, "hello again");
		addProofs(fresh, n2, legacyProof(n2), coveringProof(fresh, n2));
		assertTrue(ChannelTestNode.ackOk(publisher.handle(channel.channelId,
				write(fresh))));
	}

	@Test
	public void aFailedProofDoesNotUseUpTheNonce() throws Exception {
		byte[] nonce = nonce();
		KeyPair author = crypto.generateHybridSignatureKeyPair();
		BdfDictionary forged = comment(author, "forged");
		byte[] bad = new byte[32];
		random.nextBytes(bad);
		addProofs(forged, nonce, bad, bad);
		assertFalse(ChannelTestNode.ackOk(publisher.handle(channel.channelId,
				write(forged))));
		BdfDictionary genuine = comment(author, "genuine");
		addProofs(genuine, nonce, legacyProof(nonce),
				coveringProof(genuine, nonce));
		assertTrue("the genuine request was refused for a burnt nonce",
				ChannelTestNode.ackOk(publisher.handle(channel.channelId,
						write(genuine))));
	}

	private byte[] nonce() {
		byte[] n = new byte[16];
		random.nextBytes(n);
		return n;
	}

	private BdfDictionary comment(KeyPair author, String body)
			throws Exception {
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) author.getPrivate();
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) author.getPublic();
		long ts = publisher.clock.currentTimeMillis() / HOUR * HOUR;
		long id = random.nextLong();
		byte[] sig = publisher.signatures.signUserComment(
				publisher.codec.commentSignedInput(channel.channelId, 1L, id,
						body, "", ts),
				priv.getEd25519Component(), priv.getMlDsaPrivateKey());
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_POST_COMMENT);
		d.put("v", 2L);
		d.put("channelId", channel.channelId);
		d.put("seq", 1L);
		d.put("id", id);
		d.put("body", body);
		d.put("name", "");
		d.put("ts", ts);
		d.put("ed", pub.getEd25519PublicKey());
		d.put("ml", pub.getMlDsaPublicKey());
		d.put("sig", sig);
		return d;
	}

	private byte[] legacyProof(byte[] nonce) {
		return crypto.mac(LEGACY_LABEL, new SecretKey(capability),
				channel.channelId, nonce);
	}

	private byte[] coveringProof(BdfDictionary request, byte[] nonce)
			throws Exception {
		return crypto.mac(PROOF_LABEL, new SecretKey(capability),
				channel.channelId, nonce, write(request));
	}

	private static void addProofs(BdfDictionary d, byte[] nonce,
			@javax.annotation.Nullable byte[] legacy,
			@javax.annotation.Nullable byte[] covering) {
		d.put("nonce", nonce);
		if (legacy != null) d.put("hmac", legacy);
		if (covering != null) d.put("hmac2", covering);
	}

	private static byte[] write(BdfDictionary d) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = DaggerChannelCodecTestComponent.create()
				.getBdfWriterFactory().createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}
}
