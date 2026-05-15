package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import javax.annotation.Nullable;

import static java.util.logging.Logger.getLogger;

@NotNullByDefault
public interface TorWrapper {

	Logger LOG = getLogger(TorWrapper.class.getName());

	void start() throws IOException, InterruptedException;

	void stop() throws IOException, InterruptedException;

	void setObserver(@Nullable Observer observer);

	TorState getTorState();

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isTorRunning();

	HiddenServiceProperties publishHiddenService(int localPort,
			int remotePort, @Nullable String privateKey) throws IOException;

	void removeHiddenService(String onion) throws IOException;

	void enableNetwork(boolean enable) throws IOException;

	void enableBridges(List<String> bridges) throws IOException;

	void disableBridges() throws IOException;

	void enableConnectionPadding(boolean enable) throws IOException;

	void enableIpv6(boolean ipv6Only) throws IOException;

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

		HiddenServiceProperties(String onion, String privKey) {
			this.onion = onion;
			this.privKey = privKey;
		}
	}
}
