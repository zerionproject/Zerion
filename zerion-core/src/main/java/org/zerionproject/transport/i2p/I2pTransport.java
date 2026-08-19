package org.zerionproject.transport.i2p;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

/**
 * Produces connected streams over I2P and runs them through the shared session
 * stack, mirroring {@link org.zerionproject.transport.ZtpTorTransport}. A
 * {@link Sam3Session} against the local router bridge supplies the streams:
 * inbound connections are forwarded by the router to a local {@code
 * ServerSocket} we accept, and outbound connections are dialled with SAM
 * STREAM CONNECT. Everything below {@link ZtpConnectionHandler} is unchanged.
 */
@NotNullByDefault
public class I2pTransport implements I2pOverlayTransport {

	private static final long ACCEPT_RETRY_DELAY_MS = 500;
	private static final int MAX_INBOUND_CONNECTIONS = 64;

	private final String samHost;
	private final int samPort;
	private final I2pRouter router;
	private final Executor ioExecutor;
	private final ZtpConnectionHandler handler;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final Semaphore inboundLimiter =
			new Semaphore(MAX_INBOUND_CONNECTIONS);

	@Nullable
	private volatile Sam3Session session;
	@Nullable
	private volatile ServerSocket serverSocket;

	public I2pTransport(String samHost, int samPort, I2pRouter router,
			Executor ioExecutor, ZtpConnectionHandler handler) {
		this.samHost = samHost;
		this.samPort = samPort;
		this.router = router;
		this.ioExecutor = ioExecutor;
		this.handler = handler;
	}

	/**
	 * Opens a SAM session (recreating the destination from {@code privateKey}
	 * when non-null), binds a local accept socket, and asks the router to
	 * forward inbound streams to it. Returns the destination to publish and the
	 * key to persist.
	 */
	public I2pDestination start(@Nullable String privateKey)
			throws IOException {
		if (!running.compareAndSet(false, true)) {
			throw new IllegalStateException("already started");
		}
		try {
			router.start();
			Sam3Session s = Sam3Session.open(samHost, samPort,
					I2pConstants.SAM_CONNECT_TIMEOUT, I2pConstants.SESSION_ID,
					privateKey);
			session = s;
			ServerSocket ss = new ServerSocket();
			ss.bind(new InetSocketAddress("127.0.0.1", 0));
			serverSocket = ss;
			s.forwardTo(ss.getLocalPort());
			ioExecutor.execute(() -> acceptLoop(ss));
			return new I2pDestination(s.getLocalDestination(),
					s.getPrivateKey());
		} catch (IOException | RuntimeException e) {
			ServerSocket ss = serverSocket;
			if (ss != null) closeQuietly(ss);
			Sam3Session s = session;
			if (s != null) s.close();
			serverSocket = null;
			session = null;
			router.stop();
			running.set(false);
			throw e;
		}
	}

	public int getLocalPort() {
		ServerSocket ss = serverSocket;
		return ss == null ? -1 : ss.getLocalPort();
	}

	@Override
	public TransportId getTransportId() {
		return I2pConstants.ID;
	}

	@Override
	public String getAddressPropertyKey() {
		return I2pConstants.PROP_I2P_DEST;
	}

	@Override
	public long dial(int contactId, String peerAddress, boolean fast) {
		Sam3Session s = session;
		if (s == null) return DIAL_NOT_CONNECTED;
		Socket socket;
		try {
			socket = s.connect(peerAddress);
		} catch (IOException e) {
			return DIAL_NOT_CONNECTED;
		}
		long connectedAt = System.currentTimeMillis();
		try {
			configureSocket(socket);
			handler.handleOutgoing(I2pConstants.ID, contactId,
					socket.getInputStream(), socket.getOutputStream());
		} catch (IOException e) {
			// close below
		} finally {
			closeQuietly(socket);
		}
		return System.currentTimeMillis() - connectedAt;
	}

	@Override
	public void setNetworkEnabled(boolean enabled) {
		// The I2P router's network lifecycle is owned by the router process,
		// not toggled through SAM here.
	}

	@Override
	public void setOnSessionReady(Runnable callback) {
		callback.run();
	}

	public void stop() {
		running.set(false);
		ServerSocket ss = serverSocket;
		if (ss != null) closeQuietly(ss);
		Sam3Session s = session;
		if (s != null) s.close();
		router.stop();
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
			handler.handleIncoming(I2pConstants.ID, socket.getInputStream(),
					socket.getOutputStream());
		} catch (IOException e) {
			// close below
		} finally {
			closeQuietly(socket);
			inboundLimiter.release();
		}
	}

	private static void configureSocket(Socket socket) throws IOException {
		socket.setSoTimeout(I2pConstants.STREAM_SOCKET_TIMEOUT);
		try {
			socket.setTcpNoDelay(true);
		} catch (java.net.SocketException ignored) {
			// best effort
		}
	}

	private static void closeQuietly(java.io.Closeable c) {
		try {
			c.close();
		} catch (IOException ignored) {
			// nothing to do
		}
	}
}
