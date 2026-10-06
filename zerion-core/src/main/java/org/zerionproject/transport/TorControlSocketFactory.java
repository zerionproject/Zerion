package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.Socket;

@NotNullByDefault
public interface TorControlSocketFactory {

	Socket open(int readTimeoutMs) throws IOException;
}
