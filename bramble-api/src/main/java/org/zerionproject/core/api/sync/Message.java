package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.sync.SyncConstants.MESSAGE_HEADER_LENGTH;

@Immutable
@NotNullByDefault
public class Message {

	public static final int FORMAT_VERSION = 1;

	private final MessageId id;
	private final GroupId groupId;
	private final long timestamp;
	private final byte[] body;

	public Message(MessageId id, GroupId groupId, long timestamp, byte[] body) {
		if (body.length == 0) throw new IllegalArgumentException();
		if (body.length > MAX_MESSAGE_BODY_LENGTH)
			throw new IllegalArgumentException();
		this.id = id;
		this.groupId = groupId;
		this.timestamp = timestamp;
		this.body = body;
	}

	public MessageId getId() {
		return id;
	}

	public GroupId getGroupId() {
		return groupId;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public int getRawLength() {
		return MESSAGE_HEADER_LENGTH + body.length;
	}

	public byte[] getBody() {
		return body;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Message && id.equals(((Message) o).getId());
	}
}