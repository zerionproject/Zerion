package org.zerionproject.app.api.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class GroupTrPost {

	private final byte[] groupId;
	private final byte[] senderPubKey;
	private final String senderName;
	private final byte[] body;
	private final long timestamp;
	private final long epoch;
	private final boolean local;
	private final long autoDeleteTimerMs;

	public GroupTrPost(byte[] groupId, byte[] senderPubKey,
			String senderName, byte[] body, long timestamp, long epoch,
			boolean local) {
		this(groupId, senderPubKey, senderName, body, timestamp, epoch,
				local, 0L);
	}

	public GroupTrPost(byte[] groupId, byte[] senderPubKey,
			String senderName, byte[] body, long timestamp, long epoch,
			boolean local, long autoDeleteTimerMs) {
		this.groupId = groupId;
		this.senderPubKey = senderPubKey;
		this.senderName = senderName;
		this.body = body;
		this.timestamp = timestamp;
		this.epoch = epoch;
		this.local = local;
		this.autoDeleteTimerMs = autoDeleteTimerMs;
	}

	public long getAutoDeleteTimerMs() {
		return autoDeleteTimerMs;
	}

	public byte[] getGroupId() {
		return groupId;
	}

	public byte[] getSenderPubKey() {
		return senderPubKey;
	}

	public String getSenderName() {
		return senderName;
	}

	public byte[] getBody() {
		return body;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public long getEpoch() {
		return epoch;
	}

	public boolean isLocal() {
		return local;
	}

	public String getText() {
		return new String(body, java.nio.charset.StandardCharsets.UTF_8);
	}
}
