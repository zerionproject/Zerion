package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelTransport.ChannelServer;
import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * A channel server handles a bounded number of requests at once. Beyond
 * that bound a new connection is closed without being read, the bound is a
 * cap on concurrency rather than a queue, and the server serves again as
 * soon as a handler returns.
 */
public class TorChannelTransportHandlerCapTest {

	private static final int HANDLERS = 16;

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final CountDownLatch release = new CountDownLatch(1);
	private final AtomicInteger entered = new AtomicInteger();
	private final AtomicInteger port = new AtomicInteger();
	private final List<Socket> sockets = new ArrayList<>();
	private ChannelServer server;

	@After
	public void tearDown() {
		release.countDown();
		for (Socket s : sockets) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
		if (server != null) server.close();
		exec.shutdownNow();
	}

	@Test(timeout = 60_000)
	public void requestsBeyondTheHandlerCapAreRefusedUntilOneFinishes()
			throws Exception {
		OnionPublisher publisher = new OnionPublisher() {
			@Override
			public OnionHandle publish(int localPort, String privateKey) {
				port.set(localPort);
				return new OnionHandle("channelonion", "key");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		TorChannelTransport transport = new TorChannelTransport(publisher,
				SocketFactory.getDefault(), exec);
		server = transport.bindServer(new byte[32], null, request -> {
			entered.incrementAndGet();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new byte[] {7};
		});

		for (int i = 0; i < HANDLERS; i++) request(open());
		waitForEntered(HANDLERS);

		Socket refused = open();
		request(refused);
		assertClosedWithoutAResponse(refused);
		assertEquals(HANDLERS, entered.get());

		release.countDown();
		for (int i = 0; i < HANDLERS; i++) {
			assertEquals(7, readResponse(sockets.get(i)));
		}
		Socket served = open();
		request(served);
		assertEquals(7, readResponse(served));
		assertEquals(HANDLERS + 1, entered.get());
	}

	private Socket open() throws IOException {
		Socket s = new Socket("127.0.0.1", port.get());
		s.setSoTimeout(10_000);
		sockets.add(s);
		return s;
	}

	private static void request(Socket s) throws IOException {
		DataOutputStream out = new DataOutputStream(s.getOutputStream());
		out.writeInt(3);
		out.write(new byte[] {1, 2, 3});
		out.flush();
	}

	private static int readResponse(Socket s) throws IOException {
		DataInputStream in = new DataInputStream(s.getInputStream());
		int len = in.readInt();
		assertEquals(1, len);
		return in.readByte();
	}

	private static void assertClosedWithoutAResponse(Socket s)
			throws IOException {
		s.setSoTimeout(3_000);
		try {
			int r = s.getInputStream().read();
			assertEquals(-1, r);
		} catch (SocketTimeoutException e) {
			fail("the connection was kept open");
		} catch (IOException expected) {
		}
	}

	private void waitForEntered(int n) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		while (entered.get() < n && System.currentTimeMillis() < deadline) {
			Thread.sleep(20);
		}
		assertEquals(n, entered.get());
	}
}
