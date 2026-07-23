package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.FormatException;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
enum MessageType {

	KEY(0),
	ACTIVATE(1);

	private final int value;

	MessageType(int value) {
		this.value = value;
	}

	int getValue() {
		return value;
	}

	static MessageType fromValue(int value) throws FormatException {
		for (MessageType t : values()) if (t.value == value) return t;
		throw new FormatException();
	}
}
