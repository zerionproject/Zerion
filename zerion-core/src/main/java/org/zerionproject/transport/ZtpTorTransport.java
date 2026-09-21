package org.zerionproject.transport;

import org.briarproject.onionwrapper.TorWrapper;
import org.briarproject.onionwrapper.TorWrapper.HiddenServiceProperties;
import org.briarproject.onionwrapper.TorWrapper.TorState;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

@NotNullByDefault
public class ZtpTorTransport implements OverlayTransport {

	private static final int REMOTE_ONION_PORT = 80;
	private static final int SOCKET_TIMEOUT_MS = 30_000;
	private static final long ACCEPT_RETRY_DELAY_MS = 500;
	private static final int MAX_INBOUND_CONNECTIONS = 64;
	/** How long Tor may sit without a working connection, after having had
	 * one, before the transport counts as degraded. */
	static final long DEGRADED_GRACE_MS = 45_000;
	/** Two network restarts are never closer together than this. */
	static final long MIN_RESTART_INTERVAL_MS = 60_000;

	private final TorWrapper tor;
	private final SocketFactory socketFactory;
	private final SocketFactory fastSocketFactory;
	private final Executor ioExecutor;
	private final ZtpConnectionHandler handler;
	private final TorBridgeConfigurator bridgeConfigurator;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final Semaphore inboundLimiter =
			new Semaphore(MAX_INBOUND_CONNECTIONS);
	private final AtomicBoolean everConnected = new AtomicBoolean(false);
	private final Object restartLock = new Object();
	volatile LongSupplier clock = System::currentTimeMillis;
	private volatile long degradedSinceMs = 0;
	private long lastRestartMs = 0;

	@Nullable
	private volatile ServerSocket serverSocket;
	private volatile int localPort;

	public ZtpTorTransport(TorWrapper tor, SocketFactory socketFactory,
			SocketFactory fastSocketFactory, Executor ioExecutor,
			ZtpConnectionHandler handler,
			TorBridgeConfigurator bridgeConfigurator) {
		this.tor = tor;
		this.socketFactory = socketFactory;
		this.fastSocketFactory = fastSocketFactory;
		this.ioExecutor = ioExecutor;
		this.handler = handler;
		this.bridgeConfigurator = bridgeConfigurator;
	}

	@Override
	public TransportId getTransportId() {
		return TorConstants.ID;
	}

	@Override
	public String getAddressPropertyKey() {
		return TorConstants.PROP_ONION_V3;
	}

	public HiddenServiceProperties start(@Nullable String privateKey)
			throws IOException, InterruptedException {
		if (!running.compareAndSet(false, true)) {
			throw new IllegalStateException("already started");
		}
		tor.start();
		if (!bridgeConfigurator.apply()) {
			running.set(false);
			try {
				tor.stop();
			} catch (IOException e) {
			}
			throw new IOException("bridge configuration failed");
		}
		tor.enableNetwork(true);
		startAccepting(0);
		HiddenServiceProperties hs = tor.publishHiddenService(localPort,
				REMOTE_ONION_PORT, privateKey);
		return hs;
	}

	void startAccepting(int port) throws IOException {
		ServerSocket ss = new ServerSocket();
		ss.bind(new InetSocketAddress("127.0.0.1", port));
		this.serverSocket = ss;
		this.localPort = ss.getLocalPort();
		ioExecutor.execute(() -> acceptLoop(ss));
	}

	private void acceptLoop(ServerSocket ss) {
		while (!ss.isClosed()) {
			Socket socket;
			try {
				socket = ss.accept();
			} catch (IOException e) {
				if (ss.isClosed()) return;
				try {
					Thread.sleep(ACCEPT_RETRY_DELAY_MS);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return;
				}
				continue;
			}
			if (inboundLimiter.availablePermits() <= 0) {
				closeQuietly(socket);
				continue;
			}
			ioExecutor.execute(() -> handleAccepted(socket));
		}
	}

	private void handleAccepted(Socket socket) {
		if (!inboundLimiter.tryAcquire()) {
			closeQuietly(socket);
			return;
		}
		try {
			configureSocket(socket);
			handler.handleIncoming(TorConstants.ID, socket.getInputStream(),
					socket.getOutputStream());
		} catch (IOException e) {
		} finally {
			closeQuietly(socket);
			inboundLimiter.release();
		}
	}

	@Override
	public long dial(int contactId, String peerOnion, boolean fast) {
		SocketFactory factory = fast ? fastSocketFactory : socketFactory;
		Socket socket;
		try {
			socket = factory.createSocket(peerOnion + ".onion",
					REMOTE_ONION_PORT);
		} catch (IOException e) {
			return DIAL_NOT_CONNECTED;
		}
		long connectedAt = System.currentTimeMillis();
		try {
			configureSocket(socket);
			handler.handleOutgoing(TorConstants.ID, contactId,
					socket.getInputStream(), socket.getOutputStream());
		} catch (IOException e) {
		} finally {
			closeQuietly(socket);
		}
		return System.currentTimeMillis() - connectedAt;
	}

	private static void configureSocket(Socket socket) throws IOException {
		socket.setSoTimeout(SOCKET_TIMEOUT_MS);
		try {
			socket.setTcpNoDelay(true);
		} catch (java.net.SocketException ignored) {
		}
	}

	public int getLocalPort() {
		return localPort;
	}

	/**
	 * Tor's state as reported by the wrapper. Once Tor has been connected in
	 * this run, a fall back to connecting marks the moment the network
	 * degraded; a connected report or a deliberate disable clears it.
	 */
	void onTorState(TorState state) {
		if (state == TorState.CONNECTED) {
			everConnected.set(true);
			degradedSinceMs = 0;
		} else if (state == TorState.CONNECTING) {
			if (everConnected.get() && degradedSinceMs == 0) {
				degradedSinceMs = clock.getAsLong();
			}
		} else {
			degradedSinceMs = 0;
		}
	}

	@Override
	public boolean isNetworkDegraded() {
		long since = degradedSinceMs;
		return running.get() && since != 0
				&& clock.getAsLong() - since >= DEGRADED_GRACE_MS;
	}

	/**
	 * A connectivity report that the network is up while Tor is still
	 * stuck without a working connection is the signal the wrapper's
	 * idempotent enable cannot carry: Tor is told the network went away and
	 * came back, which makes it retry its guards at once and republish the
	 * hidden service instead of waiting out its own retry schedule.
	 */
	@Override
	public void setNetworkEnabled(boolean enabled) {
		if (!running.get()) return;
		if (enabled && degradedSinceMs != 0 && restartNetworkNow()) return;
		try {
			tor.enableNetwork(enabled);
		} catch (IOException e) {
		}
	}

	@Override
	public void restartNetwork() {
		if (!running.get()) return;
		restartNetworkNow();
	}

	private boolean restartNetworkNow() {
		synchronized (restartLock) {
			long now = clock.getAsLong();
			if (now - lastRestartMs < MIN_RESTART_INTERVAL_MS) return false;
			lastRestartMs = now;
		}
		try {
			tor.enableNetwork(false);
			tor.enableNetwork(true);
		} catch (IOException e) {
		}
		return true;
	}

	public void stop() throws IOException, InterruptedException {
		running.set(false);
		ServerSocket ss = serverSocket;
		if (ss != null) closeQuietly(ss);
		tor.stop();
	}

	private static void closeQuietly(java.io.Closeable c) {
		try {
			c.close();
		} catch (IOException ignored) {
		}
	}
}
