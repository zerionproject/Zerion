package org.zerionproject.app.api.messaging;

import org.zerionproject.core.api.sync.Message;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class VoiceSignal {

	private final Message message;
	private final VoiceSignalType signalType;
	private final String callId;
	@Nullable
	private final String payload;
	@Nullable
	private final Long durationMs;
	@Nullable
	private final byte[] hmac;

	public VoiceSignal(Message message, VoiceSignalType signalType, String callId) {
		this(message, signalType, callId, null, null, null);
	}

	public VoiceSignal(Message message, VoiceSignalType signalType, String callId,
			@Nullable String payload) {
		this(message, signalType, callId, payload, null, null);
	}

	public VoiceSignal(Message message, VoiceSignalType signalType, String callId,
			@Nullable Long durationMs) {
		this(message, signalType, callId, null, durationMs, null);
	}

	public VoiceSignal(Message message, VoiceSignalType signalType, String callId,
			@Nullable String payload, @Nullable Long durationMs, @Nullable byte[] hmac) {
		this.message = message;
		this.signalType = signalType;
		this.callId = callId;
		this.payload = payload;
		this.durationMs = durationMs;
		this.hmac = hmac;
	}

	public Message getMessage() {
		return message;
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

	@Nullable
	public byte[] getHmac() {
		return hmac;
	}

	public boolean requiresPayload() {
		return signalType == VoiceSignalType.CALL_OFFER ||
				signalType == VoiceSignalType.CALL_ANSWER ||
				signalType == VoiceSignalType.ICE_CANDIDATE;
	}
}
