package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelTransport.ChannelServer;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TorChannelTransportDeadlineTest {

	private static final int HANDLERS = 16;
	private static final int PER_SERVER =
			TorChannelTransport.MAX_HANDLERS_PER_CHANNEL;
	private static final long HEADER_DEADLINE_MS = 3_000L;
	private static final long WRITE_STALL_MS = 3_000L;
	private static final long SERVED_WITHIN_MS = 60_000L;
	private static final int STALLED_RESPONSE_BYTES = 1024 * 1024;
	private static final int SMALL_BUFFER_BYTES = 4096;

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final AtomicInteger port = new AtomicInteger();
	private final AtomicInteger entered = new AtomicInteger();
	private final List<Socket> sockets = new ArrayList<>();
	private ChannelServer server;

	@After
	public void tearDown() {
		for (Socket s : sockets) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
		if (server != null) server.close();
		exec.shutdownNow();
	}

	@Test(timeout = 120_000)
	public void silentConnectionsFreeTheirHandlersAtTheHeaderDeadline()
			throws Exception {
		bind();
		for (int i = 0; i < HANDLERS; i++) open(64 * 1024);
		requestRefused(open(64 * 1024));
		assertServedEventually();
	}

	@Test(timeout = 120_000)
	public void stalledReadersFreeTheirHandlersAtTheWriteDeadline()
			throws Exception {
		bind();
		for (int i = 0; i < PER_SERVER; i++) {
			request(open(SMALL_BUFFER_BYTES), (byte) 1);
		}
		waitForEntered(PER_SERVER);
		requestRefused(open(64 * 1024));
		assertServedEventually();
	}

	private void bind() throws IOException {
		OnionPublisher publisher = new OnionPublisher() {
			@Override
			public OnionTargetListener openTarget() throws IOException {
				return withSmallSendBuffers(OnionPublisher.super.openTarget());
			}

			@Override
			public OnionHandle publish(int localPort, String privateKey) {
				port.set(localPort);
				return new OnionHandle("channelonion", "key");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		TorChannelTransport transport = transport(publisher);
		server = transport.bindServer(new byte[32], null, request -> {
			entered.incrementAndGet();
			if (request.length > 0 && request[0] == 1) {
				return new byte[STALLED_RESPONSE_BYTES];
			}
			return new byte[] {7};
		});
	}

	private static OnionTargetListener withSmallSendBuffers(
			OnionTargetListener target) {
		return new OnionTargetListener() {
			@Override
			public String getTorTarget() {
				return target.getTorTarget();
			}

			@Override
			public Socket accept() throws IOException {
				Socket s = target.accept();
				try {
					s.setSendBufferSize(SMALL_BUFFER_BYTES);
				} catch (SocketException ignored) {
				}
				return s;
			}

			@Override
			public boolean isClosed() {
				return target.isClosed();
			}

			@Override
			public void close() throws IOException {
				target.close();
			}
		};
	}

	private TorChannelTransport transport(OnionPublisher publisher) {
		return new TorChannelTransport(publisher, SocketFactory.getDefault(),
				exec, HEADER_DEADLINE_MS, WRITE_STALL_MS);
	}

	private Socket open(int receiveBuffer) throws IOException {
		Socket s = new Socket();
		s.setReceiveBufferSize(receiveBuffer);
		s.connect(new InetSocketAddress("127.0.0.1", port.get()));
		s.setSoTimeout(10_000);
		sockets.add(s);
		return s;
	}

	private static void requestRefused(Socket s) throws IOException {
		try {
			request(s, (byte) 2);
		} catch (IOException refusedBeforeTheRequest) {
			return;
		}
		assertClosedWithoutAResponse(s);
	}

	private static void request(Socket s, byte kind) throws IOException {
		DataOutputStream out = new DataOutputStream(s.getOutputStream());
		out.writeInt(3);
		out.write(new byte[] {kind, 2, 3});
		out.flush();
	}

	private void assertServedEventually() throws Exception {
		long deadline = System.currentTimeMillis() + SERVED_WITHIN_MS;
		while (!served(open(64 * 1024))) {
			if (System.currentTimeMillis() > deadline) {
				fail("no handler was free again");
			}
			Thread.sleep(50);
		}
	}

	private static boolean served(Socket s) {
		int length;
		byte body;
		try {
			request(s, (byte) 2);
			DataInputStream in = new DataInputStream(s.getInputStream());
			length = in.readInt();
			body = in.readByte();
		} catch (IOException refused) {
			return false;
		}
		assertEquals(1, length);
		assertEquals(7, body);
		return true;
	}

	private static void assertClosedWithoutAResponse(Socket s)
			throws IOException {
		s.setSoTimeout(10_000);
		try {
			int r = s.getInputStream().read();
			assertEquals(-1, r);
		} catch (SocketTimeoutException e) {
			fail("the connection was kept open");
		} catch (IOException expected) {
		}
	}

	private void waitForEntered(int n) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 15_000;
		while (entered.get() < n && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertEquals(n, entered.get());
	}
}
