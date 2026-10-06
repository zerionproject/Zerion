package org.zerionproject.wire;

import java.util.function.BooleanSupplier;

public interface StreamCounterStore {

	long loadHighWater(int contactId, int direction);

	void storeHighWater(int contactId, int direction, long highWater);

	default boolean storeHighWaterIf(int contactId, int direction,
			long highWater, BooleanSupplier stillCurrent) {
		if (!stillCurrent.getAsBoolean()) return false;
		storeHighWater(contactId, direction, highWater);
		return true;
	}
}
