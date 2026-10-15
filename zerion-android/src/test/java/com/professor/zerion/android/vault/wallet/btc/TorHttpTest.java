package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.net.TorSockets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TorHttpTest {

	private static final int PORT = 9050;
	private static final String TAG = "walletA";

	private ServerSocket server;
	private volatile byte[] response = new byte[0];
	private volatile long dripDelayMs = 0;
	private volatile boolean holdOpen = false;
	private volatile boolean stallHandshake = false;
	private final List<String> targets = new CopyOnWriteArrayList<>();
	private final List<String> credentials = new CopyOnWriteArrayList<>();
	private final List<String> requestLines = new CopyOnWriteArrayList<>();

	@Before
	public void setUp() throws IOException {
		server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		InetSocketAddress proxy = new InetSocketAddress(
				InetAddress.getLoopbackAddress(), server.getLocalPort());
		TorSockets.install(timeoutMs -> {
			Socket s = new Socket();
			s.connect(proxy, timeoutMs);
			return s;
		});
		Thread t = new Thread(this::serve);
		t.setDaemon(true);
		t.start();
	}

	@After
	public void tearDown() throws IOException {
		TorSockets.install(timeoutMs -> {
			throw new IOException("Tor proxy not available");
		});
		server.close();
	}

	private void serve() {
		while (!server.isClosed()) {
			try (Socket s = server.accept()) {
				handle(s);
			} catch (IOException e) {
				if (server.isClosed()) return;
			}
		}
	}

	private void handle(Socket s) throws IOException {
		s.setSoTimeout(10_000);
		DataInputStream in = new DataInputStream(s.getInputStream());
		OutputStream out = s.getOutputStream();
		in.readFully(new byte[3]);
		out.write(new byte[] {5, 2});
		in.readUnsignedByte();
		String user = new String(read(in, in.readUnsignedByte()),
				StandardCharsets.US_ASCII);
		String pass = new String(read(in, in.readUnsignedByte()),
				StandardCharsets.US_ASCII);
		credentials.add(user + "/" + pass);
		out.write(new byte[] {1, 0});
		in.readFully(new byte[4]);
		String host = new String(read(in, in.readUnsignedByte()),
				StandardCharsets.US_ASCII);
		targets.add(host + ":" + in.readUnsignedShort());
		out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
		if (stallHandshake) {
			drip(out, new byte[] {0x16, 0x03, 0x03, 0x40, 0x00});
			drip(out, new byte[0x4000]);
			return;
		}
		ByteArrayOutputStream request = new ByteArrayOutputStream();
		while (!request.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
			request.write(in.readUnsignedByte());
		}
		requestLines.add(request.toString("ISO-8859-1").split("\r\n")[0]);
		byte[] r = response;
		if (dripDelayMs > 0) {
			for (byte b : r) {
				out.write(b);
				out.flush();
				try {
					Thread.sleep(dripDelayMs);
				} catch (InterruptedException e) {
					return;
				}
			}
		} else {
			out.write(r);
			out.flush();
		}
		if (holdOpen) {
			while (in.read() >= 0) {
			}
		}
	}

	private void drip(OutputStream out, byte[] bytes) throws IOException {
		for (byte b : bytes) {
			out.write(b);
			out.flush();
			try {
				Thread.sleep(dripDelayMs);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private static byte[] read(DataInputStream in, int n) throws IOException {
		byte[] b = new byte[n];
		in.readFully(b);
		return b;
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	private static byte[] filled(int n) {
		byte[] b = new byte[n];
		Arrays.fill(b, (byte) 'a');
		return b;
	}

	private String fetch(String rawResponse) {
		response = bytes(rawResponse);
		return TorHttp.get("http://oracle.example.org/x?y=1", PORT, TAG);
	}

	@Test
	public void aValidResponseReturnsItsBodyOverTheIsolatedTorCircuit() {
		assertEquals("{\"a\":1}", fetch("HTTP/1.1 200 OK\r\n"
				+ "Content-Type: application/json\r\nContent-Length: 7\r\n"
				+ "\r\n{\"a\":1}"));
		assertEquals(Collections.singletonList("oracle.example.org:80"),
				targets);
		assertEquals(Collections.singletonList("zw-" + TAG + "/" + TAG),
				credentials);
		assertEquals(Collections.singletonList("GET /x?y=1 HTTP/1.0"),
				requestLines);
	}

	@Test
	public void aBodyWithoutContentLengthIsReadToTheEnd() {
		assertEquals("[1,2]", fetch("HTTP/1.0 200 OK\r\n\r\n[1,2]"));
		assertEquals("[1,2]", fetch("HTTP/1.1 200\r\n\r\n[1,2]"));
	}

	@Test
	public void aStatusOtherThanExactly200IsRefused() {
		assertNull(fetch("HTTP/1.1 2000 OK\r\n\r\n{}"));
		assertNull(fetch("HTTP/1.1 404 Not Found\r\n\r\n{}"));
		assertNull(fetch("HTTP/1.1 201 Created\r\n\r\n{}"));
		assertNull(fetch("HTTP/1.1 500 OK 200\r\n\r\n{}"));
		assertNull(fetch("HTTP/2 200 OK\r\n\r\n{}"));
		assertNull(fetch("HTTP/1.1 20 OK\r\n\r\n{}"));
		assertNull(fetch("XHTTP/1.1 200 OK\r\n\r\n{}"));
		assertNull(fetch("garbage 200\r\n\r\n{}"));
	}

	@Test
	public void aResponseWithoutTheHeaderSeparatorIsRefused() {
		assertNull(fetch("HTTP/1.1 200 OK\r\nContent-Type: x\r\n{\"a\":1}"));
		assertNull(fetch("HTTP/1.1 200 OK"));
		assertNull(fetch("{\"a\":1}"));
	}

	@Test
	public void anOversizedBodyIsRefusedInsteadOfTruncated() {
		response = concat(bytes("HTTP/1.1 200 OK\r\n\r\n"),
				filled(TorHttp.MAX_BODY + 1));
		assertNull(TorHttp.get("http://oracle.example.org/x", PORT, TAG));
		response = bytes("HTTP/1.1 200 OK\r\nContent-Length: "
				+ (TorHttp.MAX_BODY + 1) + "\r\n\r\nabc");
		assertNull(TorHttp.get("http://oracle.example.org/x", PORT, TAG));
	}

	@Test
	public void aBodyShorterThanItsContentLengthIsRefused() {
		assertNull(fetch("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n"
				+ "{\"a\":1}"));
	}

	@Test
	public void dataAfterTheContentLengthIsRefused() {
		assertNull(fetch("HTTP/1.1 200 OK\r\nContent-Length: 4"
				+ "\r\n\r\n{\"a\":1}"));
		assertNull(fetch("HTTP/1.1 200 OK\r\nContent-Length: 7"
				+ "\r\n\r\n{\"a\":1}HTTP/1.1 200 OK\r\n\r\n{}"));
		assertEquals("{\"a\":1}", fetch("HTTP/1.1 200 OK\r\n"
				+ "Content-Length: 7\r\n\r\n{\"a\":1}"));
	}

	@Test
	public void aBodyThatIsNotUtf8IsRefused() {
		response = concat(bytes("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n"),
				new byte[] {'[', (byte) 0xC3, (byte) 0x28, ']'});
		assertNull(TorHttp.get("http://oracle.example.org/x", PORT, TAG));
		response = concat(bytes("HTTP/1.1 200 OK\r\n\r\n"),
				new byte[] {'"', (byte) 0xF0, (byte) 0x9F, '"'});
		assertNull(TorHttp.get("http://oracle.example.org/x", PORT, TAG));
		response = concat(bytes("HTTP/1.1 200 OK\r\n\r\n"),
				"[\"\u00e9\u20ac\"]".getBytes(StandardCharsets.UTF_8));
		assertEquals("[\"\u00e9\u20ac\"]",
				TorHttp.get("http://oracle.example.org/x", PORT, TAG));
	}

	@Test(timeout = 20_000)
	public void aServerThatStallsHalfwayFailsClosedWhenTheReadTimesOut() {
		holdOpen = true;
		response = bytes("HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n"
				+ "{\"a\":");
		long start = System.nanoTime();
		assertNull(TorHttp.get("http://oracle.example.org/x", PORT, TAG, 300,
				10_000));
		assertTrue((System.nanoTime() - start) / 1_000_000L < 5_000);
		response = bytes("HTTP/1.1 200 OK\r\n\r\n{\"a\":");
		assertNull(TorHttp.get("http://oracle.example.org/x", PORT, TAG, 300,
				10_000));
		response = bytes("HTTP/1.1 200 OK\r\nContent-Le");
		assertNull(TorHttp.get("http://oracle.example.org/x", PORT, TAG, 300,
				10_000));
		response = bytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}");
		assertNull("a server that does not close after the body is refused",
				TorHttp.get("http://oracle.example.org/x", PORT, TAG, 300,
						10_000));
	}

	@Test(timeout = 20_000)
	public void aSlowDripPastTheResponseDeadlineFailsClosed() {
		dripDelayMs = 40;
		response = bytes("HTTP/1.1 200 OK\r\nContent-Length: 40\r\n\r\n"
				+ "[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16]");
		long start = System.nanoTime();
		assertNull(TorHttp.get("http://oracle.example.org/x", PORT, TAG, 1000,
				600));
		long took = (System.nanoTime() - start) / 1_000_000L;
		assertTrue("took " + took + " ms", took < 3_000);
		dripDelayMs = 1;
		response = bytes("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n[]");
		assertEquals("[]", TorHttp.get("http://oracle.example.org/x", PORT,
				TAG, 1000, 10_000));
	}

	@Test(timeout = 20_000)
	public void aTlsHandshakeThatDripsPastTheDeadlineFailsClosed() {
		stallHandshake = true;
		dripDelayMs = 50;
		long start = System.nanoTime();
		assertNull(TorHttp.get("https://oracle.example.org/x", PORT, TAG,
				1000, 1500));
		long took = (System.nanoTime() - start) / 1_000_000L;
		assertTrue("took " + took + " ms", took < 6_000);
		assertEquals(Collections.singletonList("oracle.example.org:443"),
				targets);
	}

	@Test
	public void aChunkedOrEncodedResponseIsRefused() {
		assertNull(fetch("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n"
				+ "\r\n7\r\n{\"a\":1}\r\n0\r\n\r\n"));
		assertNull(fetch("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n"
				+ "\r\n{\"a\":1}"));
	}

	@Test
	public void aUrlWithoutAnExplicitHttpOrHttpsSchemeIsNotFetched() {
		response = bytes("HTTP/1.1 200 OK\r\n\r\n{}");
		assertNull(TorHttp.get("oracle.example.org/x", PORT, TAG));
		assertNull(TorHttp.get("//oracle.example.org/x", PORT, TAG));
		assertNull(TorHttp.get("ftp://oracle.example.org/x", PORT, TAG));
		assertNull(TorHttp.get("oracle.example.org:80/x", PORT, TAG));
		assertTrue("nothing was sent to Tor", targets.isEmpty());
	}

	@Test
	public void hostAndPortVariantsAreParsedStrictly() {
		TorHttp.Target t = TorHttp.parseUrl(
				"https://api.example.org:8443/0/public/Ticker?pair=XMRUSD");
		assertTrue(t.https);
		assertEquals("api.example.org", t.host);
		assertEquals(8443, t.port);
		assertEquals("/0/public/Ticker?pair=XMRUSD", t.path);
		TorHttp.Target plain = TorHttp.parseUrl("HTTP://abc.onion");
		assertEquals(80, plain.port);
		assertEquals("/", plain.path);
		assertEquals(443, TorHttp.parseUrl("https://h.example").port);
		assertNull(TorHttp.parseUrl("https://h.example:0/"));
		assertNull(TorHttp.parseUrl("https://h.example:65536/"));
		assertNull(TorHttp.parseUrl("https://h.example:x/"));
		assertNull(TorHttp.parseUrl("https://[::1]:8443/"));
		assertNull(TorHttp.parseUrl("https://user@h.example/"));
		assertNull(TorHttp.parseUrl("https:///x"));
		assertNull(TorHttp.parseUrl("https://h.example/a b"));
	}

	@Test
	public void theParserRefusesMalformedHeaders() {
		String[] bad = {
				"HTTP/1.1 200 OK\r\nno colon here\r\n\r\n{}",
				"HTTP/1.1 200 OK\r\nContent-Length : 2\r\n\r\n{}",
				"HTTP/1.1 200 OK\r\n folded: x\r\n\r\n{}",
				"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 3"
						+ "\r\n\r\n{}x",
				"HTTP/1.1 200 OK\r\nContent-Length: -2\r\n\r\n{}",
				"HTTP/1.1 200 OK\r\nContent-Length: 2, 2\r\n\r\n{}",
				"HTTP/1.1 200 OK\r\nTransfer-Encoding: identity\r\n\r\n{}",
		};
		for (String b : bad) {
			assertThrows(b, IOException.class, () -> TorHttp.readResponse(
					new ByteArrayInputStream(bytes(b)), 100));
		}
	}

	@Test
	public void theParserEnforcesTheBodyLimitExactly() throws IOException {
		assertEquals(100, TorHttp.readResponse(new ByteArrayInputStream(
				concat(bytes("HTTP/1.1 200 OK\r\n\r\n"), filled(100))), 100)
				.length());
		assertThrows(IOException.class, () -> TorHttp.readResponse(
				new ByteArrayInputStream(concat(bytes("HTTP/1.1 200 OK\r\n\r\n"),
						filled(101))), 100));
		assertThrows(IOException.class, () -> TorHttp.readResponse(
				new ByteArrayInputStream(bytes(
						"HTTP/1.1 200 OK\r\nContent-Length: 101\r\n\r\n")), 100));
		assertThrows(IOException.class, () -> TorHttp.readResponse(
				new ByteArrayInputStream(concat(bytes("HTTP/1.1 200 OK\r\nX: "),
						filled(TorHttp.MAX_HEADER))), 100));
	}
}
