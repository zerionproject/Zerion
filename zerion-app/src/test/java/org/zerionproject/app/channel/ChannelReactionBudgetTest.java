package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChannelReactionBudgetTest {

	private static final int ML_DSA_PUB = 1952;
	private static final int HYBRID_SIG = 3373;

	private static byte[] signer(int i) {
		byte[] b = new byte[32];
		b[0] = (byte) i;
		b[1] = (byte) (i >> 8);
		return b;
	}

	private static ChannelReaction realistic(long post, int signer, long ts) {
		return new ChannelReaction(post, "❤", signer(signer),
				new byte[ML_DSA_PUB], ts, new byte[HYBRID_SIG]);
	}

	@Test
	public void theByteCeilingBindsBeforeTheCountForOversizedReactions() {
		List<ChannelReaction> set = new ArrayList<>();
		ChannelReaction last = null;
		for (int i = 0; i < 60; i++) {
			last = new ChannelReaction(i, "x", signer(i),
					new byte[ML_DSA_PUB], i, new byte[64 * 1024]);
			List<ChannelReaction> next =
					ChannelReactionPolicy.withAdmitted(set, last);
			assertTrue("every reaction is taken in", next != null);
			set = next;
		}
		assertTrue("the byte ceiling held, not the count",
				set.size() < ChannelConstants.MAX_REACTIONS_PER_CHANNEL);
		assertTrue(ChannelReactionPolicy.storedBytes(set)
				<= ChannelConstants.MAX_REACTION_BYTES_PER_CHANNEL);
		assertSame(last, set.get(set.size() - 1));
	}

	@Test
	public void realisticReactionsStayAtTheCountWithinTheByteCeiling() {
		List<ChannelReaction> set = new ArrayList<>();
		ChannelReaction last = null;
		for (int i = 0; i < 300; i++) {
			last = realistic(i % 200, i, i);
			set = ChannelReactionPolicy.withAdmitted(set, last);
		}
		assertEquals(ChannelConstants.MAX_REACTIONS_PER_CHANNEL, set.size());
		assertTrue(ChannelReactionPolicy.storedBytes(set)
				<= ChannelConstants.MAX_REACTION_BYTES_PER_CHANNEL);
		assertSame(last, set.get(set.size() - 1));
	}

	@Test
	public void legacyStateIsTrimmedToTheNewestReactionsWithinEveryCeiling() {
		List<ChannelReaction> legacy = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			legacy.add(realistic(i % 7, i % 50, i));
		}
		List<ChannelReaction> kept =
				ChannelReactionPolicy.fitToCeilings(legacy);
		assertTrue(kept.size() <= ChannelConstants.MAX_REACTIONS_PER_CHANNEL);
		assertTrue(ChannelReactionPolicy.storedBytes(kept)
				<= ChannelConstants.MAX_REACTION_BYTES_PER_CHANNEL);
		Map<Long, Integer> perPost = new HashMap<>();
		Map<Integer, Integer> perSigner = new HashMap<>();
		long lastTs = -1;
		for (ChannelReaction r : kept) {
			perPost.merge(r.getPostSeqNum(), 1, Integer::sum);
			perSigner.merge(r.getSignerEd25519PubKey()[0] & 0xff
					| (r.getSignerEd25519PubKey()[1] & 0xff) << 8, 1,
					Integer::sum);
			assertTrue("original order kept", r.getTimestampHourMs() > lastTs);
			lastTs = r.getTimestampHourMs();
			assertTrue("the newest are kept", r.getTimestampHourMs() >= 1000
					- 2L * ChannelConstants.MAX_REACTIONS_PER_CHANNEL);
		}
		for (int n : perPost.values()) {
			assertTrue(n <= ChannelConstants.MAX_REACTIONS_PER_POST);
		}
		for (int n : perSigner.values()) {
			assertTrue(n <= ChannelConstants.MAX_REACTIONS_PER_SIGNER_PER_CHANNEL);
		}
	}

	@Test
	public void stateWithinTheCeilingsIsReturnedUntouched() {
		List<ChannelReaction> small = new ArrayList<>();
		for (int i = 0; i < 10; i++) small.add(realistic(i, i, i));
		assertSame(small, ChannelReactionPolicy.fitToCeilings(small));
	}
}
