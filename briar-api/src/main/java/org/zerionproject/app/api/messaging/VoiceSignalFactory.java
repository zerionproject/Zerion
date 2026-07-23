package org.zerionproject.app.api.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface VoiceSignalFactory {

	VoiceSignal createCallOffer(GroupId groupId, long timestamp,
			String callId, String sdpOffer) throws FormatException;

	VoiceSignal createCallAnswer(GroupId groupId, long timestamp,
			String callId, String sdpAnswer) throws FormatException;

	VoiceSignal createCallReject(GroupId groupId, long timestamp,
			String callId) throws FormatException;

	VoiceSignal createCallReject(GroupId groupId, long timestamp,
			String callId, @Nullable String reason) throws FormatException;

	VoiceSignal createCallEnd(GroupId groupId, long timestamp,
			String callId, @Nullable Long durationMs) throws FormatException;

	VoiceSignal createIceCandidate(GroupId groupId, long timestamp,
			String callId, String iceCandidate) throws FormatException;

	VoiceSignal createCallBusy(GroupId groupId, long timestamp,
			String callId) throws FormatException;

	VoiceSignal createVideoOffer(GroupId groupId, long timestamp,
			String callId, String payload) throws FormatException;

	VoiceSignal createVideoAccept(GroupId groupId, long timestamp,
			String callId, String payload) throws FormatException;

	VoiceSignal createVideoReject(GroupId groupId, long timestamp,
			String callId) throws FormatException;

	VoiceSignal createVideoEnd(GroupId groupId, long timestamp,
			String callId) throws FormatException;
}
