package org.zerionproject.app.api.messaging;

public enum VoiceSignalType {

	CALL_OFFER(0),

	CALL_ANSWER(1),

	CALL_REJECT(2),

	CALL_END(3),

	ICE_CANDIDATE(4),

	CALL_BUSY(5),

	VIDEO_OFFER(6),

	VIDEO_ACCEPT(7),

	VIDEO_REJECT(8),

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
