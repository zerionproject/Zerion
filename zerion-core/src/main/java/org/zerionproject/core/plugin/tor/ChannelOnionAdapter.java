package org.zerionproject.core.plugin.tor;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.OnionTargetListener;

import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ChannelOnionAdapter {

	OnionTargetListener openOnionTarget() throws IOException;

	ChannelOnionHandle publishChannelOnion(String target,
			@Nullable String privateKey) throws IOException;

	void removeChannelOnion(String onion) throws IOException;

	@NotNullByDefault
	final class ChannelOnionHandle {

		private final String onion;
		private final String privateKey;

		public ChannelOnionHandle(String onion, String privateKey) {
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
