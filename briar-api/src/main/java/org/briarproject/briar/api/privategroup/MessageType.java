package org.briarproject.briar.api.privategroup;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public enum MessageType {

	JOIN(0),
	POST(1),
	ENCRYPTED_POST(2),
	SENDER_KEYS_POST(3),
	// Group-membership v2 wire records (iOS commits d529ec2 / d7c1e27 / e17dd0a).
	// All five ride the existing private-group BSP channel; "authorId" inside
	// these payloads is the raw 32-byte Ed25519 signing public key (iOS
	// convention), not the SHA-256-derived Briar authorId.
	MEMBER_ADDED(10),
	MEMBER_REMOVED(11),
	MEMBER_LEFT(12),
	GROUP_DISSOLVED(13),
	SENDER_KEY_BROADCAST(14);

	private final int value;

	MessageType(int value) {
		this.value = value;
	}

	public static MessageType valueOf(int value) {
		for (MessageType m : values()) if (m.value == value) return m;
		throw new IllegalArgumentException();
	}

	public int getInt() {
		return value;
	}
}
