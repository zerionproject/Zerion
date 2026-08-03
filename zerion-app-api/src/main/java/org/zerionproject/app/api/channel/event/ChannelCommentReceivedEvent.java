package org.zerionproject.app.api.channel.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ChannelCommentReceivedEvent extends Event {

	private final byte[] channelId;
	private final long parentPostSeqNum;

	public ChannelCommentReceivedEvent(byte[] channelId,
			long parentPostSeqNum) {
		this.channelId = channelId;
		this.parentPostSeqNum = parentPostSeqNum;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public long getParentPostSeqNum() {
		return parentPostSeqNum;
	}
}
