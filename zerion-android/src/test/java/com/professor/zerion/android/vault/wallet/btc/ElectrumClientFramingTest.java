package com.professor.zerion.android.vault.wallet.btc;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;
import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The Electrum line protocol as the client reads it from a hostile or merely
 * awkward server: replies split across writes are reassembled, lines with
 * another id or no id are skipped until the reply arrives, an overlong line
 * is refused at its bound instead of buffered, a reply cut off by a close is
 * a connection error, an error object is a server rejection, and a
 * transaction whose id does not match the one requested is refused.
 */
public class ElectrumClientFramingTest {

	private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");
	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";

	/** Answers each request line with the chunks a script hands back. */
	private interface Script {
		List<byte[]> reply(long id, String request);
	}

	private final class FakeServer implements AutoCloseable {
		private final ServerSocket ss;
		private final Thread thread;
		private volatile Socket client;

		FakeServer(Script script) throws IOException {
			ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
			thread = new Thread(() -> {
				try {
					client = ss.accept();
					BufferedReader in = new BufferedReader(new InputStreamReader(
							client.getInputStream(), StandardCharsets.UTF_8));
					OutputStream out = client.getOutputStream();
					String line;
					while ((line = in.readLine()) != null) {
						Matcher m = ID.matcher(line);
						long id = m.find() ? Long.parseLong(m.group(1)) : -1;
						List<byte[]> chunks = script.reply(id, line);
						if (chunks == null) {
							client.close();
							return;
						}
						for (byte[] c : chunks) {
							out.write(c);
							out.flush();
							Thread.sleep(5);
						}
					}
				} catch (IOException | InterruptedException ignored) {
				}
			}, "fake-electrum");
			thread.setDaemon(true);
			thread.start();
		}

		int port() {
			return ss.getLocalPort();
		}

		@Override
		public void close() throws IOException {
			ss.close();
			Socket c = client;
			if (c != null) c.close();
		}
	}

	private final List<AutoCloseable> closeables = new ArrayList<>();

	@After
	public void tearDown() throws Exception {
		for (AutoCloseable c : closeables) c.close();
	}

	@Test(timeout = 30_000)
	public void repliesSplitAcrossWritesAndInterleavedWithOtherLinesAreRead()
			throws Exception {
		FakeServer server = server((id, request) -> {
			if (request.contains("server.version")) {
				return split("{\"id\":" + id + ",\"result\":[\"x\",\"1.4\"]}\n");
			}
			if (request.contains("headers.subscribe")) {
				List<byte[]> chunks = new ArrayList<>();
				chunks.add(bytes("{\"id\":9999,\"result\":{\"height\":1}}\n"));
				chunks.add(bytes("{\"method\":\"blockchain.headers.subscribe\","
						+ "\"params\":[{\"height\":2}]}\n"));
				chunks.add(bytes("garbage without an id\n"));
				chunks.addAll(split("{\"id\":" + id
						+ ",\"result\":{\"height\":123456}}\n"));
				return chunks;
			}
			return null;
		});
		ElectrumClient client = connect(server);
		assertEquals(123456, client.blockHeight());
	}

	@Test(timeout = 60_000)
	public void anOverlongLineIsRefusedAtTheBound() throws Exception {
		byte[] filler = new byte[1 << 20];
		Arrays.fill(filler, (byte) 'x');
		FakeServer server = server((id, request) -> {
			if (request.contains("server.version")) {
				return split("{\"id\":" + id + ",\"result\":[\"x\",\"1.4\"]}\n");
			}
			List<byte[]> chunks = new ArrayList<>();
			chunks.add(bytes("{\"id\":" + id + ",\"result\":\""));
			for (int i = 0; i < 9; i++) chunks.add(filler);
			chunks.add(bytes("\"}\n"));
			return chunks;
		});
		ElectrumClient client = connect(server);
		try {
			client.blockHeight();
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage()
					.contains("too large"));
		}
	}

	@Test(timeout = 30_000)
	public void aReplyCutOffByACloseIsAConnectionError() throws Exception {
		FakeServer server = server((id, request) -> {
			if (request.contains("server.version")) {
				return split("{\"id\":" + id + ",\"result\":[\"x\",\"1.4\"]}\n");
			}
			return null;
		});
		ElectrumClient client = connect(server);
		try {
			client.blockHeight();
			fail();
		} catch (IOException expected) {
		}
	}

	@Test(timeout = 30_000)
	public void anErrorObjectIsAServerRejection() throws Exception {
		FakeServer server = server((id, request) -> {
			if (request.contains("server.version")) {
				return split("{\"id\":" + id + ",\"result\":[\"x\",\"1.4\"]}\n");
			}
			return split("{\"id\":" + id + ",\"error\":{\"code\":1,"
					+ "\"message\":\"nope\"}}\n");
		});
		ElectrumClient client = connect(server);
		try {
			client.broadcast("00");
			fail();
		} catch (ElectrumClient.ServerRejectedException expected) {
			assertEquals("nope", expected.getMessage());
		}
	}

	@Test(timeout = 30_000)
	public void aTransactionWhoseIdDoesNotMatchIsRefused() throws Exception {
		String hexA = signed(0);
		String hexB = signed(1);
		String txidA = txid(hexA);
		String txidB = txid(hexB);
		FakeServer server = server((id, request) -> {
			if (request.contains("server.version")) {
				return split("{\"id\":" + id + ",\"result\":[\"x\",\"1.4\"]}\n");
			}
			return split("{\"id\":" + id + ",\"result\":\"" + hexB + "\"}\n");
		});
		ElectrumClient client = connect(server);
		try {
			client.getTransaction(txidA);
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage()
					.contains("mismatch"));
		}
		assertEquals(hexB, client.getTransaction(txidB));
	}

	@Test(timeout = 30_000)
	public void aMatchingTransactionIsReturned() throws Exception {
		String hex = signed(2);
		String id = txid(hex);
		FakeServer server = server((reqId, request) -> {
			if (request.contains("server.version")) {
				return split("{\"id\":" + reqId + ",\"result\":[\"x\",\"1.4\"]}\n");
			}
			return split("{\"id\":" + reqId + ",\"result\":\"" + hex + "\"}\n");
		});
		assertEquals(hex, connect(server).getTransaction(id));
	}

	private FakeServer server(Script script) throws IOException {
		FakeServer s = new FakeServer(script);
		closeables.add(s);
		return s;
	}

	private ElectrumClient connect(FakeServer server) throws IOException {
		ElectrumEndpoint ep = new ElectrumEndpoint("127.0.0.1", server.port(),
				ElectrumEndpoint.Mode.PLAINTEXT, true, null);
		ElectrumClient client = new ElectrumClient(ep, 0, "w");
		closeables.add(client::close);
		return client;
	}

	private static List<byte[]> split(String line) {
		byte[] all = bytes(line);
		int cut = all.length / 2;
		return Arrays.asList(Arrays.copyOfRange(all, 0, 3),
				Arrays.copyOfRange(all, 3, cut),
				Arrays.copyOfRange(all, cut, all.length));
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static String signed(int index) {
		List<BtcTx.Input> inputs = Collections.singletonList(new BtcTx.Input(
				"000000000000000000000000000000000000000000000000000000000000000"
						+ (index + 1), 0, 100_000L,
				TestKeys.receiveKey(MNEMONIC, 0, index)));
		List<BtcTx.Output> outputs = Collections.singletonList(
				new BtcTx.Output(TestKeys.address(MNEMONIC, 0, index + 3),
						90_000L));
		return BtcTx.buildAndSign(inputs, outputs);
	}

	private static String txid(String hex) {
		return new Transaction(BtcKeys.PARAMS, Utils.HEX.decode(hex))
				.getTxId().toString();
	}
}
