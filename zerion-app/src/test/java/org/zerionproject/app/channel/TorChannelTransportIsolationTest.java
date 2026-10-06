package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelTransport.ChannelServer;
import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.ConnectException;
import java.net.InetSocketAddress;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TorChannelTransportIsolationTest {

	private static final int MIB = 1024 * 1024;

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final List<Socket> sockets = new ArrayList<>();
	private final List<ChannelServer> servers = new ArrayList<>();
	private final List<Integer> ports =
			java.util.Collections.synchronizedList(new ArrayList<>());
	private final CountDownLatch release = new CountDownLatch(1);
	private final AtomicInteger entered = new AtomicInteger();

	@After
	public void tearDown() {
		release.countDown();
		for (Socket s : sockets) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
		for (ChannelServer s : servers) s.close();
		exec.shutdownNow();
	}

	@Test(timeout = 60_000)
	public void aListenerWhoseOnionCouldNotBePublishedIsClosed()
			throws Exception {
		OnionPublisher failing = new OnionPublisher() {
			@Override
			public OnionHandle publish(int localPort, String privateKey)
					throws IOException {
				ports.add(localPort);
				throw new IOException("tor is down");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		TorChannelTransport transport = new TorChannelTransport(failing,
				SocketFactory.getDefault(), exec);
		try {
			transport.bindServer(new byte[] {1}, null, request -> request);
			fail("binding succeeded without an onion");
		} catch (IOException expected) {
		}

		Socket s = new Socket();
		try {
			s.connect(new InetSocketAddress("127.0.0.1", ports.get(0)),
					2_000);
			fail("the listener is still open after publishing failed");
		} catch (ConnectException expected) {
		} finally {
			s.close();
		}
	}

	@Test(timeout = 60_000)
	public void theOnionsOfOneChannelShareItsHandlers() throws Exception {
		TorChannelTransport transport = new TorChannelTransport(
				recordingPublisher(), SocketFactory.getDefault(), exec);
		byte[] channel = new byte[] {7};
		for (int i = 0; i < 2; i++) {
			servers.add(transport.bindServer(channel, null, request -> {
				entered.incrementAndGet();
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return new byte[] {7};
			}));
		}
		int perChannel = 6;
		for (int i = 0; i < perChannel; i++) request(open(ports.get(0)));
		waitForEntered(perChannel);

		Socket viaRetiringOnion = open(ports.get(1));
		requestRefused(viaRetiringOnion);
		assertEquals("a second onion of the same channel gave it more "
				+ "handlers", perChannel, entered.get());
	}

	@Test(timeout = 120_000)
	public void oneChannelsSpentBudgetLeavesAnotherServed()
			throws Exception {
		TorChannelTransport transport = withEgressBudget(3L * MIB);
		int a = bindLarge(transport, new byte[] {1});
		int b = bindLarge(transport, new byte[] {2});
		assertEquals(2 * MIB, readFully(open(a, 64 * 1024), 2 * MIB));
		Socket second = open(a, 64 * 1024);
		request(second);
		boolean refusedOrCut;
		try {
			refusedOrCut = readFully(second, 2 * MIB) < 2 * MIB;
		} catch (IOException cut) {
			refusedOrCut = true;
		}
		assertTrue("the channel served past its budget", refusedOrCut);

		assertEquals("the other channel was refused", 2 * MIB,
				readFully(open(b, 64 * 1024), 2 * MIB));
	}

	@Test(timeout = 120_000)
	public void aReaderThatHangsUpIsChargedOnlyWhatWasSent()
			throws Exception {
		TorChannelTransport transport = withEgressBudget(3L * MIB);
		int a = bindLarge(transport, new byte[] {1});
		Socket early = open(a, 16 * 1024);
		request(early);
		early.close();
		Thread.sleep(1_500);

		assertEquals("the reply a reader never took was charged in full",
				2 * MIB, readFully(open(a, 64 * 1024), 2 * MIB));
	}

	private TorChannelTransport withEgressBudget(long bytesPerWindow)
			throws Exception {
		Constructor<TorChannelTransport> c;
		try {
			c = TorChannelTransport.class.getDeclaredConstructor(
					OnionPublisher.class, SocketFactory.class,
					java.util.concurrent.Executor.class, long.class,
					long.class, long.class);
		} catch (NoSuchMethodException e) {
			fail("the egress budget is not kept per channel");
			throw e;
		}
		c.setAccessible(true);
		return c.newInstance(recordingPublisher(), SocketFactory.getDefault(),
				exec, 30_000L, 60_000L, bytesPerWindow);
	}

	private OnionPublisher recordingPublisher() {
		return new OnionPublisher() {
			@Override
			public OnionHandle publish(int localPort, String privateKey) {
				ports.add(localPort);
				return new OnionHandle("onion" + localPort, "key");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
	}

	private int bindLarge(TorChannelTransport transport, byte[] channelId)
			throws IOException {
		int before = ports.size();
		servers.add(transport.bindServer(channelId, null,
				request -> new byte[2 * MIB]));
		return ports.get(before);
	}

	private Socket open(int port, int receiveBuffer) throws IOException {
		Socket s = new Socket();
		s.setReceiveBufferSize(receiveBuffer);
		s.connect(new InetSocketAddress("127.0.0.1", port));
		s.setSoTimeout(30_000);
		sockets.add(s);
		return s;
	}

	private Socket open(int port) throws IOException {
		Socket s = new Socket("127.0.0.1", port);
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

	private static int readFully(Socket s, int expected) throws IOException {
		request(s);
		DataInputStream in = new DataInputStream(s.getInputStream());
		int len = in.readInt();
		if (len == 0) return 0;
		assertEquals(expected, len);
		byte[] buf = new byte[64 * 1024];
		int total = 0;
		while (total < len) {
			int r = in.read(buf, 0, Math.min(buf.length, len - total));
			if (r < 0) break;
			total += r;
		}
		return total;
	}

	private static void requestRefused(Socket s) throws IOException {
		try {
			request(s);
		} catch (IOException refusedBeforeTheRequest) {
			return;
		}
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
