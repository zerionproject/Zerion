package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.channel.ChannelReactionPolicy.Verdict;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * EXT-13-F05: a valid signature must not be enough to grow a channel's
 * stored state. Reactions for posts that do not exist are refused, and
 * the per-post, per-signer and channel-wide ceilings hold.
 */
public class ChannelReactionPolicyTest {

	private static byte[] signer(int i) {
		byte[] k = new byte[32];
		k[0] = (byte) (i >> 8);
		k[1] = (byte) i;
		return k;
	}

	private static ChannelReaction reaction(long post, byte[] signer) {
		return new ChannelReaction(post, "+1", signer, new byte[4], 0);
	}

	private static Set<Long> posts(long... seqs) {
		Set<Long> s = new HashSet<>();
		for (long q : seqs) s.add(q);
		return s;
	}

	@Test
	public void aReactionForAnAbsentPostIsRefused() {
		List<ChannelReaction> none = new ArrayList<>();
		assertEquals(Verdict.NO_SUCH_POST,
				ChannelReactionPolicy.admit(none, posts(1, 2, 3), 4, signer(1)));
		assertEquals(Verdict.NO_SUCH_POST,
				ChannelReactionPolicy.admit(none, posts(), 1, signer(1)));
		assertEquals(Verdict.NO_SUCH_POST,
				ChannelReactionPolicy.admit(none, posts(1), 0, signer(1)));
		assertEquals(Verdict.ADMIT,
				ChannelReactionPolicy.admit(none, posts(1, 2, 3), 2, signer(1)));
	}

	@Test
	public void aDeletedPostNoLongerAcceptsReactions() {
		List<ChannelReaction> existing = new ArrayList<>();
		existing.add(reaction(5, signer(1)));
		assertEquals(Verdict.NO_SUCH_POST,
				ChannelReactionPolicy.admit(existing, posts(1, 2), 5, signer(2)));
	}

	@Test
	public void thePerPostCeilingHolds() {
		List<ChannelReaction> existing = new ArrayList<>();
		for (int i = 0; i < ChannelConstants.MAX_REACTIONS_PER_POST; i++) {
			existing.add(reaction(1, signer(i)));
		}
		assertEquals(Verdict.POST_FULL, ChannelReactionPolicy.admit(existing,
				posts(1, 2), 1, signer(9999)));
		assertEquals(Verdict.ADMIT, ChannelReactionPolicy.admit(existing,
				posts(1, 2), 2, signer(9999)));
	}

	@Test
	public void oneSignerCannotFillTheChannel() {
		List<ChannelReaction> existing = new ArrayList<>();
		int n = ChannelConstants.MAX_REACTIONS_PER_SIGNER_PER_CHANNEL;
		Set<Long> ps = new HashSet<>();
		for (long p = 1; p <= n + 1; p++) ps.add(p);
		for (long p = 1; p <= n; p++) existing.add(reaction(p, signer(1)));
		assertEquals(Verdict.SIGNER_FULL, ChannelReactionPolicy.admit(existing,
				ps, n + 1, signer(1)));
		assertEquals(Verdict.ADMIT, ChannelReactionPolicy.admit(existing,
				ps, n + 1, signer(2)));
	}

	@Test
	public void theChannelCeilingHoldsAcrossPostsAndSigners() {
		List<ChannelReaction> existing = new ArrayList<>();
		int total = ChannelConstants.MAX_REACTIONS_PER_CHANNEL;
		int perSigner = ChannelConstants.MAX_REACTIONS_PER_SIGNER_PER_CHANNEL;
		Set<Long> ps = new HashSet<>();
		int s = 0, p = 1;
		while (existing.size() < total) {
			ps.add((long) p);
			existing.add(reaction(p, signer(s)));
			s++;
			p++;
		}
		assertTrue(perSigner < total);
		ps.add((long) p);
		assertEquals(Verdict.CHANNEL_FULL, ChannelReactionPolicy.admit(existing,
				ps, p, signer(100000)));
	}

	@Test
	public void replacingOwnReactionIsAlwaysAdmitted() {
		List<ChannelReaction> existing = new ArrayList<>();
		for (int i = 0; i < ChannelConstants.MAX_REACTIONS_PER_POST; i++) {
			existing.add(reaction(1, signer(i)));
		}
		assertEquals(Verdict.ADMIT, ChannelReactionPolicy.admit(existing,
				posts(1), 1, signer(3)));
	}
}
