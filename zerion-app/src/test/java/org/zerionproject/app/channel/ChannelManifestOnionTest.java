package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelManifestOnionTest {

	private static final long HOUR = 3_600_000L;
	private static final String V3 =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";

	private final Random random = new Random(318);
	private ChannelCodec codec;
	private ChannelPullCodec pullCodec;
	private ChannelSignatures signatures;
	private ChannelPullProtocol protocol;
	private KeyPair publisher;
	private byte[] salt;
	private byte[] channelId;

	@Before
	public void setUp() {
		CryptoComponent crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		ChannelCodecTestComponent bdf =
				DaggerChannelCodecTestComponent.create();
		codec = new ChannelCodec(crypto);
		pullCodec = new ChannelPullCodec(bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		signatures = new ChannelSignatures(crypto);
		ChannelChainVerifier chain = new ChannelChainVerifier(codec);
		protocol = new ChannelPullProtocol(codec, pullCodec,
				new ChannelHmacChallenge(crypto),
				new ChannelContentKey(crypto),
				new ChannelPostValidator(codec, signatures, chain),
				signatures, crypto);
		publisher = crypto.generateHybridSignatureKeyPair();
		salt = new byte[32];
		random.nextBytes(salt);
		channelId = crypto.hash("org.zerionproject/CHANNEL_ID",
				publisher.getPublic().getEncoded(), salt);
	}

	@Test
	public void aV3OnionIsAdopted() throws Exception {
		ChannelPullProtocol.ProcessResult r = merge(V3);
		assertTrue(r.ok);
		assertEquals(V3, r.mergedState.getCurrentOnion());
	}

	@Test
	public void anEmptyOnionIsAccepted() throws Exception {
		assertTrue(merge("").ok);
	}

	@Test
	public void anOnionThatIsNotV3IsRefusedAtMerge() throws Exception {
		assertFalse("a non-v3 onion was adopted", merge("publisher").ok);
		assertFalse(merge("example.com").ok);
		assertFalse(merge(V3.substring(1)).ok);
	}

	private ChannelPullProtocol.ProcessResult merge(String onion)
			throws Exception {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) publisher.getPublic();
		List<ChannelDelegationCert> none = Collections.emptyList();
		List<Long> revoked = Collections.emptyList();
		byte[] input = codec.manifestSignedInput(channelId, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "name",
				"description", null, HOUR, true, null, onion, 2L, null, none,
				revoked, ChannelState.NO_PINNED_POST, false, true);
		byte[] sig = signatures.signManifest(input,
				(HybridSignaturePrivateKey) publisher.getPrivate());
		BdfDictionary manifest = pullCodec.encodeManifest(channelId, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "name",
				"description", null, HOUR, true, null, onion, 2L, null, none,
				revoked, ChannelState.NO_PINNED_POST, false, true, sig);
		byte[] response = pullCodec.encodePullResponse(manifest,
				Collections.<ChannelPost>emptyList(), null,
				Collections.<String>emptyList(),
				Collections.<ChannelReaction>emptyList(),
				Collections.<ChannelComment>emptyList());
		ChannelState local = new ChannelState(channelId, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "", "",
				null, HOUR, true, null, V3, 1L, false, -1L, null, null,
				none, revoked, 0L);
		return protocol.processSubscriberResponse(response, local,
				Collections.<ChannelPost>emptyList(), null);
	}
}
