package org.briarproject.briar.api.messaging;

/**
 * Types of voice call signaling messages.
 * These are sent through the dedicated VOICE_SIGNAL channel,
 * NOT as text messages in the conversation.
 */
public enum VoiceSignalType {

	/**
	 * Call offer - initiating a voice call.
	 * Contains SDP offer and call metadata.
	 */
	CALL_OFFER(0),

	/**
	 * Call answer - accepting an incoming call.
	 * Contains SDP answer.
	 */
	CALL_ANSWER(1),

	/**
	 * Call reject - declining an incoming call.
	 */
	CALL_REJECT(2),

	/**
	 * Call end - terminating an active or pending call.
	 * May contain call duration.
	 */
	CALL_END(3),

	/**
	 * ICE candidate - WebRTC connectivity candidate.
	 */
	ICE_CANDIDATE(4),

	/**
	 * Call busy - callee is already in another call.
	 */
	CALL_BUSY(5),

	/**
	 * Video offer - request to upgrade call with video.
	 */
	VIDEO_OFFER(6),

	/**
	 * Video accept - accept video upgrade request.
	 */
	VIDEO_ACCEPT(7),

	/**
	 * Video reject - reject video upgrade request.
	 */
	VIDEO_REJECT(8),

	/**
	 * Video end - stop video streaming (voice continues).
	 */
	VIDEO_END(9);

	private final int value;

	VoiceSignalType(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}

	public static VoiceSignalType fromValue(int value) {
		for (VoiceSignalType type : values()) {
			if (type.value == value) {
				return type;
			}
		}
		throw new IllegalArgumentException("Unknown VoiceSignalType: " + value);
	}
}
