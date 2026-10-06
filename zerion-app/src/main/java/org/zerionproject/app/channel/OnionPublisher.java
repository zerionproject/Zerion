package org.zerionproject.app.channel;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.zerionproject.core.api.plugin.OnionTargets;
import org.zerionproject.transport.LoopbackOnionTargetFactory;

import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface OnionPublisher {

	OnionHandle publish(int localPort, @Nullable String privateKey)
			throws IOException;

	default OnionTargetListener openTarget() throws IOException {
		return new LoopbackOnionTargetFactory().open();
	}

	default OnionHandle publish(String target, @Nullable String privateKey)
			throws IOException {
		int port = OnionTargets.loopbackPort(target);
		if (port <= 0) throw new IOException("Unsupported onion target");
		return publish(port, privateKey);
	}

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
