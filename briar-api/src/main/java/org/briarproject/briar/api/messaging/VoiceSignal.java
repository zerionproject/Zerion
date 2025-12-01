package org.briarproject.briar.api.messaging;

import org.briarproject.bramble.api.sync.Message;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * A voice call signaling message.
 * This is a dedicated message type separate from chat messages,
 * ensuring voice call signals never appear in or interfere with
 * the conversation UI.
 */
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

	/**
	 * Constructor for voice signals without payload (e.g., CALL_REJECT, CALL_BUSY).
	 */
	public VoiceSignal(Message message, VoiceSignalType signalType, String callId) {
		this(message, signalType, callId, null, null, null);
	}

	/**
	 * Constructor for voice signals with payload (e.g., CALL_OFFER, CALL_ANSWER, ICE_CANDIDATE).
	 */
	public VoiceSignal(Message message, VoiceSignalType signalType, String callId,
			@Nullable String payload) {
		this(message, signalType, callId, payload, null, null);
	}

	/**
	 * Constructor for CALL_END signals with duration.
	 */
	public VoiceSignal(Message message, VoiceSignalType signalType, String callId,
			@Nullable Long durationMs) {
		this(message, signalType, callId, null, durationMs, null);
	}

	/**
	 * Full constructor with all fields including HMAC for authentication.
	 */
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

	/**
	 * Check if this signal requires a payload (SDP/ICE data).
	 */
	public boolean requiresPayload() {
		return signalType == VoiceSignalType.CALL_OFFER ||
				signalType == VoiceSignalType.CALL_ANSWER ||
				signalType == VoiceSignalType.ICE_CANDIDATE;
	}
}
