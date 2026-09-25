package org.zerionproject.tor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

/**
 * The lifecycle of the embedded Tor process and the few control-port
 * operations the transport needs from it. The transport speaks the rest of
 * the control protocol itself over its own cookie-authenticated connection.
 */
@NotNullByDefault
public interface TorWrapper {

	/**
	 * Starts the Tor process without connecting it to the Tor network; call
	 * {@link #enableNetwork(boolean)} for that. Returns once the process has
	 * opened its control listener and the control connection is
	 * authenticated. Methods that change the configuration must be called
	 * after this method returns. Not to be called concurrently with
	 * {@link #stop()}.
	 */
	void start() throws IOException, InterruptedException;

	/**
	 * Tells the Tor process to stop and waits, for a bounded time, for it to
	 * exit; a process that does not exit in time is killed. The wrapper's
	 * configuration is reset, so a wrapper that is started again must be
	 * configured again. Not to be called concurrently with {@link #start()}.
	 */
	void stop() throws IOException, InterruptedException;

	/**
	 * Sets the observer, replacing any existing one, or removes it if the
	 * argument is null.
	 */
	void setObserver(@Nullable Observer observer);

	TorState getTorState();

	/**
	 * True if the wrapper has been started and not yet stopped.
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isTorRunning();

	/**
	 * Publishes an ephemeral onion service.
	 *
	 * @param localPort the local port the service listens on
	 * @param remotePort the port clients of the service see
	 * @param privateKey the service's private key as returned by an earlier
	 * call, or null to create a new service
	 */
	HiddenServiceProperties publishHiddenService(int localPort,
			int remotePort, @Nullable String privateKey) throws IOException;

	/**
	 * Removes an ephemeral onion service created by
	 * {@link #publishHiddenService(int, int, String)}.
	 */
	void removeHiddenService(String onion) throws IOException;

	/**
	 * Enables or disables the Tor process's network connection, which is
	 * disabled when the process starts.
	 */
	void enableNetwork(boolean enable) throws IOException;

	/**
	 * Configures Tor to reach the network through the given bridges. Each
	 * item is a torrc bridge line including the Bridge keyword.
	 */
	void enableBridges(List<String> bridges) throws IOException;

	void disableBridges() throws IOException;

	/**
	 * Enables or disables connection padding. The process starts with
	 * padding enabled; this only changes it afterwards.
	 */
	void enableConnectionPadding(boolean enable) throws IOException;

	/**
	 * Configures Tor to reach the network over IPv6 only, or over IPv4.
	 */
	void enableIpv6(boolean ipv6Only) throws IOException;

	/**
	 * The lyrebird executable, for pluggable transports.
	 */
	File getLyrebirdExecutableFile();

	enum TorState {

		/** Created, {@link #start()} not yet called. */
		NOT_STARTED,

		/** {@link #start()} called, process starting. */
		STARTING,

		/**
		 * Process started, network not yet enabled. No connection to the Tor
		 * network is made in this state.
		 */
		STARTED,

		/** Network enabled, connecting or reconnecting to the Tor network. */
		CONNECTING,

		/**
		 * Connected to the Tor network; connections through the SOCKS
		 * listener are possible.
		 */
		CONNECTED,

		/** Process started, network disabled. */
		DISABLED,

		/** {@link #stop()} called, process stopping. */
		STOPPING,

		/** Process stopped; {@link #start()} may be called again. */
		STOPPED
	}

	/**
	 * Observes the wrapper. Every call happens on the event executor the
	 * wrapper was given.
	 */
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
