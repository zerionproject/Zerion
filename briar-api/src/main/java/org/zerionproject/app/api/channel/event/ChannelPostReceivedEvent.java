package org.zerionproject.app.api.channel.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ChannelPostReceivedEvent extends Event {

	private final byte[] channelId;
	private final long seqNum;
	private final boolean isLocal;

	public ChannelPostReceivedEvent(byte[] channelId, long seqNum,
			boolean isLocal) {
		this.channelId = channelId;
		this.seqNum = seqNum;
		this.isLocal = isLocal;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public long getSeqNum() {
		return seqNum;
	}

	public boolean isLocal() {
		return isLocal;
	}
}
