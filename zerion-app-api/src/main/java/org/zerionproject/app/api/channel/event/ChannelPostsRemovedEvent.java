package org.zerionproject.app.api.channel.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ChannelPostsRemovedEvent extends Event {

	private final byte[] channelId;
	private final List<Long> seqNums;

	public ChannelPostsRemovedEvent(byte[] channelId, List<Long> seqNums) {
		this.channelId = channelId;
		this.seqNums = seqNums;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public List<Long> getSeqNums() {
		return seqNums;
	}
}
