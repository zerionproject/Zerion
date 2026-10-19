package com.professor.zerion.android.vault.wallet.btc;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ElectrumClientDeadlineTest {

	private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
	private static final long TRICKLE_MS = 50;
	private static final int CONNECT_DEADLINE_MS = 30_000;

	private interface Behaviour {
		void serve(Socket client) throws Exception;
	}

	private static final class Server implements AutoCloseable {
		private final ServerSocket ss;
		private final List<Socket> clients = new ArrayList<>();

		Server(Behaviour behaviour) throws IOException {
			ss = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
			Thread t = new Thread(() -> {
				while (!ss.isClosed()) {
					Socket c;
					try {
						c = ss.accept();
					} catch (IOException closed) {
						return;
					}
					synchronized (clients) {
						clients.add(c);
					}
					Thread h = new Thread(() -> {
						try {
							behaviour.serve(c);
						} catch (Exception ignored) {
						}
					}, "deadline-server-client");
					h.setDaemon(true);
					h.start();
				}
			}, "deadline-server");
			t.setDaemon(true);
			t.start();
		}

		int port() {
			return ss.getLocalPort();
		}

		@Override
		public void close() throws IOException {
			ss.close();
			synchronized (clients) {
				for (Socket c : clients) c.close();
			}
		}
	}

	private final List<AutoCloseable> closeables = new ArrayList<>();

	@After
	public void tearDown() throws Exception {
		for (AutoCloseable c : closeables) c.close();
	}

	private Server server(Behaviour b) throws IOException {
		Server s = new Server(b);
		closeables.add(s);
		return s;
	}

	private static ElectrumEndpoint plain(Server s) {
		return new ElectrumEndpoint("127.0.0.1", s.port(),
				ElectrumEndpoint.Mode.PLAINTEXT, true, null);
	}

	private static ElectrumEndpoint tls(Server s) {
		return new ElectrumEndpoint("127.0.0.1", s.port(),
				ElectrumEndpoint.Mode.TLS, true, null);
	}

	private ElectrumClient connect(ElectrumEndpoint ep, int readTimeoutMs,
			int deadlineMs) throws IOException {
		ElectrumClient c = new ElectrumClient(ep, 0, "w", readTimeoutMs,
				deadlineMs);
		closeables.add(c::close);
		return c;
	}

	private ElectrumClient connectForCalls(ElectrumEndpoint ep,
			int readTimeoutMs, int callDeadlineMs) throws IOException {
		ElectrumClient c = new ElectrumClient(ep, 0, "w", readTimeoutMs,
				CONNECT_DEADLINE_MS, callDeadlineMs);
		closeables.add(c::close);
		return c;
	}

	private static long id(String request) {
		Matcher m = ID.matcher(request);
		return m.find() ? Long.parseLong(m.group(1)) : -1;
	}

	private static void write(OutputStream out, String s) throws IOException {
		out.write(s.getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static void trickle(OutputStream out, byte b) throws Exception {
		while (true) {
			out.write(b);
			out.flush();
			Thread.sleep(TRICKLE_MS);
		}
	}

	private static Behaviour versionThen(Behaviour afterVersion) {
		return client -> {
			BufferedReader in = new BufferedReader(new InputStreamReader(
					client.getInputStream(), StandardCharsets.UTF_8));
			OutputStream out = client.getOutputStream();
			String version = in.readLine();
			write(out, "{\"id\":" + id(version)
					+ ",\"result\":[\"x\",\"1.4\"]}\n");
			afterVersion.serve(client);
		};
	}

	private static Behaviour answersEveryCall() {
		return versionThen(client -> {
			BufferedReader in = new BufferedReader(new InputStreamReader(
					client.getInputStream(), StandardCharsets.UTF_8));
			OutputStream out = client.getOutputStream();
			String line;
			while ((line = in.readLine()) != null) {
				write(out, "{\"id\":" + id(line)
						+ ",\"result\":{\"height\":777}}\n");
			}
		});
	}

	private static void assertTimedOut(IOException e, long startNanos,
			long withinMs) {
		assertTrue(e.getClass().getName(), e instanceof SocketTimeoutException);
		assertEquals(ElectrumClient.NO_ANSWER_IN_TIME, e.getMessage());
		long tookMs = TimeUnit.NANOSECONDS.toMillis(
				System.nanoTime() - startNanos);
		assertTrue("took " + tookMs + " ms", tookMs < withinMs);
	}

	@Test(timeout = 30_000)
	public void aReplyThatTricklesForeverFailsAtTheDeadline()
			throws Exception {
		Server s = server(versionThen(client -> {
			BufferedReader in = new BufferedReader(new InputStreamReader(
					client.getInputStream(), StandardCharsets.UTF_8));
			OutputStream out = client.getOutputStream();
			String request = in.readLine();
			write(out, "{\"id\":" + id(request) + ",\"result\":{\"height\":");
			trickle(out, (byte) '1');
		}));
		ElectrumClient c = connectForCalls(plain(s), 5_000, 1_000);
		long start = System.nanoTime();
		try {
			c.blockHeight();
			fail();
		} catch (IOException e) {
			assertTimedOut(e, start, 10_000);
		}
		try {
			c.blockHeight();
			fail("a client cut off mid reply must not be reused");
		} catch (IOException expected) {
		}
	}

	@Test(timeout = 30_000)
	public void aReadCutShortByTheDeadlineIsTheDeadlineEvenBeforeTheCloseFires()
			throws Exception {
		Server s = server(versionThen(client -> {
			BufferedReader in = new BufferedReader(new InputStreamReader(
					client.getInputStream(), StandardCharsets.UTF_8));
			OutputStream out = client.getOutputStream();
			String request = in.readLine();
			write(out, "{\"id\":" + id(request) + ",\"result\":{\"height\":");
			while (in.readLine() != null) {
			}
		}));
		ElectrumClient c = connectForCalls(plain(s), 5_000, 1_000);
		java.lang.reflect.Field f =
				ElectrumClient.class.getDeclaredField("DEADLINES");
		f.setAccessible(true);
		CountDownLatch closesHeld = new CountDownLatch(1);
		((ScheduledExecutorService) f.get(null)).execute(() -> {
			try {
				closesHeld.await(20, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			long start = System.nanoTime();
			try {
				c.blockHeight();
				fail();
			} catch (IOException e) {
				assertTimedOut(e, start, 10_000);
			}
			try {
				c.blockHeight();
				fail("a client cut off mid reply must not be reused");
			} catch (IOException expected) {
				assertFalse("the cut off client was used again",
						expected instanceof SocketTimeoutException);
			}
		} finally {
			closesHeld.countDown();
		}
	}

	@Test
	public void aWaitBoundByTheDeadlineNeverEndsBeforeIt() throws Exception {
		Class<?> type = Class.forName(ElectrumClient.class.getName()
				+ "$Deadline");
		java.lang.reflect.Constructor<?> create =
				type.getDeclaredConstructor(int.class);
		create.setAccessible(true);
		java.lang.reflect.Method remaining =
				type.getDeclaredMethod("remainingMs");
		remaining.setAccessible(true);
		java.lang.reflect.Field end = type.getDeclaredField("endNanos");
		end.setAccessible(true);
		for (int i = 0; i < 1_000; i++) {
			Object deadline = create.newInstance(1_000);
			int ms = (Integer) remaining.invoke(deadline);
			long waitEnds = System.nanoTime() + ms * 1_000_000L;
			assertTrue("a wait of " + ms + " ms ends before the deadline",
					waitEnds >= (Long) end.get(deadline));
		}
	}

	@Test(timeout = 30_000)
	public void aServerThatNeverAnswersFailsTheConnectAtTheDeadline()
			throws Exception {
		Server s = server(client -> {
			InputStream in = client.getInputStream();
			while (in.read() != -1) {
			}
		});
		long start = System.nanoTime();
		try {
			connect(plain(s), 30_000, 500);
			fail();
		} catch (IOException e) {
			assertTimedOut(e, start, 5_000);
		}
	}

	@Test(timeout = 30_000)
	public void aVersionReplyThatTricklesFailsTheConnectAtTheDeadline()
			throws Exception {
		Server s = server(client -> {
			BufferedReader in = new BufferedReader(new InputStreamReader(
					client.getInputStream(), StandardCharsets.UTF_8));
			OutputStream out = client.getOutputStream();
			String version = in.readLine();
			write(out, "{\"id\":" + id(version) + ",\"result\":[\"");
			trickle(out, (byte) 'x');
		});
		long start = System.nanoTime();
		try {
			connect(plain(s), 5_000, 1_000);
			fail();
		} catch (IOException e) {
			assertTimedOut(e, start, 10_000);
		}
	}

	private static Behaviour tricklingTlsHandshake() {
		return client -> {
			InputStream in = client.getInputStream();
			byte[] hello = new byte[5];
			int got = 0;
			while (got < hello.length) {
				int r = in.read(hello, got, hello.length - got);
				if (r < 0) return;
				got += r;
			}
			OutputStream out = client.getOutputStream();
			out.write(new byte[] {0x16, 0x03, 0x03, 0x40, 0x00});
			out.flush();
			trickle(out, (byte) 0);
		};
	}

	@Test(timeout = 30_000)
	public void aTlsHandshakeThatTricklesFailsAtTheDeadline()
			throws Exception {
		Server s = server(tricklingTlsHandshake());
		long start = System.nanoTime();
		try {
			connect(tls(s), 5_000, 1_000);
			fail();
		} catch (IOException e) {
			assertTimedOut(e, start, 10_000);
		}
	}

	@Test(timeout = 30_000)
	public void readingACertificateIsBoundedByTheDeadline() throws Exception {
		Server s = server(tricklingTlsHandshake());
		long start = System.nanoTime();
		try {
			ElectrumClient.captureCert(tls(s), 0, 1_000);
			fail();
		} catch (IOException e) {
			assertTimedOut(e, start, 10_000);
		}
	}

	@Test(timeout = 30_000)
	public void everyCallGetsItsOwnDeadline() throws Exception {
		Server s = server(answersEveryCall());
		ElectrumClient c = connectForCalls(plain(s), 5_000, 2_000);
		for (int i = 0; i < 3; i++) {
			assertEquals(777, c.blockHeight());
			Thread.sleep(2_500);
		}
		assertEquals(777, c.blockHeight());
	}
}
