package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.app.api.messaging.VoiceSignal;
import org.zerionproject.app.api.messaging.VoiceSignalFactory;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.app.messaging.MessageTypes.VOICE_SIGNAL;

@Immutable
@NotNullByDefault
class VoiceSignalFactoryImpl implements VoiceSignalFactory {

	private static final int MAX_CALL_ID_LENGTH = 64;
	private static final int MAX_PAYLOAD_LENGTH = 16384;

	private final ClientHelper clientHelper;

	@Inject
	VoiceSignalFactoryImpl(ClientHelper clientHelper) {
		this.clientHelper = clientHelper;
	}

	@Override
	public VoiceSignal createCallOffer(GroupId groupId, long timestamp,
			String callId, String sdpOffer) throws FormatException {
		validateCallId(callId);
		validatePayload(sdpOffer);
		return createSignal(groupId, timestamp, VoiceSignalType.CALL_OFFER,
				callId, sdpOffer, null);
	}

	@Override
	public VoiceSignal createCallAnswer(GroupId groupId, long timestamp,
			String callId, String sdpAnswer) throws FormatException {
		validateCallId(callId);
		validatePayload(sdpAnswer);
		return createSignal(groupId, timestamp, VoiceSignalType.CALL_ANSWER,
				callId, sdpAnswer, null);
	}

	@Override
	public VoiceSignal createCallReject(GroupId groupId, long timestamp,
			String callId) throws FormatException {
		return createCallReject(groupId, timestamp, callId, null);
	}

	@Override
	public VoiceSignal createCallReject(GroupId groupId, long timestamp,
			String callId, @Nullable String reason) throws FormatException {
		validateCallId(callId);
		if (reason != null) validatePayload(reason);
		return createSignal(groupId, timestamp, VoiceSignalType.CALL_REJECT,
				callId, reason, null);
	}

	@Override
	public VoiceSignal createCallEnd(GroupId groupId, long timestamp,
			String callId, @Nullable Long durationMs) throws FormatException {
		validateCallId(callId);
		if (durationMs != null && durationMs < 0) {
			throw new IllegalArgumentException("Duration cannot be negative");
		}
		return createSignal(groupId, timestamp, VoiceSignalType.CALL_END,
				callId, null, durationMs);
	}

	@Override
	public VoiceSignal createIceCandidate(GroupId groupId, long timestamp,
			String callId, String iceCandidate) throws FormatException {
		validateCallId(callId);
		validatePayload(iceCandidate);
		return createSignal(groupId, timestamp, VoiceSignalType.ICE_CANDIDATE,
				callId, iceCandidate, null);
	}

	@Override
	public VoiceSignal createCallBusy(GroupId groupId, long timestamp,
			String callId) throws FormatException {
		validateCallId(callId);
		return createSignal(groupId, timestamp, VoiceSignalType.CALL_BUSY,
				callId, null, null);
	}

	@Override
	public VoiceSignal createVideoOffer(GroupId groupId, long timestamp,
			String callId, String payload) throws FormatException {
		validateCallId(callId);
		validatePayload(payload);
		return createSignal(groupId, timestamp, VoiceSignalType.VIDEO_OFFER,
				callId, payload, null);
	}

	@Override
	public VoiceSignal createVideoAccept(GroupId groupId, long timestamp,
			String callId, String payload) throws FormatException {
		validateCallId(callId);
		validatePayload(payload);
		return createSignal(groupId, timestamp, VoiceSignalType.VIDEO_ACCEPT,
				callId, payload, null);
	}

	@Override
	public VoiceSignal createVideoReject(GroupId groupId, long timestamp,
			String callId) throws FormatException {
		validateCallId(callId);
		return createSignal(groupId, timestamp, VoiceSignalType.VIDEO_REJECT,
				callId, null, null);
	}

	@Override
	public VoiceSignal createVideoEnd(GroupId groupId, long timestamp,
			String callId) throws FormatException {
		validateCallId(callId);
		return createSignal(groupId, timestamp, VoiceSignalType.VIDEO_END,
				callId, null, null);
	}

	private VoiceSignal createSignal(GroupId groupId, long timestamp,
			VoiceSignalType signalType, String callId,
			@Nullable String payload, @Nullable Long durationMs)
			throws FormatException {
		BdfList body = BdfList.of(
				VOICE_SIGNAL,
				signalType.getValue(),
				callId,
				payload,
				durationMs
		);
		Message m = clientHelper.createMessage(groupId, timestamp, body);
		return new VoiceSignal(m, signalType, callId, payload, durationMs, null);
	}

	private void validateCallId(String callId) {
		if (callId == null || callId.isEmpty()) {
			throw new IllegalArgumentException("Call ID cannot be empty");
		}
		if (callId.length() > MAX_CALL_ID_LENGTH) {
			throw new IllegalArgumentException("Call ID too long");
		}
	}

	private void validatePayload(@Nullable String payload) {
		if (payload != null && payload.length() > MAX_PAYLOAD_LENGTH) {
			throw new IllegalArgumentException("Payload too long");
		}
	}
}
