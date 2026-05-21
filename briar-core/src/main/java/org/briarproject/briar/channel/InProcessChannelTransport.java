package org.briarproject.briar.channel;

import org.briarproject.briar.api.channel.ChannelTransport;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class InProcessChannelTransport implements ChannelTransport {

	private static final AtomicLong NEXT_ONION_SEQ = new AtomicLong(0);
	private final ConcurrentHashMap<String, ChannelRequestHandler>
			onionToHandler = new ConcurrentHashMap<>();

	@Inject
	InProcessChannelTransport() {
	}

	@Override
	public ChannelServer bindServer(byte[] channelId,
			ChannelRequestHandler handler) throws IOException {
		String onion = synthesiseOnion(channelId);
		onionToHandler.put(onion, handler);
		return new ChannelServer() {
			@Override
			public String getOnionAddress() {
				return onion;
			}

			@Override
			public void close() {
				onionToHandler.remove(onion);
			}
		};
	}

	@Override
	public byte[] requestFromOnion(String onion, byte[] requestBytes)
			throws IOException {
		ChannelRequestHandler handler = onionToHandler.get(onion);
		if (handler == null) {
			throw new IOException("No in-process server bound for onion "
					+ onion);
		}
		return handler.handle(requestBytes);
	}

	private String synthesiseOnion(byte[] channelId) {
		long seq = NEXT_ONION_SEQ.incrementAndGet();
		StringBuilder sb = new StringBuilder("inproc-");
		for (int i = 0; i < Math.min(8, channelId.length); i++) {
			sb.append(String.format(Locale.US, "%02x", channelId[i]));
		}
		sb.append('-').append(seq).append(".onion");
		return sb.toString();
	}
}
