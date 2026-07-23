package org.zerionproject.app.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface OnionPublisher {

	OnionHandle publish(int localPort, @Nullable String privateKey)
			throws IOException;

	void unpublish(String onion) throws IOException;

	@NotNullByDefault
	final class OnionHandle {

		private final String onion;
		private final String privateKey;

		public OnionHandle(String onion, String privateKey) {
			this.onion = onion;
			this.privateKey = privateKey;
		}

		public String getOnion() {
			return onion;
		}

		public String getPrivateKey() {
			return privateKey;
		}
	}
}
