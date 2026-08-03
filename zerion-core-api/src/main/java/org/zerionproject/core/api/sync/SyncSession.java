package org.zerionproject.core.api.sync;

import java.io.IOException;

public interface SyncSession {

	void run() throws IOException;

	void interrupt();
}
