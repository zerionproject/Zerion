package org.zerionproject.app.channel;

import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.net.SocketFactory;

@NotNullByDefault
public class TorChannelTransport implements ChannelTransport {

	private static final int CONNECT_TIMEOUT_MS = 60_000;
	private static final int READ_TIMEOUT_MS = 120_000;
	private static final int REMOTE_PORT = 80;
	private static final int MAX_REQUEST_BYTES = 256 * 1024;
	private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
	private static final int READ_CHUNK_BYTES = 64 * 1024;
	private static final int MAX_CONCURRENT_HANDLERS = 16;
	private static final long SERVER_READ_DEADLINE_MS = READ_TIMEOUT_MS;
	private static final long CLIENT_READ_DEADLINE_MS = 20L * 60L * 1000L;

	private final OnionPublisher onionPublisher;
	private final SocketFactory torSocketFactory;
	private final Executor ioExecutor;
	private final java.util.concurrent.ThreadPoolExecutor handlerExecutor =
			new java.util.concurrent.ThreadPoolExecutor(
					0, MAX_CONCURRENT_HANDLERS,
					60L, java.util.concurrent.TimeUnit.SECONDS,
					new java.util.concurrent.SynchronousQueue<>(),
					r -> {
						Thread t = new Thread(r, "ChannelHandler");
						t.setDaemon(true);
						return t;
					},
					new java.util.concurrent.ThreadPoolExecutor
							.AbortPolicy());
	private final ConcurrentHashMap<String, ServerSocket>
			boundSockets = new ConcurrentHashMap<>();

	@Inject
	public TorChannelTransport(OnionPublisher onionPublisher,
			SocketFactory torSocketFactory,
			@IoExecutor Executor ioExecutor) {
		this.onionPublisher = onionPublisher;
		this.torSocketFactory = torSocketFactory;
		this.ioExecutor = ioExecutor;
	}

	@Override
	public ChannelServer bindServer(byte[] channelId,
			@javax.annotation.Nullable String onionPrivateKey,
			ChannelRequestHandler handler) throws IOException {
		ServerSocket ss = new ServerSocket();
		ss.bind(new InetSocketAddress("127.0.0.1", 0));
		int localPort = ss.getLocalPort();
		OnionPublisher.OnionHandle handle =
				onionPublisher.publish(localPort, onionPrivateKey);
		String onion = handle.getOnion();
		String returnedPrivKey = handle.getPrivateKey();
		boundSockets.put(onion, ss);
		ioExecutor.execute(() -> acceptLoop(ss, handler));
		return new ChannelServer() {
			@Override
			public String getOnionAddress() {
				return onion;
			}

			@javax.annotation.Nullable
			@Override
			public String getOnionPrivateKey() {
				return returnedPrivKey;
			}

			@Override
			public void close() {
				try {
					ss.close();
				} catch (IOException ignored) {
				}
				boundSockets.remove(onion);
				try {
					onionPublisher.unpublish(onion);
				} catch (IOException ignored) {
				}
			}
		};
	}

	@Override
	public byte[] requestFromOnion(String onion, byte[] requestBytes)
			throws IOException {
		if (requestBytes.length > MAX_REQUEST_BYTES) {
			throw new IOException("Request too large");
		}
		Socket s = torSocketFactory.createSocket();
		try {
			s.connect(new InetSocketAddress(stripDotOnion(onion)
					+ ".onion", REMOTE_PORT), CONNECT_TIMEOUT_MS);
			s.setSoTimeout(READ_TIMEOUT_MS);
			DataOutputStream out = new DataOutputStream(
					s.getOutputStream());
			out.writeInt(requestBytes.length);
			out.write(requestBytes);
			out.flush();
			DataInputStream in = new DataInputStream(
					s.getInputStream());
			int len = in.readInt();
			if (len < 0 || len > MAX_RESPONSE_BYTES) {
				throw new IOException(
						"Invalid response length: " + len);
			}
			return readBounded(in, len, CLIENT_READ_DEADLINE_MS);
		} finally {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
	}

	@Override
	public boolean isReachable(String onion) {
		Socket s = null;
		try {
			s = torSocketFactory.createSocket(
					stripDotOnion(onion) + ".onion", REMOTE_PORT);
			return true;
		} catch (Throwable t) {
			return false;
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	private void acceptLoop(ServerSocket ss,
			ChannelRequestHandler handler) {
		while (!ss.isClosed()) {
			Socket client;
			try {
				client = ss.accept();
			} catch (IOException e) {
				return;
			}
			try {
				handlerExecutor.execute(
						() -> handleClient(client, handler));
			} catch (java.util.concurrent.RejectedExecutionException e) {
				try {
					client.close();
				} catch (IOException ignored) {
				}
			}
		}
	}

	private void handleClient(Socket client,
			ChannelRequestHandler handler) {
		try {
			client.setSoTimeout(READ_TIMEOUT_MS);
			DataInputStream in = new DataInputStream(
					client.getInputStream());
			int len = in.readInt();
			if (len < 0 || len > MAX_REQUEST_BYTES) return;
			byte[] body = readBounded(in, len, SERVER_READ_DEADLINE_MS);
			byte[] response = handler.handle(body);
			if (response == null) response = new byte[0];
			DataOutputStream out = new DataOutputStream(
					client.getOutputStream());
			out.writeInt(response.length);
			out.write(response);
			out.flush();
		} catch (IOException ignored) {
		} finally {
			try {
				client.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static byte[] readBounded(DataInputStream in, int declaredLen,
			long maxTotalMs) throws IOException {
		long deadline = System.nanoTime() + maxTotalMs * 1_000_000L;
		java.io.ByteArrayOutputStream bos =
				new java.io.ByteArrayOutputStream(
						Math.min(declaredLen, READ_CHUNK_BYTES));
		byte[] buf = new byte[Math.min(READ_CHUNK_BYTES,
				Math.max(1, declaredLen))];
		int remaining = declaredLen;
		while (remaining > 0) {
			int r = in.read(buf, 0, Math.min(buf.length, remaining));
			if (r < 0) throw new java.io.EOFException();
			bos.write(buf, 0, r);
			remaining -= r;
			if (System.nanoTime() - deadline > 0) {
				throw new IOException("Read exceeded total deadline");
			}
		}
		return bos.toByteArray();
	}

	private static String stripDotOnion(String onion) {
		if (onion.endsWith(".onion")) {
			return onion.substring(0, onion.length() - 6);
		}
		return onion;
	}
}
