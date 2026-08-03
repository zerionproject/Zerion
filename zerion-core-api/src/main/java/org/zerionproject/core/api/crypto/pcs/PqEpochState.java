package org.zerionproject.core.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public enum PqEpochState {

	PQ_INACTIVE(0),
	PQ_READY(1),
	PQ_SENDING_EK_SEED(2),
	PQ_SENDING_EK_VEC(3),
	PQ_RECEIVING_EK_SEED(4),
	PQ_RECEIVING_EK_VEC(5),
	PQ_AWAITING_CT(6),
	PQ_SENDING_CT(7),
	PQ_COMPLETE(8);

	private final int value;

	PqEpochState(int value) {
		this.value = value;
	}

	public int getValue() {
		return value;
	}

	public static PqEpochState fromValue(int value) {
		for (PqEpochState state : values()) {
			if (state.value == value) return state;
		}
		return PQ_INACTIVE;
	}

	public boolean isActive() {
		return this != PQ_INACTIVE;
	}

	public boolean isInitiatorState() {
		return this == PQ_SENDING_EK_SEED ||
				this == PQ_SENDING_EK_VEC ||
				this == PQ_AWAITING_CT;
	}

	public boolean isResponderState() {
		return this == PQ_RECEIVING_EK_SEED ||
				this == PQ_RECEIVING_EK_VEC ||
				this == PQ_SENDING_CT;
	}

	public boolean isTransmitting() {
		return this == PQ_SENDING_EK_SEED ||
				this == PQ_SENDING_EK_VEC ||
				this == PQ_SENDING_CT;
	}

	public boolean isReceiving() {
		return this == PQ_RECEIVING_EK_SEED ||
				this == PQ_RECEIVING_EK_VEC ||
				this == PQ_AWAITING_CT;
	}
}
