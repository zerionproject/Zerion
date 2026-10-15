package com.professor.zerion.android.vault.wallet.btc;

import org.junit.After;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WalletHttpFuzzTest {

	private static final long SEED = 20261010L;
	private static final int MUTATIONS = 3000;
	private static final int JSON_DOCUMENTS = 400;
	private static final String REFUSED = "refused";
	private static final String ORACLE = "https://oracle.example.org";
	private static final BigInteger N = new BigInteger(
			"FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
	private static final String REAL_TXID =
			"5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e5e";
	private static final String DECOY_TXID =
			"0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d";

	private enum Kind { TIP, TWEAKS, UTXOS, PRICES }

	private static final byte[] SCAN_PRIV;
	private static final byte[] SPEND_PUB;
	private static final String TWEAKS_BODY;
	private static final String UTXOS_BODY;
	private static final String TIP_BODY = "{\"block_height\":970750}";
	private static final String PRICES_BODY = "{\"time\":1791635705,\"USD\":82787,"
			+ "\"EUR\":73960,\"GBP\":62666,\"CAD\":118189,\"CHF\":68805,"
			+ "\"AUD\":118713,\"JPY\":13123418}";

	static {
		Random r = new Random(SEED);
		byte[] inputPriv = scalar(r);
		byte[] outpoint = new byte[36];
		r.nextBytes(outpoint);
		SCAN_PRIV = scalar(r);
		byte[] spendPriv = scalar(r);
		SPEND_PUB = SilentPayment.pubFromPriv(spendPriv);
		byte[] output = SilentPayment.deriveOutputs(
				Collections.singletonList(inputPriv),
				Collections.singletonList(outpoint),
				SilentPayment.pubFromPriv(SCAN_PRIV), SPEND_PUB, 1).get(0);
		byte[] tweak = SilentPayment.tweakPoint(
				SilentPayment.pubFromPriv(inputPriv),
				Collections.singletonList(outpoint));
		byte[] decoyTweak = SilentPayment.pubFromPriv(scalar(r));
		byte[] decoyOutput = new byte[32];
		r.nextBytes(decoyOutput);
		TWEAKS_BODY = "[\"" + BtcKeys.toHex(decoyTweak) + "\",\""
				+ BtcKeys.toHex(tweak) + "\"]";
		UTXOS_BODY = "[" + utxo(DECOY_TXID, 0, 1000, decoyOutput) + ","
				+ utxo(REAL_TXID, 1, 54321, output) + "]";
	}

	private static byte[] scalar(Random r) {
		while (true) {
			byte[] b = new byte[32];
			r.nextBytes(b);
			BigInteger k = new BigInteger(1, b);
			if (k.signum() > 0 && k.compareTo(N) < 0) return b;
		}
	}

	private static String utxo(String txid, int vout, long value, byte[] xonly) {
		return "{\"txid\":\"" + txid + "\",\"vout\":" + vout + ",\"value\":"
				+ value + ",\"scriptpubkey\":\"5120" + BtcKeys.toHex(xonly)
				+ "\",\"block_height\":800000,\"spent\":false}";
	}

	private static String bodyOf(Kind kind) {
		switch (kind) {
			case TIP:
				return TIP_BODY;
			case TWEAKS:
				return TWEAKS_BODY;
			case UTXOS:
				return UTXOS_BODY;
			default:
				return PRICES_BODY;
		}
	}

	private static String consume(Kind kind, String body) {
		try {
			switch (kind) {
				case TIP: {
					Integer h = SilentPaymentScanner.tipHeight(ORACLE, "w",
							(url, tag) -> body);
					if (h == null) return REFUSED;
					assertTrue(h >= 0 && h <= SilentPaymentScanner.MAX_HEIGHT);
					return "height=" + h;
				}
				case TWEAKS:
				case UTXOS: {
					String tweaks = kind == Kind.TWEAKS ? body : TWEAKS_BODY;
					String utxos = kind == Kind.UTXOS ? body : UTXOS_BODY;
					List<SilentPaymentScanner.Found> found =
							SilentPaymentScanner.scanBlock(ORACLE, 800000,
									SCAN_PRIV, SPEND_PUB, "w", (url, tag) ->
											url.contains("/tweaks/") ? tweaks
													: utxos);
					StringBuilder sb = new StringBuilder("found");
					for (SilentPaymentScanner.Found f : found) {
						assertTrue(f.valueSat >= 0
								&& f.valueSat <= BtcAmounts.MAX_MONEY_SAT);
						assertTrue(ElectrumClient.isTxid(f.txid));
						sb.append(' ').append(f.txid).append(':').append(f.vout)
								.append(':').append(f.valueSat);
					}
					return sb.toString();
				}
				default: {
					BtcPrice.Rates rates = BtcPrice.Rates.parse(body);
					Map<String, Double> sorted = new TreeMap<>();
					for (String c : BtcPrice.CURRENCIES) {
						if (rates.has(c)) {
							double v = rates.get(c);
							assertTrue(v >= 100 && v <= 1e12);
							sorted.put(c, v);
						}
					}
					return sorted.toString();
				}
			}
		} catch (IOException refused) {
			return REFUSED;
		}
	}

	private static byte[] http(String status, List<String> headers, byte[] body) {
		StringBuilder head = new StringBuilder(status).append("\r\n");
		for (String h : headers) head.append(h).append("\r\n");
		head.append("\r\n");
		return concat(head.toString().getBytes(StandardCharsets.ISO_8859_1),
				body);
	}

	private static byte[] framed(String body, boolean withLength) {
		byte[] b = body.getBytes(StandardCharsets.UTF_8);
		List<String> headers = new ArrayList<>();
		headers.add("Content-Type: application/json; charset=utf-8");
		if (withLength) headers.add("Content-Length: " + b.length);
		headers.add("Connection: close");
		return http("HTTP/1.1 200 OK", headers, b);
	}

	private static String outcome(Kind kind, byte[] response) {
		String body;
		try {
			body = TorHttp.readResponse(new ByteArrayInputStream(response),
					TorHttp.MAX_BODY);
		} catch (IOException refused) {
			return REFUSED;
		}
		return consume(kind, body);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] out = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, out, a.length, b.length);
		return out;
	}

	@Test
	public void theUntouchedResponsesAreReadCompletely() {
		assertEquals("height=970750", outcome(Kind.TIP, framed(TIP_BODY, true)));
		String payment = "found " + REAL_TXID + ":1:54321";
		assertEquals(payment, outcome(Kind.TWEAKS, framed(TWEAKS_BODY, false)));
		assertEquals(payment, outcome(Kind.UTXOS, framed(UTXOS_BODY, false)));
		assertEquals("{AUD=118713.0, CAD=118189.0, CHF=68805.0, EUR=73960.0,"
						+ " GBP=62666.0, JPY=1.3123418E7, USD=82787.0}",
				outcome(Kind.PRICES, framed(PRICES_BODY, true)));
	}

	@Test
	public void aResponseCutAtAnyByteIsNeverReadAsWalletData() {
		int cases = 0;
		for (Kind kind : Kind.values()) {
			for (boolean withLength : new boolean[] {true, false}) {
				byte[] full = framed(bodyOf(kind), withLength);
				String expected = outcome(kind, full);
				assertTrue(kind + " full", !expected.equals(REFUSED));
				for (int cut = 0; cut < full.length; cut++) {
					String got = outcome(kind, Arrays.copyOf(full, cut));
					cases++;
					assertEquals(kind + " cut at " + cut + " of " + full.length
							+ (withLength ? " with" : " without")
							+ " a length", REFUSED, got);
				}
			}
		}
		assertTrue(cases > 1500);
	}

	private static final String[] BAD_LENGTHS = {"-1", "", "abc", "1 0", "+5",
			"0x10", "99999999999", "4294967296", "1e3", "5,5", "\t", "2147483648"};

	@Test
	public void mutatedResponsesFailClosedOrStayWithinBounds() {
		Random r = new Random(SEED);
		Kind[] kinds = Kind.values();
		for (int i = 0; i < MUTATIONS; i++) {
			Kind kind = kinds[r.nextInt(kinds.length)];
			String body = bodyOf(kind);
			byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
			boolean withLength = r.nextBoolean();
			int type = r.nextInt(12);
			String what = "case " + i + " type " + type + " " + kind;
			byte[] response;
			boolean mustRefuse;
			switch (type) {
				case 0: {
					byte[] full = framed(body, withLength);
					byte[] m = full.clone();
					int at = r.nextInt(m.length);
					m[at] = (byte) r.nextInt(256);
					response = m;
					mustRefuse = false;
					break;
				}
				case 1: {
					byte[] full = framed(body, withLength);
					int at = r.nextInt(full.length + 1);
					byte[] junk = new byte[1 + r.nextInt(8)];
					r.nextBytes(junk);
					response = concat(concat(Arrays.copyOf(full, at), junk),
							Arrays.copyOfRange(full, at, full.length));
					mustRefuse = withLength && at >= full.length
							- bodyBytes.length;
					break;
				}
				case 2: {
					byte[] full = framed(body, withLength);
					int from = r.nextInt(full.length);
					int to = Math.min(full.length, from + 1 + r.nextInt(16));
					response = concat(Arrays.copyOf(full, from),
							Arrays.copyOfRange(full, to, full.length));
					mustRefuse = withLength && from >= full.length
							- bodyBytes.length;
					break;
				}
				case 3: {
					String[] tails = {"x", "}", "]", ",", "{}", "[]", " 1",
							"\"a\"", "null", body};
					String tail = tails[r.nextInt(tails.length)];
					response = withLength
							? concat(framed(body, true),
									tail.getBytes(StandardCharsets.UTF_8))
							: framed(body + tail, false);
					mustRefuse = true;
					break;
				}
				case 4: {
					String value = r.nextBoolean()
							? BAD_LENGTHS[r.nextInt(BAD_LENGTHS.length)]
							: String.valueOf(bodyBytes.length
									+ (r.nextBoolean() ? 1 : -1)
									* (1 + r.nextInt(bodyBytes.length)));
					response = http("HTTP/1.1 200 OK", Arrays.asList(
							"Content-Length: " + value), bodyBytes);
					mustRefuse = true;
					break;
				}
				case 5: {
					response = http("HTTP/1.1 200 OK", Arrays.asList(
							"Content-Length: " + bodyBytes.length,
							"Content-Length: " + (bodyBytes.length + 1
									+ r.nextInt(5))), bodyBytes);
					mustRefuse = true;
					break;
				}
				case 6: {
					String chunked = Integer.toHexString(bodyBytes.length)
							+ "\r\n" + body + "\r\n0\r\n\r\n";
					response = http("HTTP/1.1 200 OK", Arrays.asList(
							r.nextBoolean() ? "Transfer-Encoding: chunked"
									: "transfer-encoding: gzip, chunked"),
							chunked.getBytes(StandardCharsets.UTF_8));
					mustRefuse = true;
					break;
				}
				case 7: {
					char[] filler = new char[TorHttp.MAX_HEADER];
					Arrays.fill(filler, 'a');
					response = http("HTTP/1.1 200 OK", Arrays.asList(
							"X-Filler: " + new String(filler)), bodyBytes);
					mustRefuse = true;
					break;
				}
				case 8: {
					byte[][] invalid = {{(byte) 0xFF}, {(byte) 0xC3, 0x28},
							{(byte) 0xE2, (byte) 0x82}, {(byte) 0xF0,
							(byte) 0x9F, (byte) 0x98}, {(byte) 0xC0,
							(byte) 0xAF}, {(byte) 0xED, (byte) 0xA0,
							(byte) 0x80}};
					byte[] bad = invalid[r.nextInt(invalid.length)];
					int at = r.nextInt(bodyBytes.length + 1);
					byte[] b = concat(concat(Arrays.copyOf(bodyBytes, at), bad),
							Arrays.copyOfRange(bodyBytes, at, bodyBytes.length));
					response = withLength
							? http("HTTP/1.1 200 OK", Collections.singletonList(
									"Content-Length: " + b.length), b)
							: http("HTTP/1.1 200 OK", Collections.emptyList(), b);
					mustRefuse = true;
					break;
				}
				case 9: {
					String[] statuses = {"HTTP/1.1 2000 OK", "HTTP/1.1 404 OK",
							"HTTP/1.1 500 Internal Server Error", "HTTP/2 200",
							"HTTP/1.2 200 OK", "HTTP/1.1  200 OK", " HTTP/1.1 200",
							"HTTP/1.1 20O OK", "http/1.1 200 OK", "HTTP/1.1 201"};
					response = http(statuses[r.nextInt(statuses.length)],
							Collections.singletonList("Content-Length: "
									+ bodyBytes.length), bodyBytes);
					mustRefuse = true;
					break;
				}
				case 10: {
					String lf = "HTTP/1.1 200 OK\nContent-Length: "
							+ bodyBytes.length + "\n\n";
					response = concat(lf.getBytes(StandardCharsets.US_ASCII),
							bodyBytes);
					mustRefuse = true;
					break;
				}
				default: {
					String[] hostile = {"-1", "1e999", "1.5", "-0",
							"99999999999999999999", "\"5\"", "null", "true",
							"[]", "{}", "1E+2", "0.0000001", "\"\"",
							"21000000000000001"};
					String v = hostile[r.nextInt(hostile.length)];
					String mutated = body.replaceFirst(
							"(?<=:)(?:\\d+|\"[0-9a-f]{64}\")", Matcher
									.quoteReplacement(v));
					response = framed(mutated, withLength);
					mustRefuse = false;
					break;
				}
			}
			String got = outcome(kind, response);
			if (mustRefuse) {
				assertEquals(what, REFUSED, got);
			}
		}
	}

	@Test
	public void strictJsonRefusesEveryCutOfACompleteDocument()
			throws IOException {
		Random r = new Random(SEED);
		for (int i = 0; i < JSON_DOCUMENTS; i++) {
			String doc = r.nextBoolean() ? randomArray(r, 0)
					: randomObject(r, 0);
			StrictJson.parse(doc);
			for (int cut = 0; cut < doc.length(); cut++) {
				try {
					StrictJson.parse(doc.substring(0, cut));
					fail("a cut document parsed: " + doc.substring(0, cut));
				} catch (IOException expected) {
				}
			}
		}
	}

	@Test
	public void strictJsonRefusesHostileInputWithoutOtherErrors() {
		Random r = new Random(SEED);
		String[] seeds = {"{", "}", "[", "]", "\"", "\\", ":", ",", "-", "0",
				"1", "e", "E", ".", "+", "t", "true", "f", "null", "nul", " ",
				"\\u", "\\u12", "\u0000", "\u001f", "\uffff", "\ud800", "\"\\x\"",
				"1e99999999999", "00", "01", "-a", "[1,]", "{\"a\"}", "{,}"};
		for (int i = 0; i < MUTATIONS; i++) {
			StringBuilder sb = new StringBuilder();
			int parts = 1 + r.nextInt(20);
			for (int p = 0; p < parts; p++) {
				sb.append(seeds[r.nextInt(seeds.length)]);
			}
			try {
				StrictJson.parse(sb.toString());
			} catch (IOException refused) {
			}
		}
		StringBuilder deep = new StringBuilder();
		for (int d = 0; d <= StrictJson.MAX_DEPTH; d++) deep.append('[');
		for (int d = 0; d <= StrictJson.MAX_DEPTH; d++) deep.append(']');
		refused(deep.toString());
		char[] digits = new char[StrictJson.MAX_NUMBER_LENGTH + 1];
		Arrays.fill(digits, '7');
		refused("[" + new String(digits) + "]");
		char[] many = new char[1 << 20];
		Arrays.fill(many, '[');
		refused(new String(many));
		refused("{\"a\":1,\"a\":2}");
		refused("[1] [2]");
		refused("\ufeff[1]");
		refused("[\"\t\"]");
		refused("['a']");
		refused("{a:1}");
		refused("[NaN]");
		refused("[Infinity]");
	}

	private static void refused(String text) {
		try {
			StrictJson.parse(text);
			fail("accepted " + text.substring(0, Math.min(40, text.length())));
		} catch (IOException expected) {
		}
	}

	private static String randomValue(Random r, int depth) {
		int pick = depth >= 4 ? 2 + r.nextInt(4) : r.nextInt(6);
		switch (pick) {
			case 0:
				return randomArray(r, depth + 1);
			case 1:
				return randomObject(r, depth + 1);
			case 2:
				return String.valueOf(r.nextInt(2000000) - 1000000);
			case 3:
				return "\"" + Long.toHexString(r.nextLong()) + "\\n\\u00e9\"";
			case 4:
				return r.nextBoolean() ? "true" : "null";
			default:
				return (r.nextInt(1000) / 7.0) + "e" + (r.nextInt(10) - 5);
		}
	}

	private static String randomArray(Random r, int depth) {
		StringBuilder sb = new StringBuilder("[");
		int n = r.nextInt(5);
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(r.nextBoolean() ? "," : " ,\n");
			sb.append(randomValue(r, depth));
		}
		return sb.append(']').toString();
	}

	private static String randomObject(Random r, int depth) {
		StringBuilder sb = new StringBuilder("{");
		int n = r.nextInt(5);
		for (int i = 0; i < n; i++) {
			if (i > 0) sb.append(',');
			sb.append("\"k").append(i).append("\" : ")
					.append(randomValue(r, depth));
		}
		return sb.append('}').toString();
	}

	private final List<AutoCloseable> closeables = new ArrayList<>();

	@After
	public void tearDown() throws Exception {
		for (AutoCloseable c : closeables) c.close();
	}

	private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

	@Test(timeout = 120_000)
	public void anElectrumReplyCutAtAnyByteIsNeverUsed() throws Exception {
		String txid = REAL_TXID;
		String result = "\"result\":[{\"tx_hash\":\"" + txid
				+ "\",\"tx_pos\":1,\"height\":800000,\"value\":5000},"
				+ "{\"tx_hash\":\"" + DECOY_TXID
				+ "\",\"tx_pos\":0,\"height\":0,\"value\":7}]}";
		ServerSocket ss = new ServerSocket(0, 50,
				InetAddress.getLoopbackAddress());
		closeables.add(ss);
		AtomicInteger cut = new AtomicInteger();
		AtomicBoolean newline = new AtomicBoolean();
		Thread server = new Thread(() -> {
			while (!ss.isClosed()) {
				try (Socket s = ss.accept()) {
					BufferedReader in = new BufferedReader(new InputStreamReader(
							s.getInputStream(), StandardCharsets.UTF_8));
					OutputStream out = s.getOutputStream();
					String line;
					while ((line = in.readLine()) != null) {
						Matcher m = ID.matcher(line);
						String id = m.find() ? m.group(1) : "0";
						if (line.contains("server.version")) {
							out.write(("{\"id\":" + id
									+ ",\"result\":[\"x\",\"1.4\"]}\n")
									.getBytes(StandardCharsets.UTF_8));
							out.flush();
							continue;
						}
						String reply = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ","
								+ result;
						byte[] b = reply.getBytes(StandardCharsets.UTF_8);
						int n = Math.min(cut.get(), b.length);
						out.write(b, 0, n);
						if (newline.get()) out.write('\n');
						out.flush();
						break;
					}
				} catch (IOException e) {
					if (ss.isClosed()) return;
				}
			}
		});
		server.setDaemon(true);
		server.start();
		ElectrumEndpoint ep = new ElectrumEndpoint("127.0.0.1",
				ss.getLocalPort(), ElectrumEndpoint.Mode.PLAINTEXT, true, null);
		String scriptHash = DECOY_TXID;
		int full = ("{\"jsonrpc\":\"2.0\",\"id\":2," + result).length();
		for (boolean nl : new boolean[] {false, true}) {
			for (int c = 0; c < full; c++) {
				cut.set(c);
				newline.set(nl);
				ElectrumClient client = new ElectrumClient(ep, 0, "w");
				try {
					List<ElectrumClient.Utxo> got = client.listUnspent(
							scriptHash);
					fail("a reply cut at " + c + " gave " + got.size());
				} catch (IOException expected) {
				} finally {
					client.close();
				}
			}
		}
		cut.set(Integer.MAX_VALUE);
		newline.set(true);
		ElectrumClient client = new ElectrumClient(ep, 0, "w");
		try {
			assertEquals(2, client.listUnspent(scriptHash).size());
		} finally {
			client.close();
		}
	}
}
