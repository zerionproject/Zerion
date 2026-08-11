package org.zerionproject.app.api.channel.event;

import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ChannelStateChangedEvent extends Event {

	public enum Kind {
		CREATED,
		JOINED,
		LEFT,
		MANIFEST_UPDATED,
		MIRROR_OPT_IN_TOGGLED,
		UNREAD_COUNT_CHANGED,
		APPLICANT_APPROVED,
		APPLICANT_DENIED
	}

	private final byte[] channelId;
	private final Kind kind;

	public ChannelStateChangedEvent(byte[] channelId, Kind kind) {
		this.channelId = channelId;
		this.kind = kind;
	}

	public byte[] getChannelId() {
		return channelId;
	}

	public Kind getKind() {
		return kind;
	}
}
