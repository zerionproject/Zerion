package org.briarproject.briar.api.messaging;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Factory for creating voice call signaling messages.
 * These messages are sent through a dedicated channel separate from chat messages.
 */
@NotNullByDefault
public interface VoiceSignalFactory {

	/**
	 * Creates a voice signal for call offer.
	 *
	 * @param groupId   The contact group ID
	 * @param timestamp Message timestamp
	 * @param callId    Unique call identifier
	 * @param sdpOffer  SDP offer payload
	 * @return The voice signal message
	 */
	VoiceSignal createCallOffer(GroupId groupId, long timestamp,
			String callId, String sdpOffer) throws FormatException;

	/**
	 * Creates a voice signal for call answer.
	 *
	 * @param groupId   The contact group ID
	 * @param timestamp Message timestamp
	 * @param callId    Unique call identifier
	 * @param sdpAnswer SDP answer payload
	 * @return The voice signal message
	 */
	VoiceSignal createCallAnswer(GroupId groupId, long timestamp,
			String callId, String sdpAnswer) throws FormatException;

	/**
	 * Creates a voice signal for call reject.
	 *
	 * @param groupId   The contact group ID
	 * @param timestamp Message timestamp
	 * @param callId    Unique call identifier
	 * @return The voice signal message
	 */
	VoiceSignal createCallReject(GroupId groupId, long timestamp,
			String callId) throws FormatException;

	/**
	 * Creates a voice signal for call end.
	 *
	 * @param groupId    The contact group ID
	 * @param timestamp  Message timestamp
	 * @param callId     Unique call identifier
	 * @param durationMs Call duration in milliseconds (null if call was not connected)
	 * @return The voice signal message
	 */
	VoiceSignal createCallEnd(GroupId groupId, long timestamp,
			String callId, @Nullable Long durationMs) throws FormatException;

	/**
	 * Creates a voice signal for ICE candidate.
	 *
	 * @param groupId      The contact group ID
	 * @param timestamp    Message timestamp
	 * @param callId       Unique call identifier
	 * @param iceCandidate ICE candidate payload (JSON)
	 * @return The voice signal message
	 */
	VoiceSignal createIceCandidate(GroupId groupId, long timestamp,
			String callId, String iceCandidate) throws FormatException;

	/**
	 * Creates a voice signal for call busy.
	 *
	 * @param groupId   The contact group ID
	 * @param timestamp Message timestamp
	 * @param callId    Unique call identifier
	 * @return The voice signal message
	 */
	VoiceSignal createCallBusy(GroupId groupId, long timestamp,
			String callId) throws FormatException;
}
