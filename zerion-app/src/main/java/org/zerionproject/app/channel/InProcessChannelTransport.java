package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelTransport;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class InProcessChannelTransport implements ChannelTransport {

	private static final AtomicLong NEXT_ONION_SEQ = new AtomicLong(0);
	private final ConcurrentHashMap<String, ChannelRequestHandler>
			onionToHandler = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> onionToPrivKey =
			new ConcurrentHashMap<>();

	@Inject
	InProcessChannelTransport() {
	}

	@Override
	public ChannelServer bindServer(byte[] channelId,
			@Nullable String onionPrivateKey,
			ChannelRequestHandler handler) throws IOException {
		String onion;
		String privKey;
		if (onionPrivateKey != null
				&& onionToHandler.containsKey(privKeyToOnion(
						onionPrivateKey))) {
			onion = privKeyToOnion(onionPrivateKey);
			privKey = onionPrivateKey;
		} else {
			onion = synthesiseOnion(channelId);
			privKey = onionPrivateKey != null
					? onionPrivateKey : "inproc-key-" + onion;
		}
		onionToHandler.put(onion, handler);
		onionToPrivKey.put(onion, privKey);
		String finalOnion = onion;
		String finalPriv = privKey;
		return new ChannelServer() {
			@Override
			public String getOnionAddress() {
				return finalOnion;
			}

			@Nullable
			@Override
			public String getOnionPrivateKey() {
				return finalPriv;
			}

			@Override
			public void close() {
				onionToHandler.remove(finalOnion);
				onionToPrivKey.remove(finalOnion);
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

	@Override
	public boolean isReachable(String onion) {
		return onionToHandler.containsKey(onion);
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

	private String privKeyToOnion(String privKey) {
		if (privKey.startsWith("inproc-key-")) {
			return privKey.substring("inproc-key-".length());
		}
		return "inproc-" + privKey.hashCode() + ".onion";
	}
}
