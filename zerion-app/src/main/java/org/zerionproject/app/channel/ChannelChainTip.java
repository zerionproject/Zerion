package org.zerionproject.app.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
final class ChannelChainTip {

	final long seqNum;
	@Nullable
	final byte[] hash;

	ChannelChainTip(long seqNum, @Nullable byte[] hash) {
		this.seqNum = seqNum;
		this.hash = hash;
	}
}
