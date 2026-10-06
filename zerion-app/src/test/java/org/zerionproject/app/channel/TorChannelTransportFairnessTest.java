package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelTransport.ChannelServer;
import org.junit.After;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.SocketFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TorChannelTransportFairnessTest {

	private static final int LARGE = 16 * 1024 * 1024;

	private final ExecutorService exec = Executors.newCachedThreadPool();
	private final List<Socket> sockets = new ArrayList<>();
	private final List<ChannelServer> servers = new ArrayList<>();

	@After
	public void tearDown() {
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
	public void idleConnectionsToOneChannelLeaveHandlersForAnother()
			throws Exception {
		TorChannelTransport transport = transport(30_000L, 30_000L);
		int attacked = bind(transport, new byte[] {1}, new byte[] {7});
		int other = bind(transport, new byte[] {2}, new byte[] {9});
		for (int i = 0; i < 16; i++) open(attacked, 64 * 1024);
		Thread.sleep(500);

		Socket s = open(other, 64 * 1024);
		request(s, (byte) 2);
		DataInputStream in = new DataInputStream(s.getInputStream());
		try {
			assertEquals(1, in.readInt());
			assertEquals(9, in.readByte());
		} catch (IOException e) {
			fail("the other channel had no handler left: " + e);
		}
	}

	@Test(timeout = 120_000)
	public void slowReadersOfOneChannelLeaveRoomForAnothersLargeResponse()
			throws Exception {
		TorChannelTransport transport = transport(30_000L, 60_000L);
		int attacked = bind(transport, new byte[] {1}, null);
		int other = bind(transport, new byte[] {2}, null);
		for (int i = 0; i < 2; i++) request(open(attacked, 4096), (byte) 1);
		Thread.sleep(1_500);

		Socket s = open(other, 1024 * 1024);
		request(s, (byte) 1);
		DataInputStream in = new DataInputStream(s.getInputStream());
		assertEquals("the other channel's large response was refused",
				LARGE, in.readInt());
	}

	@Test(timeout = 120_000)
	public void aTrickledRequestLosesItsHandlerOnceItFallsBehind()
			throws Exception {
		TorChannelTransport transport = transport(30_000L, 30_000L);
		int port = bind(transport, new byte[] {1}, new byte[] {7});
		Socket s = open(port, 64 * 1024);
		DataOutputStream out = new DataOutputStream(s.getOutputStream());
		out.writeInt(64 * 1024);
		out.flush();
		long start = System.currentTimeMillis();
		boolean closed = false;
		while (System.currentTimeMillis() - start < 40_000L) {
			try {
				out.write(1);
				out.flush();
			} catch (IOException e) {
				closed = true;
				break;
			}
			Thread.sleep(500);
		}
		long held = System.currentTimeMillis() - start;
		assertTrue("a trickled request held its handler for " + held
				+ " ms", closed && held < 35_000L);
	}

	private TorChannelTransport transport(long headerMs, long stallMs) {
		OnionPublisher publisher = new OnionPublisher() {
			private int n;

			@Override
			public OnionHandle publish(int localPort, String privateKey) {
				n++;
				return new OnionHandle("onion" + n + ":" + localPort, "key");
			}

			@Override
			public void unpublish(String onion) {
			}
		};
		return new TorChannelTransport(publisher, SocketFactory.getDefault(),
				exec, headerMs, stallMs);
	}

	private int bind(TorChannelTransport transport, byte[] channelId,
			byte[] reply) throws IOException {
		ChannelServer server = transport.bindServer(channelId, null,
				request -> {
					if (reply != null) return reply;
					return request.length > 0 && request[0] == 1
							? new byte[LARGE] : new byte[] {7};
				});
		servers.add(server);
		String onion = server.getOnionAddress();
		return Integer.parseInt(onion.substring(onion.indexOf(':') + 1));
	}

	private Socket open(int port, int receiveBuffer) throws IOException {
		Socket s = new Socket();
		s.setReceiveBufferSize(receiveBuffer);
		s.connect(new InetSocketAddress("127.0.0.1", port));
		s.setSoTimeout(30_000);
		sockets.add(s);
		return s;
	}

	private static void request(Socket s, byte kind) throws IOException {
		DataOutputStream out = new DataOutputStream(s.getOutputStream());
		out.writeInt(3);
		out.write(new byte[] {kind, 2, 3});
		out.flush();
	}
}
