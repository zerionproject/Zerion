package org.zerionproject.app.channel;

import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.plugin.tor.ChannelOnionAdapter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class TorPluginOnionPublisher implements OnionPublisher {

	private final PluginManager pluginManager;

	@Inject
	TorPluginOnionPublisher(PluginManager pluginManager) {
		this.pluginManager = pluginManager;
	}

	@Override
	public OnionHandle publish(int localPort,
			@Nullable String privateKey) throws IOException {
		ChannelOnionAdapter.ChannelOnionHandle h =
				adapter().publishChannelOnion(localPort, privateKey);
		return new OnionHandle(h.getOnion(), h.getPrivateKey());
	}

	@Override
	public void unpublish(String onion) throws IOException {
		adapter().removeChannelOnion(onion);
	}

	private ChannelOnionAdapter adapter() throws IOException {
		Plugin p = pluginManager.getPlugin(TorConstants.ID);
		if (p == null) {
			throw new IOException("Tor plugin not yet started");
		}
		if (!(p instanceof ChannelOnionAdapter)) {
			throw new IOException(
					"Tor plugin does not expose ChannelOnionAdapter");
		}
		return (ChannelOnionAdapter) p;
	}
}
