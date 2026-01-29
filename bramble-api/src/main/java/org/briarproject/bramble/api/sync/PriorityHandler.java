package org.briarproject.bramble.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;


@NotNullByDefault
public interface PriorityHandler {

	void handle(Priority p);
}
