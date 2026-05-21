package org.briarproject.briar.api.channel.event;

import org.briarproject.bramble.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ChannelPostReceivedEvent extends Event {

	private final byte[] channelId;
	private final long seqNum;

	public ChannelPostReceivedEvent(byte[] channelId, long seqNum) {
		this.channelId = channelId;
		this.seqNum = seqNum;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public long getSeqNum() {
		return seqNum;
	}
}
