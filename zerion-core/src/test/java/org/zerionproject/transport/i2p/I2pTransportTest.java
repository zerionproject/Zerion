package org.zerionproject.transport.i2p;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class I2pTransportTest {

	private ServerSocket bridge;
	private ExecutorService exec;
	private int bridgePort;

	@Before
	public void setUp() throws IOException {
		bridge = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		bridgePort = bridge.getLocalPort();
		exec = Executors.newCachedThreadPool();
		exec.execute(() -> {
			while (!bridge.isClosed()) {
				Socket s;
				try {
					s = bridge.accept();
				} catch (IOException e) {
					return;
				}
				exec.execute(() -> handleBridge(s));
			}
		});
	}

	@After
	public void tearDown() throws IOException {
		bridge.close();
		exec.shutdownNow();
	}

	/** A fake SAM bridge: replies OK to the control handshake and to a dial,
	 * and after a STREAM CONNECT OK sends one byte so the outgoing handler has
	 * something to read. */
	private void handleBridge(Socket s) {
		try {
			InputStream in = s.getInputStream();
			OutputStream out = s.getOutputStream();
			String line;
			while ((line = readLine(in)) != null) {
				if (line.startsWith("HELLO")) {
					send(out, "HELLO REPLY RESULT=OK VERSION=3.1");
				} else if (line.startsWith("SESSION CREATE")) {
					send(out, "SESSION STATUS RESULT=OK DESTINATION=PRIVKEY");
				} else if (line.startsWith("NAMING LOOKUP")) {
					send(out, "NAMING REPLY RESULT=OK NAME=ME VALUE=PUBDEST");
				} else if (line.startsWith("STREAM FORWARD")) {
					send(out, "STREAM STATUS RESULT=OK");
				} else if (line.startsWith("STREAM CONNECT")) {
					send(out, "STREAM STATUS RESULT=OK");
					out.write(0x42);
					out.flush();
				}
			}
		} catch (IOException ignored) {
		}
	}

	@Test
	public void dialRunsOutgoingHandlerWithI2pId() throws Exception {
		CountDownLatch outgoing = new CountDownLatch(1);
		AtomicInteger gotContact = new AtomicInteger(-1);
		AtomicInteger firstByte = new AtomicInteger(-1);
		TransportId[] seenId = new TransportId[1];
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) throws IOException {
				seenId[0] = transportId;
				gotContact.set(contactId);
				firstByte.set(in.read());
				outgoing.countDown();
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) {
			}
		};
		I2pTransport t = new I2pTransport("127.0.0.1", bridgePort,
				new ExternalI2pRouter("127.0.0.1", bridgePort, 5000), exec,
				handler);
		t.start(null);
		t.dial(7, "PEERDEST", false);
		assertTrue(outgoing.await(10, TimeUnit.SECONDS));
		assertEquals(7, gotContact.get());
		assertEquals(0x42, firstByte.get());
		assertEquals(I2pConstants.ID, seenId[0]);
		t.stop();
	}

	@Test
	public void forwardedInboundRunsIncomingHandler() throws Exception {
		CountDownLatch incoming = new CountDownLatch(1);
		AtomicInteger firstByte = new AtomicInteger(-1);
		TransportId[] seenId = new TransportId[1];
		ZtpConnectionHandler handler = new ZtpConnectionHandler() {
			@Override
			public void handleOutgoing(TransportId transportId, int contactId,
					InputStream in, OutputStream out) {
			}

			@Override
			public void handleIncoming(TransportId transportId, InputStream in,
					OutputStream out) throws IOException {
				seenId[0] = transportId;
				firstByte.set(in.read());
				incoming.countDown();
			}
		};
		I2pTransport t = new I2pTransport("127.0.0.1", bridgePort,
				new ExternalI2pRouter("127.0.0.1", bridgePort, 5000), exec,
				handler);
		t.start(null);
		// Simulate the router forwarding an inbound stream to the local port.
		Socket forwarded = new Socket();
		forwarded.connect(new InetSocketAddress("127.0.0.1",
				t.getLocalPort()), 5000);
		forwarded.getOutputStream().write(0x37);
		forwarded.getOutputStream().flush();
		assertTrue(incoming.await(10, TimeUnit.SECONDS));
		assertEquals(0x37, firstByte.get());
		assertEquals(I2pConstants.ID, seenId[0]);
		forwarded.close();
		t.stop();
	}

	private static void send(OutputStream out, String line) throws IOException {
		out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	private static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') return sb.toString();
			if (b != '\r') sb.append((char) b);
		}
		return sb.length() == 0 ? null : sb.toString();
	}
}
