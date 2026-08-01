package com.professor.zerion.android.i2p;

import net.i2p.client.I2PClientFactory;
import net.i2p.client.streaming.I2PServerSocket;
import net.i2p.client.streaming.I2PSocket;
import net.i2p.client.streaming.I2PSocketManager;
import net.i2p.client.streaming.I2PSocketManagerFactory;
import net.i2p.data.Base64;
import net.i2p.data.Destination;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.zerionproject.transport.i2p.I2pDestination;
import org.zerionproject.transport.i2p.I2pOverlayTransport;
import org.zerionproject.transport.i2p.I2pRouter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

/**
 * Carries contact traffic over I2P through the in-process bundled router using
 * the I2CP streaming library, the embedded-router counterpart of the SAM-based
 * transport. Our address comes straight from the persisted keypair, so it is
 * published as soon as the plugin starts; the I2CP session is then brought up in
 * the background and retried until the router has tunnels, so a slow first-boot
 * reseed never blocks or kills anything. Inbound streams are taken from an
 * {@link I2PServerSocket}, outbound ones dialled with {@link
 * I2PSocketManager#connect}, and both run through the shared session stack.
 * Debug builds only.
 */
@NotNullByDefault
public class I2pStreamingTransport implements I2pOverlayTransport {

	private static final int MAX_INBOUND_CONNECTIONS = 64;
	private static final long ACCEPT_TIMEOUT_MS = 30_000;
	private static final long SESSION_RETRY_MS = 15_000;

	private final I2pRouter router;
	private final Executor ioExecutor;
	private final ZtpConnectionHandler handler;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final Semaphore inboundLimiter =
			new Semaphore(MAX_INBOUND_CONNECTIONS);

	@Nullable
	private volatile I2PSocketManager manager;

	public I2pStreamingTransport(I2pRouter router, Executor ioExecutor,
			ZtpConnectionHandler handler) {
		this.router = router;
		this.ioExecutor = ioExecutor;
		this.handler = handler;
	}

	@Override
	public I2pDestination start(@Nullable String privateKey)
			throws IOException {
		if (!running.compareAndSet(false, true)) {
			throw new IllegalStateException("already started");
		}
		try {
			byte[] keyBytes = privateKey != null
					? Base64.decode(privateKey) : generateKeys();
			if (keyBytes == null) throw new IOException("Invalid I2P key");
			Destination myDest = Destination.create(
					new ByteArrayInputStream(keyBytes));
			router.start();
			ioExecutor.execute(() -> initSession(keyBytes));
			return new I2pDestination(myDest.toBase64(),
					Base64.encode(keyBytes));
		} catch (Exception e) {
			running.set(false);
			throw e instanceof IOException ? (IOException) e
					: new IOException(e);
		}
	}

	private void initSession(byte[] keyBytes) {
		int attempt = 0;
		while (running.get()) {
			attempt++;
			try {
				I2PSocketManager mgr = I2PSocketManagerFactory.createManager(
						new ByteArrayInputStream(keyBytes), new Properties());
				if (mgr != null) {
					mgr.setAcceptTimeout(ACCEPT_TIMEOUT_MS);
					manager = mgr;
					acceptLoop(mgr);
					return;
				}
			} catch (RuntimeException e) {
			}
			if (!running.get()) return;
			try {
				Thread.sleep(SESSION_RETRY_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
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
		I2PSocketManager mgr = manager;
		if (mgr == null) return DIAL_NOT_CONNECTED;
		I2PSocket socket;
		try {
			socket = mgr.connect(new Destination(peerAddress));
		} catch (Exception e) {
			return DIAL_NOT_CONNECTED;
		}
		long connectedAt = System.currentTimeMillis();
		try {
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
		// The router owns its own network lifecycle.
	}

	@Override
	public void stop() {
		running.set(false);
		I2PSocketManager mgr = manager;
		if (mgr != null) mgr.destroySocketManager();
		manager = null;
		router.stop();
	}

	private byte[] generateKeys() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			I2PClientFactory.createClient().createDestination(out);
		} catch (Exception e) {
			throw new IOException("Could not generate I2P destination", e);
		}
		return out.toByteArray();
	}

	private void acceptLoop(I2PSocketManager mgr) {
		I2PServerSocket serverSocket = mgr.getServerSocket();
		while (running.get() && !mgr.isDestroyed()) {
			I2PSocket socket;
			try {
				socket = serverSocket.accept();
			} catch (Exception e) {
				if (!running.get()) return;
				continue;
			}
			if (socket == null) continue;
			if (inboundLimiter.availablePermits() <= 0) {
				closeQuietly(socket);
				continue;
			}
			ioExecutor.execute(() -> handleAccepted(socket));
		}
	}

	private void handleAccepted(I2PSocket socket) {
		if (!inboundLimiter.tryAcquire()) {
			closeQuietly(socket);
			return;
		}
		try {
			handler.handleIncoming(I2pConstants.ID, socket.getInputStream(),
					socket.getOutputStream());
		} catch (IOException e) {
			// close below
		} finally {
			closeQuietly(socket);
			inboundLimiter.release();
		}
	}

	private static void closeQuietly(I2PSocket socket) {
		try {
			socket.close();
		} catch (IOException ignored) {
			// nothing to do
		}
	}
}
