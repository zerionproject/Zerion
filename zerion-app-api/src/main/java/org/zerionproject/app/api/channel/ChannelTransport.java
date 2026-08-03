package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ChannelTransport {

	ChannelServer bindServer(byte[] channelId,
			@Nullable String onionPrivateKey,
			ChannelRequestHandler handler) throws IOException;

	byte[] requestFromOnion(String onion, byte[] requestBytes)
			throws IOException;

	boolean isReachable(String onion);

	@NotNullByDefault
	interface ChannelServer {

		String getOnionAddress();

		@Nullable
		String getOnionPrivateKey();

		void close();
	}

	@NotNullByDefault
	interface ChannelRequestHandler {

		byte[] handle(byte[] requestBytes);
	}
}
