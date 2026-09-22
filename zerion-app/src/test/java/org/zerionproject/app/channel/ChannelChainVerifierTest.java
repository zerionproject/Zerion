package org.zerionproject.app.channel;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;

/**
 * The channel hash chain: an ordered run of posts is accepted only when every
 * post names the canonical hash of its predecessor and the sequence numbers
 * are contiguous; a genesis post must name the zero hash; changing any field
 * of a post, including its signature, breaks the link the next post carries.
 */
public class ChannelChainVerifierTest {

	private final Random random = new Random(79);
	private final byte[] channelId = new byte[32];

	private ChannelCodec codec;
	private ChannelChainVerifier verifier;

	@Before
	public void setUp() {
		CryptoComponent crypto = DaggerChannelCryptoTestComponent.create()
				.getCryptoComponent();
		codec = new ChannelCodec(crypto);
		verifier = new ChannelChainVerifier(codec);
		random.nextBytes(channelId);
	}

	@Test
	public void aWellFormedChainFromGenesisIsAccepted() {
		assertEquals(ChannelChainVerifier.Result.OK,
				verifier.verifyOrdered(chain(0, 6)));
		assertEquals(ChannelChainVerifier.Result.OK,
				verifier.verifyOrdered(chain(0, 1)));
	}

	@Test
	public void aChainThatStartsMidwayIsCheckedFromItsOwnFirstLink() {
		List<ChannelPost> full = chain(0, 8);
		assertEquals(ChannelChainVerifier.Result.OK,
				verifier.verifyOrdered(full.subList(3, 8)));
	}

	@Test
	public void anEmptyRunIsReportedAsEmpty() {
		assertEquals(ChannelChainVerifier.Result.EMPTY,
				verifier.verifyOrdered(Collections.<ChannelPost>emptyList()));
	}

	@Test
	public void aGenesisPostMustNameTheZeroHash() {
		byte[] notZero = new byte[ChannelConstants.PREV_HASH_BYTES];
		notZero[5] = 1;
		ChannelPost genesis = post(0, notZero, "hello");
		assertEquals(ChannelChainVerifier.Result.HASH_CHAIN_BROKEN,
				verifier.verifyOrdered(Collections.singletonList(genesis)));
	}

	@Test
	public void aSkippedOrRepeatedSequenceNumberBreaksTheChain() {
		List<ChannelPost> chain = chain(0, 5);
		List<ChannelPost> skipped = new ArrayList<>(chain);
		skipped.remove(2);
		assertEquals(ChannelChainVerifier.Result.SEQUENCE_BREAK,
				verifier.verifyOrdered(skipped));
		List<ChannelPost> repeated = new ArrayList<>(chain);
		repeated.add(2, chain.get(2));
		assertEquals(ChannelChainVerifier.Result.SEQUENCE_BREAK,
				verifier.verifyOrdered(repeated));
	}

	@Test
	public void everyFieldOfAPostIsBoundIntoTheNextLink() {
		List<ChannelPost> chain = chain(0, 4);
		ChannelPost p = chain.get(1);
		ChannelPost[] variants = {
				new ChannelPost(p.getChannelId(), p.getSeqNum(),
						p.getPrevHash(), p.getTimestampHourMs(),
						p.getBody() + "!", p.getAttachments(), p.getTtlMs(),
						p.getSignature(), false),
				new ChannelPost(p.getChannelId(), p.getSeqNum(),
						p.getPrevHash(), p.getTimestampHourMs() + 1,
						p.getBody(), p.getAttachments(), p.getTtlMs(),
						p.getSignature(), false),
				new ChannelPost(p.getChannelId(), p.getSeqNum(),
						p.getPrevHash(), p.getTimestampHourMs(),
						p.getBody(), p.getAttachments(), p.getTtlMs() + 1,
						p.getSignature(), false),
				new ChannelPost(p.getChannelId(), p.getSeqNum(),
						p.getPrevHash(), p.getTimestampHourMs(),
						p.getBody(), p.getAttachments(), p.getTtlMs(),
						flipped(p.getSignature()), false),
				new ChannelPost(flipped(p.getChannelId()), p.getSeqNum(),
						p.getPrevHash(), p.getTimestampHourMs(),
						p.getBody(), p.getAttachments(), p.getTtlMs(),
						p.getSignature(), false),
		};
		for (ChannelPost v : variants) {
			List<ChannelPost> tampered = new ArrayList<>(chain);
			tampered.set(1, v);
			assertEquals(ChannelChainVerifier.Result.HASH_CHAIN_BROKEN,
					verifier.verifyOrdered(tampered));
		}
	}

	@Test
	public void aPostNamingTheWrongPredecessorIsRefused() {
		List<ChannelPost> chain = chain(0, 4);
		ChannelPost p = chain.get(2);
		List<ChannelPost> tampered = new ArrayList<>(chain);
		tampered.set(2, new ChannelPost(p.getChannelId(), p.getSeqNum(),
				flipped(p.getPrevHash()), p.getTimestampHourMs(),
				p.getBody(), p.getAttachments(), p.getTtlMs(),
				p.getSignature(), false));
		assertEquals(ChannelChainVerifier.Result.HASH_CHAIN_BROKEN,
				verifier.verifyOrdered(tampered));
	}

	@Test
	public void randomChainsAreAcceptedAndRandomSingleEditsAreRefused() {
		for (int round = 0; round < 60; round++) {
			int len = 2 + random.nextInt(8);
			List<ChannelPost> chain = chain(0, len);
			assertEquals(ChannelChainVerifier.Result.OK,
					verifier.verifyOrdered(chain));
			int victim = random.nextInt(len - 1);
			ChannelPost p = chain.get(victim);
			List<ChannelPost> tampered = new ArrayList<>(chain);
			tampered.set(victim, new ChannelPost(p.getChannelId(),
					p.getSeqNum(), p.getPrevHash(), p.getTimestampHourMs(),
					p.getBody() + (char) ('a' + random.nextInt(26)),
					p.getAttachments(), p.getTtlMs(), p.getSignature(),
					false));
			assertEquals("round " + round,
					ChannelChainVerifier.Result.HASH_CHAIN_BROKEN,
					verifier.verifyOrdered(tampered));
		}
	}

	private List<ChannelPost> chain(int from, int to) {
		List<ChannelPost> posts = new ArrayList<>();
		byte[] prev = new byte[ChannelConstants.PREV_HASH_BYTES];
		for (int seq = from; seq < to; seq++) {
			ChannelPost p = post(seq, prev, "post " + seq);
			posts.add(p);
			prev = verifier.hashOf(p);
		}
		return posts;
	}

	private ChannelPost post(long seq, byte[] prev, String body) {
		byte[] sig = new byte[64];
		random.nextBytes(sig);
		return new ChannelPost(channelId, seq, prev, 3_600_000L * (seq + 1),
				body, Collections.<ChannelPost.ChannelAttachment>emptyList(),
				0L, sig, false);
	}

	private static byte[] flipped(byte[] in) {
		byte[] out = in.clone();
		out[out.length / 2] ^= 0x01;
		return out;
	}
}
