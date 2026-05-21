package org.briarproject.briar.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

@NotNullByDefault
public interface ChannelTransport {

	ChannelServer bindServer(byte[] channelId,
			ChannelRequestHandler handler) throws IOException;

	byte[] requestFromOnion(String onion, byte[] requestBytes)
			throws IOException;

	@NotNullByDefault
	interface ChannelServer {

		String getOnionAddress();

		void close();
	}

	@NotNullByDefault
	interface ChannelRequestHandler {

		byte[] handle(byte[] requestBytes);
	}
}
