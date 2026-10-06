package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface ZppConnectionRunner {

	void run(int contactId, ZwfDuplexConnection connection) throws IOException;
}
