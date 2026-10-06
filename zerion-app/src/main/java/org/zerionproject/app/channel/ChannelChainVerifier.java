package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

@NotNullByDefault
class ChannelChainVerifier {

	private final ChannelCodec codec;

	@Inject
	ChannelChainVerifier(ChannelCodec codec) {
		this.codec = codec;
	}

	enum Result {
		OK,
		SEQUENCE_BREAK,
		HASH_CHAIN_BROKEN,
		EMPTY
	}

	Result verifyOrdered(List<ChannelPost> ordered) {
		if (ordered.isEmpty()) return Result.EMPTY;
		long expected = ordered.get(0).getSeqNum();
		byte[] prev = ordered.get(0).getPrevHash();
		if (expected == 0L) {
			byte[] zero = new byte[ChannelConstants.PREV_HASH_BYTES];
			if (!Arrays.equals(prev, zero)) {
				return Result.HASH_CHAIN_BROKEN;
			}
		}
		for (int i = 0; i < ordered.size(); i++) {
			ChannelPost p = ordered.get(i);
			if (p.getSeqNum() != expected) return Result.SEQUENCE_BREAK;
			if (!Arrays.equals(p.getPrevHash(), prev)) {
				return Result.HASH_CHAIN_BROKEN;
			}
			prev = codec.canonicalHashOf(p);
			expected = p.getSeqNum() + 1L;
		}
		return Result.OK;
	}

	byte[] hashOf(ChannelPost p) {
		return codec.canonicalHashOf(p);
	}
}
