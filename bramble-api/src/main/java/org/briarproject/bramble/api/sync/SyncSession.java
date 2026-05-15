package org.briarproject.bramble.api.sync;

import java.io.IOException;

public interface SyncSession {

	void run() throws IOException;

	void interrupt();
}
