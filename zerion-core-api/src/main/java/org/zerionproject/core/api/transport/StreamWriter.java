package org.zerionproject.core.api.transport;

import java.io.IOException;
import java.io.OutputStream;

public interface StreamWriter {

	OutputStream getOutputStream();

	void sendEndOfStream() throws IOException;
}
