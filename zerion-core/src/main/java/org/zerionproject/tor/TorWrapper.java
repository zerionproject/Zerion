package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.OnionTargets;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
public interface TorWrapper {

	void start() throws IOException, InterruptedException;

	void stop() throws IOException, InterruptedException;

	void setObserver(@Nullable Observer observer);

	TorState getTorState();

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isTorRunning();

	HiddenServiceProperties publishHiddenService(int localPort,
			int remotePort, @Nullable String privateKey) throws IOException;

	default HiddenServiceProperties publishHiddenService(String target,
			int remotePort, @Nullable String privateKey) throws IOException {
		int port = OnionTargets.loopbackPort(target);
		if (port <= 0) throw new IOException("Unsupported onion target");
		return publishHiddenService(port, remotePort, privateKey);
	}

	void removeHiddenService(String onion) throws IOException;

	void enableNetwork(boolean enable) throws IOException;

	void enableBridges(List<String> bridges) throws IOException;

	void disableBridges() throws IOException;

	void enableConnectionPadding(boolean enable) throws IOException;

	void enableIpv6(boolean ipv6Only) throws IOException;

	default void forgetHiddenServiceDescriptors() throws IOException {
	}

	File getLyrebirdExecutableFile();

	enum TorState {

		NOT_STARTED,

		STARTING,

		STARTED,

		CONNECTING,

		CONNECTED,

		DISABLED,

		STOPPING,

		STOPPED
	}

	interface Observer {

		void onState(TorState s);

		void onBootstrapPercentage(int percentage);

		void onHsDescriptorUpload(String onion);

		void onClockSkewDetected(long skewSeconds);
	}

	class HiddenServiceProperties {

		public final String onion, privKey;

		public HiddenServiceProperties(String onion, String privKey) {
			this.onion = onion;
			this.privKey = privKey;
		}
	}
}
