package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelTransport;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

final class FakeOnionNetwork implements ChannelTransport {

	private static final char[] BASE32 =
			"abcdefghijklmnopqrstuvwxyz234567".toCharArray();

	private final Random random;
	private final Map<String, ChannelRequestHandler> handlers =
			new ConcurrentHashMap<>();
	final Set<String> everPublished = ConcurrentHashMap.newKeySet();
	volatile boolean down;

	FakeOnionNetwork(Random random) {
		this.random = random;
	}

	Set<String> published() {
		return new HashSet<>(handlers.keySet());
	}

	void unbindAll() {
		handlers.clear();
	}

	@Override
	public ChannelServer bindServer(byte[] channelId,
			@Nullable String onionPrivateKey, ChannelRequestHandler handler)
			throws IOException {
		if (down) throw new IOException("tor is down");
		String key;
		if (onionPrivateKey == null) {
			StringBuilder sb = new StringBuilder("key-");
			for (int i = 0; i < 16; i++) sb.append(BASE32[random.nextInt(32)]);
			key = sb.toString();
		} else {
			key = onionPrivateKey;
		}
		String onion = onionOf(key);
		handlers.put(onion, handler);
		everPublished.add(onion);
		return new ChannelServer() {
			@Override
			public String getOnionAddress() {
				return onion;
			}

			@Override
			public String getOnionPrivateKey() {
				return key;
			}

			@Override
			public void close() {
				handlers.remove(onion);
			}
		};
	}

	private static String onionOf(String key) {
		Random r = new Random(key.hashCode() * 31L + key.length());
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 56; i++) sb.append(BASE32[r.nextInt(32)]);
		return sb.toString();
	}

	@Override
	public byte[] requestFromOnion(String onion, byte[] requestBytes)
			throws IOException {
		ChannelRequestHandler h = handlers.get(onion);
		if (h == null) throw new IOException("unreachable");
		return h.handle(requestBytes);
	}

	@Override
	public boolean isReachable(String onion) {
		return handlers.containsKey(onion);
	}
}
