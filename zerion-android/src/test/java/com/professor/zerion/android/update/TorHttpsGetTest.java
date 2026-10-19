package com.professor.zerion.android.update;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TorHttpsGetTest {

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	@Test
	public void theBodyIsReturnedByteForByte() throws IOException {
		byte[] body = {0, 1, (byte) 0xff, '\r', '\n', 'x'};
		byte[] head = bytes("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\n");
		byte[] all = new byte[head.length + body.length];
		System.arraycopy(head, 0, all, 0, head.length);
		System.arraycopy(body, 0, all, head.length, body.length);
		TorHttpsGet.Response r = TorHttpsGet.parse(all, 100);
		assertEquals(200, r.status);
		assertArrayEquals(body, r.body);
	}

	@Test
	public void aMissingFileReportsItsStatus() throws IOException {
		assertEquals(404, TorHttpsGet.parse(
				bytes("HTTP/1.1 404 Not Found\r\n\r\n404: Not Found"), 100).status);
	}

	@Test
	public void encodedOrOversizedOrMalformedResponsesAreRefused() {
		String[] bad = {
				"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n",
				"HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\nxx",
				"HTTP/1.1 200 OK\r\n\r\n" + new String(new char[101]).replace('\0', 'a'),
				"garbage without headers",
				"SMTP 200 OK\r\n\r\n",
				"HTTP/1.1 abc OK\r\n\r\n",
		};
		for (String b : bad) {
			try {
				TorHttpsGet.parse(bytes(b), 100);
				fail("accepted: " + b.substring(0, Math.min(40, b.length())));
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void readingStopsAtTheLimit() {
		try {
			TorHttpsGet.readAll(new ByteArrayInputStream(new byte[5000]), 4096);
			fail("read past the limit");
		} catch (IOException expected) {
		}
	}

	@Test
	public void withoutADeadlineTheUsualTimeoutsHold() throws IOException {
		assertEquals(TorHttpsGet.READ_TIMEOUT_MS,
				TorHttpsGet.remaining(TorHttpsGet.READ_TIMEOUT_MS,
						TorHttpsGet.NO_DEADLINE, () -> 0L));
		assertEquals(TorHttpsGet.CONNECT_TIMEOUT_MS,
				TorHttpsGet.remaining(TorHttpsGet.CONNECT_TIMEOUT_MS,
						TorHttpsGet.NO_DEADLINE, () -> Long.MAX_VALUE - 1));
	}

	@Test
	public void aDeadlineShortensEveryWaitAndEndsTheRequest()
			throws IOException {
		assertEquals(5000, TorHttpsGet.remaining(60_000, 15_000,
				() -> 10_000L));
		assertEquals(60_000, TorHttpsGet.remaining(60_000, 500_000,
				() -> 10_000L));
		try {
			TorHttpsGet.remaining(60_000, 10_000, () -> 10_000L);
			fail("waited past the deadline");
		} catch (IOException expected) {
		}
	}

	@Test
	public void aSlowAnswerIsCutAtTheDeadline() {
		long[] now = {0};
		List<Integer> timeouts = new ArrayList<>();
		Socket socket = new Socket() {
			@Override
			public void setSoTimeout(int timeout) {
				timeouts.add(timeout);
			}
		};
		InputStream slow = new InputStream() {
			@Override
			public int read() {
				now[0] += 1000;
				return 'x';
			}

			@Override
			public int read(byte[] b, int off, int len) {
				now[0] += 1000;
				b[off] = 'x';
				return 1;
			}
		};
		try {
			TorHttpsGet.readAll(socket, slow, 1_000_000, 10_500,
					() -> now[0]);
			fail("read past the deadline");
		} catch (IOException expected) {
		}
		assertTrue(now[0] <= 11_000);
		assertEquals(10_500, (int) timeouts.get(0));
		assertEquals(500, (int) timeouts.get(timeouts.size() - 1));
	}

	private static long monotonic() {
		return System.nanoTime() / 1_000_000L;
	}

	private static final class StalledSocket extends Socket {
		private final boolean hangInConnect;
		private final java.util.concurrent.CountDownLatch closedLatch =
				new java.util.concurrent.CountDownLatch(1);
		private volatile boolean connected = false;
		private volatile boolean closed = false;
		volatile int bytesServed = 0;

		StalledSocket(boolean hangInConnect) {
			this.hangInConnect = hangInConnect;
		}

		@Override
		public void connect(java.net.SocketAddress endpoint, int timeout)
				throws IOException {
			if (hangInConnect) {
				waitForClose(30_000);
				throw new java.net.SocketException("closed while connecting");
			}
			connected = true;
		}

		private void waitForClose(long ms) {
			try {
				closedLatch.await(ms,
						java.util.concurrent.TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public boolean isConnected() {
			return connected;
		}

		@Override
		public boolean isClosed() {
			return closed;
		}

		@Override
		public void close() {
			closed = true;
			closedLatch.countDown();
		}

		@Override
		public void setSoTimeout(int timeout) {
		}

		@Override
		public int getSoTimeout() {
			return 0;
		}

		@Override
		public java.io.OutputStream getOutputStream() {
			return new java.io.OutputStream() {
				@Override
				public void write(int b) throws IOException {
					if (closed) throw new java.net.SocketException("closed");
				}
			};
		}

		@Override
		public InputStream getInputStream() {
			byte[] header = {0x16, 0x03, 0x03, 0x40, 0x00};
			return new InputStream() {
				@Override
				public int read() throws IOException {
					waitForClose(20);
					if (closed) throw new java.net.SocketException("closed");
					int i = bytesServed++;
					return i < header.length ? header[i] : 0;
				}

				@Override
				public int read(byte[] b, int off, int len)
						throws IOException {
					if (len == 0) return 0;
					b[off] = (byte) read();
					return 1;
				}
			};
		}
	}

	private static final class StalledSockets extends javax.net.SocketFactory {
		final StalledSocket socket;

		StalledSockets(boolean hangInConnect) {
			socket = new StalledSocket(hangInConnect);
		}

		@Override
		public Socket createSocket() {
			return socket;
		}

		@Override
		public Socket createSocket(String host, int port) {
			throw new AssertionError();
		}

		@Override
		public Socket createSocket(String host, int port,
				java.net.InetAddress localHost, int localPort) {
			throw new AssertionError();
		}

		@Override
		public Socket createSocket(java.net.InetAddress host, int port) {
			throw new AssertionError();
		}

		@Override
		public Socket createSocket(java.net.InetAddress address, int port,
				java.net.InetAddress localAddress, int localPort) {
			throw new AssertionError();
		}
	}

	@Test(timeout = 15_000)
	public void anAnswerThatDripsInIsCutAtTheDeadline() {
		StalledSockets sockets = new StalledSockets(false);
		IOException failure = null;
		((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory
				.getDefault()).getDefaultCipherSuites();
		long start = monotonic();
		try {
			TorHttpsGet.get(sockets, "https://bridges.example/bridges.json",
					1000, start + 1500, TorHttpsGetTest::monotonic);
			fail("the dripping answer was waited for");
		} catch (IOException expected) {
			failure = expected;
		}
		long took = monotonic() - start;
		assertTrue("cut after " + took + " ms", took >= 1400 && took < 5000);
		assertTrue(sockets.socket.isClosed());
		assertTrue("bytes kept coming in until the cut: " + failure,
				sockets.socket.bytesServed > 0);
	}

	@Test(timeout = 15_000)
	public void aConnectThatHangsIsCutAtTheDeadline() {
		StalledSockets sockets = new StalledSockets(true);
		long start = monotonic();
		try {
			TorHttpsGet.get(sockets, "https://bridges.example/bridges.json",
					1000, start + 500, TorHttpsGetTest::monotonic);
			fail("the hanging connect was waited for");
		} catch (IOException expected) {
		}
		long took = monotonic() - start;
		assertTrue("cut after " + took + " ms", took < 3000);
		assertTrue(sockets.socket.isClosed());
	}

	@Test
	public void withoutADeadlineNoWatchdogIsArmedAndTheUsualWaitsHold() {
		List<Long> armed = new ArrayList<>();
		TorHttpsGet.Watchdog recording = (atDeadline, delayMs) -> {
			armed.add(delayMs);
			return () -> {
			};
		};
		List<Integer> connectTimeouts = new ArrayList<>();
		javax.net.SocketFactory failing = new javax.net.SocketFactory() {
			@Override
			public Socket createSocket() {
				return new Socket() {
					@Override
					public void connect(java.net.SocketAddress a, int t)
							throws IOException {
						connectTimeouts.add(t);
						throw new IOException("refused");
					}
				};
			}

			@Override
			public Socket createSocket(String h, int p) {
				throw new AssertionError();
			}

			@Override
			public Socket createSocket(String h, int p,
					java.net.InetAddress l, int lp) {
				throw new AssertionError();
			}

			@Override
			public Socket createSocket(java.net.InetAddress h, int p) {
				throw new AssertionError();
			}

			@Override
			public Socket createSocket(java.net.InetAddress a, int p,
					java.net.InetAddress l, int lp) {
				throw new AssertionError();
			}
		};
		try {
			TorHttpsGet.get(failing, "https://bridges.example/x", 10,
					TorHttpsGet.NO_DEADLINE, () -> 0L, recording);
			fail();
		} catch (IOException expected) {
		}
		assertTrue("no watchdog without a deadline", armed.isEmpty());
		assertEquals(Arrays.asList(TorHttpsGet.CONNECT_TIMEOUT_MS),
				connectTimeouts);
		try {
			TorHttpsGet.get(failing, "https://bridges.example/x", 10, 5_000,
					() -> 1_000L, recording);
			fail();
		} catch (IOException expected) {
		}
		assertEquals(Arrays.asList(4_000L), armed);
		assertEquals(4_000, (int) connectTimeouts.get(1));
	}
}
