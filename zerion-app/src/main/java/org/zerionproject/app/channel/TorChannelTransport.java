package org.zerionproject.app.channel;

import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelTransport;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.net.SocketFactory;

@NotNullByDefault
public class TorChannelTransport implements ChannelTransport {

	private static final int CONNECT_TIMEOUT_MS = 60_000;
	private static final int READ_TIMEOUT_MS = 120_000;
	private static final int REMOTE_PORT = 80;
	private static final Pattern ONION_V3 = Pattern.compile("^[a-z2-7]{56}$");
	private static final int MAX_REQUEST_BYTES = 256 * 1024;
	private static final int MAX_RESPONSE_BYTES =
			ChannelConstants.MAX_RESPONSE_BYTES;
	private static final int READ_CHUNK_BYTES = 64 * 1024;
	private static final int MAX_HANDLER_CHANNELS = 32;
	static final int MAX_HANDLERS_PER_CHANNEL = 6;
	private static final int MAX_CONCURRENT_HANDLERS =
			MAX_HANDLER_CHANNELS * MAX_HANDLERS_PER_CHANNEL;
	private static final long HEADER_READ_DEADLINE_MS = 15_000L;
	private static final long SERVER_READ_DEADLINE_MS = READ_TIMEOUT_MS;
	private static final long CLIENT_READ_DEADLINE_MS = 20L * 60L * 1000L;
	private static final long WRITE_STALL_MS = 20_000L;
	private static final long SERVER_WRITE_DEADLINE_MS =
			CLIENT_READ_DEADLINE_MS;
	private static final long READ_GRACE_MS = 10_000L;
	private static final long MIN_READ_BYTES_PER_SECOND = 8L * 1024L;
	private static final long WRITE_GRACE_MS = 20_000L;
	private static final long MIN_WRITE_BYTES_PER_SECOND = 32L * 1024L;
	private static final int LARGE_RESPONSE_BYTES = 1024 * 1024;
	private static final int MAX_LARGE_RESPONSE_KIB_PER_CHANNEL = 16 * 1024;
	private static final long EGRESS_WINDOW_MS = 60L * 1000L;
	private static final long MAX_EGRESS_BYTES_PER_WINDOW_PER_CHANNEL =
			256L * 1024 * 1024;

	private static final class ChannelShare {
		final Semaphore handlers = new Semaphore(MAX_HANDLERS_PER_CHANNEL);
		final Semaphore largeKib =
				new Semaphore(MAX_LARGE_RESPONSE_KIB_PER_CHANNEL);
		final EgressBudget egress;

		ChannelShare(long egressBytesPerWindow) {
			egress = new EgressBudget(EGRESS_WINDOW_MS, egressBytesPerWindow,
					System::currentTimeMillis);
		}
	}

	private final ConcurrentHashMap<String, ChannelShare> shares =
			new ConcurrentHashMap<>();
	private final ScheduledThreadPoolExecutor watchdog = newWatchdog();

	private final OnionPublisher onionPublisher;
	private final SocketFactory torSocketFactory;
	private final Executor ioExecutor;
	private final long headerDeadlineMs;
	private final long writeStallMs;
	private final long egressBytesPerWindowPerChannel;
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
	private final ConcurrentHashMap<String, OnionTargetListener>
			boundSockets = new ConcurrentHashMap<>();

	@Inject
	public TorChannelTransport(OnionPublisher onionPublisher,
			SocketFactory torSocketFactory,
			@IoExecutor Executor ioExecutor) {
		this(onionPublisher, torSocketFactory, ioExecutor,
				HEADER_READ_DEADLINE_MS, WRITE_STALL_MS);
	}

	TorChannelTransport(OnionPublisher onionPublisher,
			SocketFactory torSocketFactory, Executor ioExecutor,
			long headerDeadlineMs, long writeStallMs) {
		this(onionPublisher, torSocketFactory, ioExecutor, headerDeadlineMs,
				writeStallMs, MAX_EGRESS_BYTES_PER_WINDOW_PER_CHANNEL);
	}

	TorChannelTransport(OnionPublisher onionPublisher,
			SocketFactory torSocketFactory, Executor ioExecutor,
			long headerDeadlineMs, long writeStallMs,
			long egressBytesPerWindowPerChannel) {
		this.onionPublisher = onionPublisher;
		this.torSocketFactory = torSocketFactory;
		this.ioExecutor = ioExecutor;
		this.headerDeadlineMs = headerDeadlineMs;
		this.writeStallMs = writeStallMs;
		this.egressBytesPerWindowPerChannel = egressBytesPerWindowPerChannel;
	}

	private static ScheduledThreadPoolExecutor newWatchdog() {
		ScheduledThreadPoolExecutor w = new ScheduledThreadPoolExecutor(1,
				r -> {
					Thread t = new Thread(r, "ChannelHandlerWatchdog");
					t.setDaemon(true);
					return t;
				});
		w.setRemoveOnCancelPolicy(true);
		return w;
	}

	@Override
	public ChannelServer bindServer(byte[] channelId,
			@javax.annotation.Nullable String onionPrivateKey,
			ChannelRequestHandler handler) throws IOException {
		OnionTargetListener ss = onionPublisher.openTarget();
		OnionPublisher.OnionHandle handle;
		try {
			handle = onionPublisher.publish(ss.getTorTarget(),
					onionPrivateKey);
		} catch (IOException | RuntimeException e) {
			closeQuietly(ss);
			throw e;
		}
		String onion = handle.getOnion();
		String returnedPrivKey = handle.getPrivateKey();
		boundSockets.put(onion, ss);
		ChannelShare share = shares.computeIfAbsent(toHex(channelId),
				k -> new ChannelShare(egressBytesPerWindowPerChannel));
		ioExecutor.execute(() -> acceptLoop(ss, handler, share));
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
				closeQuietly(ss);
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
		String host = stripDotOnion(onion).toLowerCase(Locale.ROOT);
		if (!ONION_V3.matcher(host).matches()) {
			throw new IOException("Not a v3 onion address");
		}
		Socket s = torSocketFactory.createSocket();
		try {
			s.connect(InetSocketAddress.createUnresolved(host + ".onion",
					REMOTE_PORT), CONNECT_TIMEOUT_MS);
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

	private void acceptLoop(OnionTargetListener ss,
			ChannelRequestHandler handler, ChannelShare share) {
		while (!ss.isClosed()) {
			Socket client;
			try {
				client = ss.accept();
			} catch (IOException e) {
				return;
			}
			if (!share.handlers.tryAcquire()) {
				closeQuietly(client);
				continue;
			}
			try {
				handlerExecutor.execute(() -> {
					try {
						handleClient(client, handler, share);
					} finally {
						share.handlers.release();
					}
				});
			} catch (java.util.concurrent.RejectedExecutionException e) {
				share.handlers.release();
				closeQuietly(client);
			}
		}
	}

	private static String toHex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
		return sb.toString();
	}

	private static void closeQuietly(java.io.Closeable c) {
		try {
			c.close();
		} catch (IOException ignored) {
		}
	}

	private void handleClient(Socket client,
			ChannelRequestHandler handler, ChannelShare share) {
		Deadline deadline = new Deadline(client);
		try {
			long start = System.nanoTime();
			deadline.arm(headerDeadlineMs);
			client.setSoTimeout(READ_TIMEOUT_MS);
			DataInputStream in = new DataInputStream(
					client.getInputStream());
			int len = in.readInt();
			if (len < 0 || len > MAX_REQUEST_BYTES) return;
			long readBudget = Math.min(SERVER_READ_DEADLINE_MS,
					READ_GRACE_MS + len * 1000L / MIN_READ_BYTES_PER_SECOND);
			deadline.arm(readBudget);
			byte[] body = readBounded(in, len, readBudget);
			deadline.cancel();
			byte[] response = handler.handle(body);
			if (response == null) response = new byte[0];
			DataOutputStream out = new DataOutputStream(
					client.getOutputStream());
			deadline.arm(writeStallMs);
			int heldKib = response.length > LARGE_RESPONSE_BYTES
					? (response.length + 1023) / 1024 : 0;
			if (heldKib > 0 && !share.largeKib.tryAcquire(heldKib)) {
				out.writeInt(0);
				out.flush();
				return;
			}
			try {
				if (share.egress.exhausted()) {
					out.writeInt(0);
					out.flush();
					return;
				}
				writeResponse(out, response, deadline, share.egress);
			} finally {
				if (heldKib > 0) share.largeKib.release(heldKib);
			}
		} catch (IOException ignored) {
		} finally {
			deadline.cancel();
			try {
				client.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void writeResponse(DataOutputStream out, byte[] response,
			Deadline deadline, EgressBudget egress) throws IOException {
		long allowedMs = Math.min(SERVER_WRITE_DEADLINE_MS, WRITE_GRACE_MS
				+ response.length * 1000L / MIN_WRITE_BYTES_PER_SECOND);
		long end = System.nanoTime() + allowedMs * 1_000_000L;
		out.writeInt(response.length);
		int off = 0;
		while (off < response.length) {
			long left = (end - System.nanoTime()) / 1_000_000L;
			if (left <= 0) throw new IOException("Write exceeded deadline");
			deadline.arm(Math.min(writeStallMs, left));
			int n = Math.min(READ_CHUNK_BYTES, response.length - off);
			if (!egress.tryCharge(n)) {
				throw new IOException("Egress budget spent");
			}
			out.write(response, off, n);
			out.flush();
			off += n;
		}
		out.flush();
	}

	private final class Deadline {

		private final Socket socket;
		@Nullable
		private ScheduledFuture<?> pending;

		private Deadline(Socket socket) {
			this.socket = socket;
		}

		void arm(long ms) {
			cancel();
			pending = watchdog.schedule(this::expire, Math.max(1L, ms),
					TimeUnit.MILLISECONDS);
		}

		void cancel() {
			if (pending != null) {
				pending.cancel(false);
				pending = null;
			}
		}

		private void expire() {
			try {
				socket.close();
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
