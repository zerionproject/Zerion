package org.zerionproject.transport;

import org.briarproject.onionwrapper.TorWrapper;
import org.briarproject.onionwrapper.TorWrapper.HiddenServiceProperties;
import org.briarproject.onionwrapper.TorWrapper.TorState;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import static org.zerionproject.wire.ZwfConstants.TAG_LENGTH;
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

	/**
	 * A connection that has not delivered its stream tag holds a slot for at
	 * most this long. The tag is the first thing an honest peer writes, so a
	 * silent connection is not a peer waiting for us but a slot being held.
	 */
	static final int TAG_READ_TIMEOUT_MS = 5_000;

	/**
	 * Slots for connections that have not yet delivered a tag, separate from
	 * and smaller than the total, so silent connections cannot take every
	 * slot away from authenticated sessions.
	 */
	static final int MAX_PRE_TAG_CONNECTIONS = 16;
	/** How long Tor may sit without a working connection, after having had
	 * one, before the transport counts as degraded. */
	static final long DEGRADED_GRACE_MS = 45_000;
	/** Two network restarts are never closer together than this. */
	static final long MIN_RESTART_INTERVAL_MS = 60_000;
	/** Delay before the second attempt to bring a dead Tor back. */
	static final long PROCESS_RESTART_BACKOFF_MIN_MS = 30_000;
	/** The delay between attempts doubles up to this. */
	static final long PROCESS_RESTART_BACKOFF_MAX_MS = 10 * 60_000;

	/** Sleeps for the given number of milliseconds. */
	interface Sleeper {
		void sleep(long ms) throws InterruptedException;
	}

	/** A hidden service this transport has published, kept for republishing. */
	private static final class PublishedService {
		final int localPort;
		final int remotePort;
		final String privateKey;

		PublishedService(int localPort, int remotePort, String privateKey) {
			this.localPort = localPort;
			this.remotePort = remotePort;
			this.privateKey = privateKey;
		}
	}

	private final TorWrapper tor;
	private final SocketFactory socketFactory;
	private final SocketFactory fastSocketFactory;
	private final Executor ioExecutor;
	private final ZtpConnectionHandler handler;
	@Nullable
	private final TorBridgeConfigurator bridgeConfigurator;
	private final TorPrivacyConfigurator privacyConfigurator;
	private final TorProcessWatch processWatch;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean torDead = new AtomicBoolean(false);
	private final Map<String, PublishedService> published =
			Collections.synchronizedMap(new LinkedHashMap<>());
	volatile Sleeper sleeper = Thread::sleep;
	private final Semaphore inboundLimiter =
			new Semaphore(MAX_INBOUND_CONNECTIONS);
	private final Semaphore preTagLimiter =
			new Semaphore(MAX_PRE_TAG_CONNECTIONS);
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
			TorBridgeConfigurator bridgeConfigurator,
			TorPrivacyConfigurator privacyConfigurator) {
		this(tor, socketFactory, fastSocketFactory, ioExecutor, handler,
				bridgeConfigurator, privacyConfigurator,
				new TorProcessWatch());
	}

	public ZtpTorTransport(TorWrapper tor, SocketFactory socketFactory,
			SocketFactory fastSocketFactory, Executor ioExecutor,
			ZtpConnectionHandler handler,
			TorBridgeConfigurator bridgeConfigurator,
			TorPrivacyConfigurator privacyConfigurator,
			TorProcessWatch processWatch) {
		this.tor = tor;
		this.socketFactory = socketFactory;
		this.fastSocketFactory = fastSocketFactory;
		this.ioExecutor = ioExecutor;
		this.handler = handler;
		this.bridgeConfigurator = bridgeConfigurator;
		this.privacyConfigurator = privacyConfigurator;
		this.processWatch = processWatch;
		processWatch.setListener(this::onControlConnectionLost);
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
		torDead.set(false);
		processWatch.setListener(this::onControlConnectionLost);
		try {
			startTor();
		} catch (IOException | InterruptedException e) {
			running.set(false);
			throw e;
		}
		startAccepting(0);
		return publishHiddenService(localPort, REMOTE_ONION_PORT, privateKey);
	}

	/**
	 * Starts the wrapper and applies every setting the transport requires,
	 * failing closed (Tor stopped again) if any of them cannot be verified.
	 */
	private void startTor() throws IOException, InterruptedException {
		tor.start();
		try {
			tor.enableConnectionPadding(true);
			privacyConfigurator.applyAndVerify();
		} catch (IOException e) {
			try {
				tor.stop();
			} catch (IOException | InterruptedException ignored) {
			}
			throw e;
		}
		if (!bridgesApplied()) {
			try {
				tor.stop();
			} catch (IOException ignored) {
			}
			throw new IOException("bridge configuration failed");
		}
		if (!running.get()) {
			tor.stop();
			throw new IOException("stopped during start");
		}
		tor.enableNetwork(true);
	}

	/**
	 * Publishes a hidden service through the wrapper and remembers it, so
	 * that it is published again after Tor has been restarted.
	 */
	public HiddenServiceProperties publishHiddenService(int localPort,
			int remotePort, @Nullable String privateKey) throws IOException {
		HiddenServiceProperties hs = tor.publishHiddenService(localPort,
				remotePort, privateKey);
		if (hs == null) throw new IOException("hidden service not published");
		published.put(hs.onion,
				new PublishedService(localPort, remotePort, hs.privKey));
		return hs;
	}

	public void removeHiddenService(String onion) throws IOException {
		published.remove(onion);
		tor.removeHiddenService(onion);
	}

	/** The onions currently published by this transport. */
	List<String> publishedOnions() {
		synchronized (published) {
			return new ArrayList<>(published.keySet());
		}
	}

	/**
	 * The wrapper reports the loss of its control connection while Tor is
	 * meant to be running: the tor child has died. The wrapper itself keeps
	 * reporting the last state it saw, so the transport marks Tor dead at
	 * once (no dial reaches the SOCKS port again until Tor is back) and
	 * restarts it on the I/O executor, with a doubling delay between
	 * attempts, republishing every hidden service it had published.
	 */
	void onControlConnectionLost() {
		if (!running.get()) return;
		if (!torDead.compareAndSet(false, true)) return;
		ioExecutor.execute(this::restartTorUntilUp);
	}

	boolean isTorDead() {
		return torDead.get();
	}

	private void restartTorUntilUp() {
		long backoff = PROCESS_RESTART_BACKOFF_MIN_MS;
		while (running.get()) {
			try {
				restartTorOnce();
				torDead.set(false);
				return;
			} catch (IOException e) {
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			try {
				sleeper.sleep(backoff);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			backoff = Math.min(backoff * 2, PROCESS_RESTART_BACKOFF_MAX_MS);
		}
	}

	private void restartTorOnce() throws IOException, InterruptedException {
		try {
			tor.stop();
		} catch (IOException ignored) {
		}
		if (!running.get()) return;
		startTor();
		if (!running.get()) {
			try {
				tor.enableNetwork(false);
			} catch (IOException ignored) {
			}
			tor.stop();
			return;
		}
		List<PublishedService> services;
		synchronized (published) {
			services = new ArrayList<>(published.values());
		}
		for (PublishedService s : services) {
			HiddenServiceProperties hs = tor.publishHiddenService(s.localPort,
					s.remotePort, s.privateKey);
			if (hs == null) throw new IOException("republish failed");
		}
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
		if (!preTagLimiter.tryAcquire()) {
			closeQuietly(socket);
			inboundLimiter.release();
			return;
		}
		java.util.concurrent.atomic.AtomicBoolean tagDelivered =
				new java.util.concurrent.atomic.AtomicBoolean(false);
		Runnable onTagDelivered = () -> {
			if (tagDelivered.compareAndSet(false, true)) {
				preTagLimiter.release();
				try {
					socket.setSoTimeout(SOCKET_TIMEOUT_MS);
				} catch (java.net.SocketException ignored) {
				}
			}
		};
		try {
			socket.setSoTimeout(TAG_READ_TIMEOUT_MS);
			try {
				socket.setTcpNoDelay(true);
			} catch (java.net.SocketException ignored) {
			}
			InputStream in = new PreambleDeadlineInputStream(
					socket.getInputStream(), TAG_LENGTH, onTagDelivered,
					clock, TAG_READ_TIMEOUT_MS);
			handler.handleIncoming(TorConstants.ID, in,
					socket.getOutputStream());
		} catch (IOException e) {
		} finally {
			closeQuietly(socket);
			if (tagDelivered.compareAndSet(false, true)) {
				preTagLimiter.release();
			}
			inboundLimiter.release();
		}
	}

	/**
	 * Counts the bytes delivered to the reader and runs the callback once the
	 * preamble length has been reached, so the accept path can move a
	 * connection from the short pre-tag budget to the session budget. The
	 * preamble must arrive in full within a wall-clock deadline measured
	 * from the accept: the socket's read timeout only bounds the gap
	 * between bytes, so a peer dripping one byte per timeout would
	 * otherwise hold a pre-tag slot for the whole preamble length times
	 * the timeout.
	 */
	static final class PreambleDeadlineInputStream
			extends java.io.FilterInputStream {

		private final int preambleLength;
		private final Runnable onPreambleDelivered;
		private final LongSupplier clock;
		private final long deadlineMs;
		private final long startedMs;
		private long delivered = 0;
		private boolean signalled = false;

		PreambleDeadlineInputStream(InputStream in, int preambleLength,
				Runnable onPreambleDelivered, LongSupplier clock,
				long deadlineMs) {
			super(in);
			this.preambleLength = preambleLength;
			this.onPreambleDelivered = onPreambleDelivered;
			this.clock = clock;
			this.deadlineMs = deadlineMs;
			this.startedMs = clock.getAsLong();
		}

		private void checkDeadline() throws IOException {
			if (!signalled && clock.getAsLong() - startedMs > deadlineMs) {
				throw new IOException("preamble deadline passed");
			}
		}

		@Override
		public int read() throws IOException {
			checkDeadline();
			int b = in.read();
			if (b >= 0) count(1);
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			checkDeadline();
			int n = in.read(b, off, len);
			if (n > 0) count(n);
			return n;
		}

		private void count(int n) {
			delivered += n;
			if (!signalled && delivered >= preambleLength) {
				signalled = true;
				onPreambleDelivered.run();
			}
		}
	}

	/**
	 * Dials only while Tor is alive and connected. A dead Tor leaves its
	 * SOCKS port free for any local process to bind, so nothing may be
	 * offered to that port until Tor is back; a Tor that has not built a
	 * circuit cannot complete the dial anyway.
	 */
	@Override
	public long dial(int contactId, String peerOnion, boolean fast) {
		if (torDead.get() || tor.getTorState() != TorState.CONNECTED) {
			return DIAL_NOT_CONNECTED;
		}
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
	 * A connectivity report that the network is up while Tor has been
	 * stuck without a working connection for longer than the grace period
	 * is the signal the wrapper's idempotent enable cannot carry: Tor is
	 * told the network went away and came back, which makes it retry its
	 * guards at once and republish the hidden service instead of waiting
	 * out its own retry schedule. Inside the grace period Tor is only
	 * reconnecting after a brief dip and a bounce would cut the live
	 * sessions it is about to recover, so the report is a plain enable.
	 */
	@Override
	public void setNetworkEnabled(boolean enabled) {
		if (!running.get()) return;
		if (enabled && !bridgesApplied()) {
			try {
				tor.enableNetwork(false);
			} catch (IOException e) {
			}
			return;
		}
		if (enabled && isNetworkDegraded() && restartNetworkNow()) return;
		try {
			tor.enableNetwork(enabled);
		} catch (IOException e) {
		}
	}

	/**
	 * The bridge configuration is re-applied before every enable: a
	 * configuration Tor refused after the start (a mistyped line, a control
	 * port hiccup) has disabled the network, and an enable that skipped the
	 * check would connect without the bridges the user asked for.
	 */
	private boolean bridgesApplied() {
		TorBridgeConfigurator b = bridgeConfigurator;
		return b == null || b.apply();
	}

	@Override
	public void restartNetwork() {
		if (!running.get()) return;
		restartNetworkNow();
	}

	/**
	 * The wrapper's current state is read at the moment of the restart, so a
	 * network that was deliberately disabled, or a Tor that is stopping, in
	 * the instant before the state event reaches this transport is never
	 * re-enabled by a restart.
	 */
	private boolean restartNetworkNow() {
		synchronized (restartLock) {
			if (tor.getTorState() != TorState.CONNECTING) return false;
			long now = clock.getAsLong();
			if (now - lastRestartMs < MIN_RESTART_INTERVAL_MS) return false;
			lastRestartMs = now;
		}
		try {
			tor.enableNetwork(false);
			if (bridgesApplied()) tor.enableNetwork(true);
		} catch (IOException e) {
		}
		return true;
	}

	public void stop() throws IOException, InterruptedException {
		running.set(false);
		processWatch.setListener(null);
		ServerSocket ss = serverSocket;
		if (ss != null) closeQuietly(ss);
		published.clear();
		tor.stop();
	}

	private static void closeQuietly(java.io.Closeable c) {
		try {
			c.close();
		} catch (IOException ignored) {
		}
	}
}
