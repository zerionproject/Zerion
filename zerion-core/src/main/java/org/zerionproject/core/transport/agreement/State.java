package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.FormatException;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
enum State {

	AWAIT_KEY(0),

	AWAIT_ACTIVATE(1),

	ACTIVATED(2);

	private final int value;

	State(int value) {
		this.value = value;
	}

	int getValue() {
		return value;
	}

	static State fromValue(int value) throws FormatException {
		for (State s : values()) if (s.value == value) return s;
		throw new FormatException();
	}
}
