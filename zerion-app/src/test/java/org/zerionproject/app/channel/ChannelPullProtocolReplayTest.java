package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelState;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * A subscriber processing a publisher's pull response: posts already held
 * are skipped rather than re-accepted, a post that breaks the chain or
 * carries a bad signature stops acceptance at that point, and a manifest is
 * merged only when it is for this channel, from this publisher, signed by
 * the publisher and newer than the one held.
 */
public class ChannelPullProtocolReplayTest {

	private static final long HOUR = 3_600_000L;

	private final Random random = new Random(101);

	private CryptoComponent crypto;
	private ChannelCodec codec;
	private ChannelPullCodec pullCodec;
	private ChannelSignatures signatures;
	private ChannelChainVerifier chain;
	private ChannelPullProtocol protocol;
	private KeyPair publisher;
	private KeyPair stranger;
	private byte[] salt;
	private byte[] channelId;
	private ChannelState local;

	@Before
	public void setUp() {
		crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		ChannelCodecTestComponent bdf = DaggerChannelCodecTestComponent
				.create();
		codec = new ChannelCodec(crypto);
		pullCodec = new ChannelPullCodec(bdf.getBdfReaderFactory(),
				bdf.getBdfWriterFactory());
		signatures = new ChannelSignatures(crypto);
		chain = new ChannelChainVerifier(codec);
		ChannelPostValidator validator = new ChannelPostValidator(codec,
				signatures, chain);
		protocol = new ChannelPullProtocol(codec, pullCodec,
				new ChannelHmacChallenge(crypto),
				new ChannelContentKey(crypto), validator, signatures,
				crypto);
		publisher = crypto.generateHybridSignatureKeyPair();
		stranger = crypto.generateHybridSignatureKeyPair();
		salt = new byte[32];
		random.nextBytes(salt);
		channelId = crypto.hash("org.zerionproject/CHANNEL_ID",
				publisher.getPublic().getEncoded(), salt);
		local = state(1L);
	}

	@Test
	public void freshPostsContinuingTheChainAreAcceptedInOrder()
			throws Exception {
		List<ChannelPost> posts = chain(publisher, 0, 4);
		ChannelPullProtocol.ProcessResult r = protocol
				.processSubscriberResponse(response(manifest(publisher,
						publisher, 2L, channelId), posts), local,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(r.error, r.ok);
		assertEquals(4, r.acceptedPosts.size());
		for (int i = 0; i < 4; i++) {
			assertEquals(i, r.acceptedPosts.get(i).getSeqNum());
		}
		assertEquals(2L, r.mergedState.getManifestSeq());
	}

	@Test
	public void postsAlreadyHeldAreSkippedNotReAccepted() throws Exception {
		List<ChannelPost> posts = chain(publisher, 0, 6);
		List<ChannelPost> held = posts.subList(0, 3);
		ChannelPullProtocol.ProcessResult r = protocol
				.processSubscriberResponse(response(manifest(publisher,
						publisher, 1L, channelId), posts), local, held, null);
		assertTrue(r.error, r.ok);
		assertEquals(3, r.acceptedPosts.size());
		assertEquals(3L, r.acceptedPosts.get(0).getSeqNum());
		assertEquals(5L, r.acceptedPosts.get(2).getSeqNum());
		ChannelPullProtocol.ProcessResult again = protocol
				.processSubscriberResponse(response(manifest(publisher,
						publisher, 1L, channelId), posts.subList(0, 3)),
						local, held, null);
		assertTrue(again.ok);
		assertTrue(again.acceptedPosts.isEmpty());
	}

	@Test
	public void aReplayedOlderPostInsideAResponseStopsAcceptanceThere()
			throws Exception {
		List<ChannelPost> posts = chain(publisher, 0, 4);
		List<ChannelPost> replayed = new ArrayList<>(posts);
		replayed.add(2, posts.get(0));
		ChannelPullProtocol.ProcessResult r = protocol
				.processSubscriberResponse(response(manifest(publisher,
						publisher, 1L, channelId), replayed), local,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(r.error, r.ok);
		assertEquals(2, r.acceptedPosts.size());
		assertEquals(0L, r.acceptedPosts.get(0).getSeqNum());
		assertEquals(1L, r.acceptedPosts.get(1).getSeqNum());
	}

	@Test
	public void aBrokenLinkStopsAcceptanceAtThatPost() throws Exception {
		List<ChannelPost> posts = chain(publisher, 0, 5);
		ChannelPost p2 = posts.get(2);
		byte[] wrongPrev = p2.getPrevHash().clone();
		wrongPrev[7] ^= 1;
		posts.set(2, post(publisher, 2, wrongPrev, "forged"));
		ChannelPullProtocol.ProcessResult r = protocol
				.processSubscriberResponse(response(manifest(publisher,
						publisher, 1L, channelId), posts), local,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(r.error, r.ok);
		assertEquals(2, r.acceptedPosts.size());
	}

	@Test
	public void aPostSignedByAStrangerStopsAcceptanceAtThatPost()
			throws Exception {
		List<ChannelPost> posts = chain(publisher, 0, 3);
		ChannelPost p1 = posts.get(1);
		posts.set(1, post(stranger, 1, p1.getPrevHash(), p1.getBody()));
		ChannelPullProtocol.ProcessResult r = protocol
				.processSubscriberResponse(response(manifest(publisher,
						publisher, 1L, channelId), posts), local,
						Collections.<ChannelPost>emptyList(), null);
		assertTrue(r.error, r.ok);
		assertEquals(1, r.acceptedPosts.size());
	}

	@Test
	public void anOlderOrEqualManifestLeavesTheLocalStateUntouched()
			throws Exception {
		ChannelState held = state(5L);
		for (long seq : new long[] {5L, 4L, 0L}) {
			ChannelPullProtocol.ProcessResult r = protocol
					.processSubscriberResponse(response(manifest(publisher,
							publisher, seq, channelId), chain(publisher, 0, 1)),
							held, Collections.<ChannelPost>emptyList(), null);
			assertTrue(r.error, r.ok);
			assertSame(held, r.mergedState);
			assertEquals(1, r.acceptedPosts.size());
		}
	}

	@Test
	public void aManifestSignedByAnyoneButThePublisherIsRefused()
			throws Exception {
		ChannelPullProtocol.ProcessResult r = protocol
				.processSubscriberResponse(response(manifest(publisher,
						stranger, 2L, channelId), chain(publisher, 0, 1)),
						local, Collections.<ChannelPost>emptyList(), null);
		assertFalse(r.ok);
		assertTrue(r.acceptedPosts.isEmpty());
	}

	@Test
	public void aManifestForAnotherPublisherOrChannelIsRefused()
			throws Exception {
		ChannelPullProtocol.ProcessResult other = protocol
				.processSubscriberResponse(response(manifest(stranger,
						stranger, 2L, channelId), chain(publisher, 0, 1)),
						local, Collections.<ChannelPost>emptyList(), null);
		assertFalse(other.ok);
		byte[] otherId = channelId.clone();
		otherId[0] ^= 1;
		ChannelPullProtocol.ProcessResult renamed = protocol
				.processSubscriberResponse(response(manifest(publisher,
						publisher, 2L, otherId), chain(publisher, 0, 1)),
						local, Collections.<ChannelPost>emptyList(), null);
		assertFalse(renamed.ok);
	}

	@Test
	public void garbageAndTruncatedResponsesFailCleanly() throws Exception {
		byte[] good = response(manifest(publisher, publisher, 2L, channelId),
				chain(publisher, 0, 2));
		for (int i = 0; i < 200; i++) {
			byte[] bad;
			if (i % 2 == 0) {
				bad = Arrays.copyOf(good, random.nextInt(good.length));
			} else {
				bad = good.clone();
				bad[random.nextInt(bad.length)] ^= (byte) (1 << random.nextInt(8));
			}
			ChannelPullProtocol.ProcessResult r;
			try {
				r = protocol.processSubscriberResponse(bad, local,
						Collections.<ChannelPost>emptyList(), null);
			} catch (RuntimeException e) {
				throw new AssertionError("input " + i + ": " + e, e);
			}
			if (r.ok) {
				assertTrue(r.mergedState == local
						|| r.mergedState.getManifestSeq() == 2L);
				assertTrue(r.acceptedPosts.size() <= 2);
			}
		}
	}

	@Test
	public void thePublisherAnswersWithOneOrderedBoundedBatch()
			throws Exception {
		List<ChannelPost> posts = chain(publisher, 0, 12);
		List<ChannelPost> shuffled = new ArrayList<>(posts);
		java.util.Collections.shuffle(shuffled, random);
		List<ChannelPost> batch = ChannelManagerImpl.nextBatch(shuffled, 2L,
				4);
		assertEquals(4, batch.size());
		for (int i = 0; i < 4; i++) {
			assertEquals(3L + i, batch.get(i).getSeqNum());
		}
		assertEquals(0, ChannelManagerImpl.nextBatch(shuffled, 11L, 4).size());
		assertEquals(12, ChannelManagerImpl.nextBatch(shuffled, -1L, 100)
				.size());
	}

	private ChannelState state(long manifestSeq) {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) publisher.getPublic();
		return new ChannelState(channelId, salt, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), "name", "description", null, HOUR,
				true, null, "", manifestSeq, false, -1L, null, null,
				Collections.<ChannelDelegationCert>emptyList(),
				Collections.<Long>emptyList(), 0L);
	}

	private BdfDictionary manifest(KeyPair claimed, KeyPair signer,
			long manifestSeq, byte[] id) throws Exception {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) claimed.getPublic();
		List<ChannelDelegationCert> none =
				Collections.<ChannelDelegationCert>emptyList();
		List<Long> revoked = Collections.<Long>emptyList();
		byte[] input = codec.manifestSignedInput(id, salt,
				pub.getEd25519PublicKey(), pub.getMlDsaPublicKey(), "name",
				"description", null, HOUR, true, null, "", manifestSeq,
				null, none, revoked, ChannelState.NO_PINNED_POST, false,
				true);
		byte[] sig = signatures.signManifest(input,
				(HybridSignaturePrivateKey) signer.getPrivate());
		return pullCodec.encodeManifest(id, salt, pub.getEd25519PublicKey(),
				pub.getMlDsaPublicKey(), "name", "description", null, HOUR,
				true, null, "", manifestSeq, null, none, revoked,
				ChannelState.NO_PINNED_POST, false, true, sig);
	}

	private byte[] response(BdfDictionary manifest, List<ChannelPost> posts)
			throws Exception {
		return pullCodec.encodePullResponse(manifest, posts, null,
				Collections.<String>emptyList(),
				Collections.<ChannelReaction>emptyList(),
				Collections.<ChannelComment>emptyList());
	}

	private List<ChannelPost> chain(KeyPair signer, int from, int to)
			throws Exception {
		List<ChannelPost> posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = from; seq < to; seq++) {
			ChannelPost p = post(signer, seq, prev, "post " + seq);
			posts.add(p);
			prev = chain.hashOf(p);
		}
		return posts;
	}

	private ChannelPost post(KeyPair signer, long seq, byte[] prev,
			String body) throws Exception {
		long ts = HOUR * (seq + 2);
		List<ChannelPost.ChannelAttachment> none =
				Collections.<ChannelPost.ChannelAttachment>emptyList();
		byte[] input = codec.postSignedInput(channelId, seq, prev, ts, body,
				codec.attachmentsHash(none), 0L);
		byte[] sig = signatures.signPost(input,
				(HybridSignaturePrivateKey) signer.getPrivate());
		return new ChannelPost(channelId, seq, prev, ts, body, none, 0L, sig,
				false);
	}
}
