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

	/**
	 * Starts the Tor process, but does not yet connect to the Tor network.
	 * Call {@link #enableNetwork(boolean)} for this.
	 * <p>
	 * This method waits for the Tor process to start before returning. Methods
	 * that modify the wrapper's configuration
	 * ({@link #publishHiddenService(int, int, String)},
	 * {@link #removeHiddenService(String)}, {@link #enableNetwork(boolean)},
	 * {@link #enableBridges(List)}, {@link #enableConnectionPadding(boolean)},
	 * {@link #enableIpv6(boolean)}) should be called after this method returns.
	 * <p>
	 * Do not call this method concurrently with {@link #stop()}.
	 */
	void start() throws IOException, InterruptedException;

	/**
	 * Tell the Tor process to stop and waits for it to stop before returning.
	 * <p>
	 * The wrapper's configuration is reset, so if the wrapper is reused by
	 * calling {@link #start()} again then any configuration applied via
	 * {@link #enableNetwork(boolean)} etc must be applied again.
	 * <p>
	 * Do not call this method concurrently with {@link #start()}.
	 */
	void stop() throws IOException, InterruptedException;

	/**
	 * Sets an observer for observing the state of the wrapper, replacing any
	 * existing observer, or removes any existing observer if the argument is
	 * null.
	 */
	void setObserver(@Nullable Observer observer);

	/**
	 * Returns the current state of the wrapper.
	 */
	TorState getTorState();

	/**
	 * Returns true if the wrapper has been {@link #start() started} and not
	 * yet {@link #stop()} stopped.
	 */
	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	boolean isTorRunning();

	/**
	 * Publishes an ephemeral hidden service.
	 *
	 * @param localPort The local port on which the service is listening.
	 * @param remotePort The port number that clients of the service will see.
	 * @param privateKey The private key of the hidden service, in the form
	 * 		returned by a previous call to this method, or null if a new service
	 * 		should be created.
	 */
	HiddenServiceProperties publishHiddenService(int localPort,
			int remotePort, @Nullable String privateKey) throws IOException;

	/**
	 * Removes (unpublishes) an ephemeral hidden service that was created by
	 * calling {@link #publishHiddenService(int, int, String)}.
	 */
	void removeHiddenService(String onion) throws IOException;

	/**
	 * Enables or disables the Tor process's network connection. The network
	 * connection is disabled by default.
	 */
	void enableNetwork(boolean enable) throws IOException;

	/**
	 * Configures Tor to use the given list of bridges for connecting to the
	 * Tor network. Bridges are not used by default.
	 * <p>
	 * Each item in the list should be a bridge line in the same
	 * format that would be used in a torrc file (including the Bridge keyword).
	 */
	void enableBridges(List<String> bridges) throws IOException;

	/**
	 * Configures Tor not to use bridges for connecting to the Tor network.
	 * Bridges are not used by default.
	 */
	void disableBridges() throws IOException;

	/**
	 * Enables or disables connection padding. Padding is disabled by default.
	 */
	void enableConnectionPadding(boolean enable) throws IOException;

	/**
	 * Configures Tor to use IPv6 or IPv4 for connecting to the Tor network.
	 * IPv4 is used by default.
	 */
	void enableIpv6(boolean ipv6Only) throws IOException;

	/**
	 * Returns the Lyrebird executable as a File for use with Moat.
	 */
	File getLyrebirdExecutableFile();

	/**
	 * The state of the Tor wrapper.
	 */
	enum TorState {

		/**
		 * The wrapper has been created but the {@link #start()} method has not
		 * yet been called. This is the initial state.
		 */
		NOT_STARTED,

		/**
		 * The {@link #start()} method has been called and the Tor process is
		 * starting.
		 */
		STARTING,

		/**
		 * The {@link #start()} method has been called and the Tor process has
		 * started.
		 * <p>
		 * No connections to the Tor network will be made in this state. The
		 * wrapper remains in this state until {@link #enableNetwork(boolean)}
		 * is called.
		 */
		STARTED,

		/**
		 * The Tor process has started, its network connection is enabled, and
		 * it is connecting (or reconnecting) to the Tor network.
		 */
		CONNECTING,

		/**
		 * The Tor process has started, its network connection is enabled, and
		 * it has connected to the Tor network. In this state it should be
		 * possible to make connections via the SOCKS port.
		 */
		CONNECTED,

		/**
		 * The Tor process has started but its network connection is disabled.
		 */
		DISABLED,

		/**
		 * The {@link #stop()} method has been called and the Tor process is
		 * stopping.
		 */
		STOPPING,

		/**
		 * The {@link #stop()} method has been called and the Tor process has
		 * stopped.
		 * <p>
		 * A new Tor process can be started by calling the {@link #start()}
		 * method again.
		 */
		STOPPED
	}

	/**
	 * An interface for observing changes to the {@link TorState state} of the
	 * Tor process. All calls happen on the event executor supplied to the
	 * wrapper's constructor.
	 */
	interface Observer {

		/**
		 * Called whenever the state of the Tor process changes.
		 */
		void onState(TorState s);

		/**
		 * Called whenever the bootstrap percentage changes.
		 */
		void onBootstrapPercentage(int percentage);

		/**
		 * Called whenever a hidden service descriptor is uploaded.
		 */
		void onHsDescriptorUpload(String onion);

		/**
		 * Called whenever Tor detects that the system clock is skewed.
		 */
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
