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

public class TorChannelTransportHandlerCapTest {

	private static final int PER_SERVER =
			TorChannelTransport.MAX_HANDLERS_PER_CHANNEL;
	private static final int CHANNELS = 3;
	private static final int HANDLERS = CHANNELS * PER_SERVER;

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final CountDownLatch release = new CountDownLatch(1);
	private final AtomicInteger entered = new AtomicInteger();
	private final List<Integer> ports =
			java.util.Collections.synchronizedList(new ArrayList<>());
	private final List<Socket> sockets = new ArrayList<>();
	private final List<ChannelServer> servers = new ArrayList<>();

	@After
	public void tearDown() {
		release.countDown();
		for (Socket s : sockets) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
		for (ChannelServer server : servers) server.close();
		exec.shutdownNow();
	}

	@Test(timeout = 60_000)
	public void requestsBeyondTheHandlerCapAreRefusedUntilOneFinishes()
			throws Exception {
		OnionPublisher publisher = new OnionPublisher() {
			@Override
			public OnionHandle publish(int localPort, String privateKey) {
				ports.add(localPort);
				return new OnionHandle("channelonion" + localPort, "key");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		TorChannelTransport transport = new TorChannelTransport(publisher,
				SocketFactory.getDefault(), exec);
		for (int i = 0; i < CHANNELS; i++) {
			servers.add(transport.bindServer(new byte[] {(byte) i}, null,
					request -> {
				entered.incrementAndGet();
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return new byte[] {7};
			}));
		}

		for (int i = 0; i < HANDLERS; i++) {
			request(open(ports.get(i / PER_SERVER)));
		}
		waitForEntered(HANDLERS);

		Socket refused = open(ports.get(2));
		requestRefused(refused);
		assertEquals(HANDLERS, entered.get());

		release.countDown();
		for (int i = 0; i < HANDLERS; i++) {
			assertEquals(7, readResponse(sockets.get(i)));
		}
		Socket served = open(ports.get(0));
		request(served);
		assertEquals(7, readResponse(served));
		assertEquals(HANDLERS + 1, entered.get());
	}

	@Test(timeout = 60_000)
	public void oneOnionHoldsAtMostItsShareOfTheHandlers() throws Exception {
		OnionPublisher publisher = new OnionPublisher() {
			@Override
			public OnionHandle publish(int localPort, String privateKey) {
				ports.add(localPort);
				return new OnionHandle("channelonion" + localPort, "key");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		TorChannelTransport transport = new TorChannelTransport(publisher,
				SocketFactory.getDefault(), exec);
		servers.add(transport.bindServer(new byte[32], null, request -> {
			entered.incrementAndGet();
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new byte[] {7};
		}));
		for (int i = 0; i < PER_SERVER; i++) request(open(ports.get(0)));
		waitForEntered(PER_SERVER);
		Socket refused = open(ports.get(0));
		requestRefused(refused);
		assertEquals(PER_SERVER, entered.get());
	}

	private Socket open(int port) throws IOException {
		Socket s = new Socket("127.0.0.1", port);
		s.setSoTimeout(10_000);
		sockets.add(s);
		return s;
	}

	private static void requestRefused(Socket s) throws IOException {
		try {
			request(s);
		} catch (IOException refusedBeforeTheRequest) {
			return;
		}
		assertClosedWithoutAResponse(s);
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
