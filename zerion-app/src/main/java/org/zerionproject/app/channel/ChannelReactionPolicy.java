package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a reaction request may change the publisher's stored
 * state. A valid signature and an unbanned signer are necessary but not
 * sufficient: the post must exist, and the per-post, per-signer and
 * channel-wide ceilings bound the state one channel can accumulate. A
 * signer replacing its own reaction on a post adds nothing and is admitted
 * whatever the ceilings say.
 */
@NotNullByDefault
final class ChannelReactionPolicy {

	enum Verdict {
		ADMIT, NO_SUCH_POST, POST_FULL, SIGNER_FULL, CHANNEL_FULL
	}

	private ChannelReactionPolicy() {
	}

	static Verdict admit(List<ChannelReaction> existing,
			Set<Long> postSeqNums, long postSeqNum, byte[] signer) {
		if (!postSeqNums.contains(postSeqNum)) return Verdict.NO_SUCH_POST;
		int forPost = 0, forSigner = 0;
		for (ChannelReaction r : existing) {
			boolean samePost = r.getPostSeqNum() == postSeqNum;
			boolean sameSigner =
					Arrays.equals(r.getSignerEd25519PubKey(), signer);
			if (samePost && sameSigner) return Verdict.ADMIT;
			if (samePost) forPost++;
			if (sameSigner) forSigner++;
		}
		if (forPost >= ChannelConstants.MAX_REACTIONS_PER_POST) {
			return Verdict.POST_FULL;
		}
		if (forSigner
				>= ChannelConstants.MAX_REACTIONS_PER_SIGNER_PER_CHANNEL) {
			return Verdict.SIGNER_FULL;
		}
		if (existing.size() >= ChannelConstants.MAX_REACTIONS_PER_CHANNEL) {
			return Verdict.CHANNEL_FULL;
		}
		return Verdict.ADMIT;
	}
}
