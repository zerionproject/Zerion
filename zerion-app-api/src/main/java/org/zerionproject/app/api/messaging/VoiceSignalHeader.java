package org.zerionproject.app.api.messaging;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class VoiceSignalHeader {

	private final MessageId id;
	private final GroupId groupId;
	private final long timestamp;
	private final boolean local;
	private final VoiceSignalType signalType;
	private final String callId;
	@Nullable
	private final String payload;
	@Nullable
	private final Long durationMs;

	public VoiceSignalHeader(MessageId id, GroupId groupId, long timestamp,
			boolean local, VoiceSignalType signalType, String callId,
			@Nullable String payload, @Nullable Long durationMs) {
		this.id = id;
		this.groupId = groupId;
		this.timestamp = timestamp;
		this.local = local;
		this.signalType = signalType;
		this.callId = callId;
		this.payload = payload;
		this.durationMs = durationMs;
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

	public boolean isLocal() {
		return local;
	}

	public VoiceSignalType getSignalType() {
		return signalType;
	}

	public String getCallId() {
		return callId;
	}

	@Nullable
	public String getPayload() {
		return payload;
	}

	@Nullable
	public Long getDurationMs() {
		return durationMs;
	}
}
