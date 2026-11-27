package com.professor.zerion.android.vault.wallet.btc;


import com.professor.zerion.android.util.TlsProtocols;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

@NotNullByDefault
public class ElectrumClient implements ElectrumRpc {

	public static final class Utxo {
		public final String txHash;
		public final int txPos;
		public final int height;
		public final long value;

		public Utxo(String txHash, int txPos, int height, long value) {
			this.txHash = txHash;
			this.txPos = txPos;
			this.height = height;
			this.value = value;
		}
	}

	public static final class HistItem {
		public final String txHash;
		public final int height;

		public HistItem(String txHash, int height) {
			this.txHash = txHash;
			this.height = height;
		}
	}

	private static final Pattern TXID = Pattern.compile("^[0-9a-fA-F]{64}$");
	static final int MAX_LIST_ITEMS = 5000;
	static final int MAX_TX_HEX_CHARS = 2 * 1_000_000;

	private static final int MAX_RESPONSE_CHARS = 8 * 1024 * 1024;

	public static final class ServerRejectedException extends IOException {
		ServerRejectedException(String message) {
			super(message);
		}
	}

	private final Socket socket;
	private final Socket stream;
	private final OutputStream writer;
	private final BufferedReader reader;
	private final int deadlineMs;
	private int readTimeoutMs;
	@Nullable
	private Deadline active;
	private int id = 0;

	public ElectrumClient(String host, int port, int socksPort,
			String isolationTag) throws IOException {
		this(ElectrumEndpoint.parse(host + ":" + port), socksPort,
				isolationTag);
	}

	private static final int HANDSHAKE_TIMEOUT_MS = 15_000;
	private static final int READ_TIMEOUT_MS = 40_000;
	static final int EXCHANGE_DEADLINE_MS = 120_000;
	static final int MAX_SKIPPED_LINES = 64;
	static final String NO_ANSWER_IN_TIME = "the server did not answer in time";

	private static final ScheduledExecutorService DEADLINES = deadlines();

	private static ScheduledExecutorService deadlines() {
		ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1,
				r -> {
					Thread t = new Thread(r, "ElectrumDeadline");
					t.setDaemon(true);
					return t;
				});
		e.setRemoveOnCancelPolicy(true);
		return e;
	}

	public ElectrumClient(ElectrumEndpoint ep, int socksPort,
			String isolationTag) throws IOException {
		this(ep, socksPort, isolationTag, READ_TIMEOUT_MS,
				EXCHANGE_DEADLINE_MS);
	}

	ElectrumClient(ElectrumEndpoint ep, int socksPort, String isolationTag,
			int readTimeoutMs, int deadlineMs) throws IOException {
		this(ep, socksPort, isolationTag, readTimeoutMs, deadlineMs,
				deadlineMs);
	}

	ElectrumClient(ElectrumEndpoint ep, int socksPort, String isolationTag,
			int readTimeoutMs, int connectDeadlineMs, int callDeadlineMs)
			throws IOException {
		this.readTimeoutMs = Math.min(HANDSHAKE_TIMEOUT_MS, readTimeoutMs);
		this.deadlineMs = callDeadlineMs;
		Deadline deadline = new Deadline(connectDeadlineMs);
		Socket base = null;
		try {
			if (ep.viaTor()) {
				base = com.professor.zerion.android.vault.net.TorSockets.open(
						socksPort, Math.min(HANDSHAKE_TIMEOUT_MS,
								deadline.remainingMs()));
				deadline.watch(base);
				socks5Connect(base, ep.host, ep.port, "zw-" + isolationTag,
						isolationTag);
			} else {
				if (!ep.direct && !ElectrumEndpoint.isLanHost(ep.host)) {
					throw new IOException(
							"Refusing non-Tor connection to non-local endpoint");
				}
				base = new Socket();
				deadline.watch(base);
				base.connect(new InetSocketAddress(ep.host, ep.port),
						Math.min(HANDSHAKE_TIMEOUT_MS, deadline.remainingMs()));
				base.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			}
			if (ep.mode == ElectrumEndpoint.Mode.PLAINTEXT && !ep.local) {
				throw new IOException(
						"Refusing a plaintext connection to a non-local endpoint");
			}
			stream = ep.tls() ? wrapTls(base, ep) : base;
			socket = base;
			writer = stream.getOutputStream();
			reader = new BufferedReader(new InputStreamReader(
					new DeadlineInputStream(stream.getInputStream()),
					StandardCharsets.UTF_8));
			exchange(deadline, "server.version", "[\"\",\"1.4\"]");
			this.readTimeoutMs = readTimeoutMs;
		} catch (IOException e) {
			closeQuietly(base);
			throw deadline.failure(e);
		} catch (RuntimeException e) {
			closeQuietly(base);
			throw e;
		} finally {
			deadline.cancel();
		}
	}

	private static void closeQuietly(@Nullable Socket s) {
		if (s == null) return;
		try {
			s.close();
		} catch (IOException ignored) {
		}
	}

	private static final class Deadline {

		private final long endNanos;
		private final AtomicBoolean fired = new AtomicBoolean();
		@Nullable
		private ScheduledFuture<?> task;

		Deadline(int ms) {
			endNanos = System.nanoTime() + ms * 1_000_000L;
		}

		int remainingMs() throws SocketTimeoutException {
			long left = (endNanos - System.nanoTime() + 999_999L) / 1_000_000L;
			if (left <= 0) {
				fired.set(true);
				throw new SocketTimeoutException(NO_ANSWER_IN_TIME);
			}
			return (int) Math.min(Integer.MAX_VALUE, left);
		}

		synchronized void watch(Socket s) throws SocketTimeoutException {
			cancel();
			long left;
			try {
				left = remainingMs();
			} catch (SocketTimeoutException e) {
				closeQuietly(s);
				throw e;
			}
			task = DEADLINES.schedule(() -> {
				fired.set(true);
				closeQuietly(s);
			}, left, TimeUnit.MILLISECONDS);
		}

		synchronized void cancel() {
			ScheduledFuture<?> t = task;
			task = null;
			if (t != null) t.cancel(false);
		}

		boolean timedOut(IOException e) {
			if (e instanceof ServerRejectedException) return false;
			return fired.get() || endNanos - System.nanoTime() <= 0;
		}

		IOException failure(IOException e) {
			if (!timedOut(e) || NO_ANSWER_IN_TIME.equals(e.getMessage())) {
				return e;
			}
			SocketTimeoutException t =
					new SocketTimeoutException(NO_ANSWER_IN_TIME);
			t.initCause(e);
			return t;
		}
	}

	private final class DeadlineInputStream extends InputStream {

		private final InputStream in;

		DeadlineInputStream(InputStream in) {
			this.in = in;
		}

		private void arm() throws IOException {
			Deadline d = active;
			int timeout = d == null ? readTimeoutMs
					: Math.min(readTimeoutMs, d.remainingMs());
			stream.setSoTimeout(timeout);
		}

		@Override
		public int read() throws IOException {
			arm();
			return in.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			arm();
			return in.read(b, off, len);
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}

	public static final class CapturedCert {
		public final String sha256;
		public final boolean caValid;

		CapturedCert(String sha256, boolean caValid) {
			this.sha256 = sha256;
			this.caValid = caValid;
		}
	}

	private static final class NotCaValid extends IOException {
		NotCaValid(Throwable cause) {
			super("certificate not trusted by any CA", cause);
		}
	}

	public static final class PossibleInterception extends IOException {
		PossibleInterception() {
			super("a publicly trusted certificate was replaced on one circuit");
		}
	}

	public static final class CertificatesDiffer extends IOException {
		CertificatesDiffer() {
			super("the server showed different certificates on two circuits");
		}
	}

	public static CapturedCert captureCert(ElectrumEndpoint ep, int socksPort)
			throws IOException {
		return captureCert(ep, socksPort, EXCHANGE_DEADLINE_MS);
	}

	static CapturedCert captureCert(ElectrumEndpoint ep, int socksPort,
			int deadlineMs) throws IOException {
		if (!ep.tls()) {
			throw new IOException("not a TLS endpoint");
		}
		String firstTag = TorIsolation.ephemeral("tofu");
		String secondTag = TorIsolation.ephemeral("tofu");
		while (secondTag.equals(firstTag)) {
			secondTag = TorIsolation.ephemeral("tofu");
		}
		Deadline deadline = new Deadline(deadlineMs);
		CapturedCert first;
		CapturedCert second;
		try {
			first = captureOnCircuit(ep, socksPort, firstTag, deadline);
			second = captureOnCircuit(ep, socksPort, secondTag, deadline);
		} catch (IOException e) {
			throw deadline.failure(e);
		} finally {
			deadline.cancel();
		}
		return agreed(first, second);
	}

	static CapturedCert agreed(CapturedCert first, CapturedCert second)
			throws IOException {
		if (first.caValid != second.caValid) {
			throw new PossibleInterception();
		}
		if (!TlsTrust.constEq(first.sha256, second.sha256)) {
			throw new CertificatesDiffer();
		}
		return first;
	}

	private static CapturedCert captureOnCircuit(ElectrumEndpoint ep,
			int socksPort, String tag, Deadline deadline) throws IOException {
		try {
			return new CapturedCert(handshakeAndCapture(ep, socksPort, tag,
					true, deadline), true);
		} catch (NotCaValid notTrusted) {
			return new CapturedCert(handshakeAndCapture(ep, socksPort, tag,
					false, deadline), false);
		}
	}

	private static String handshakeAndCapture(ElectrumEndpoint ep,
			int socksPort, String tag, boolean validate, Deadline deadline)
			throws IOException {
		Socket base = null;
		try {
			if (ep.viaTor()) {
				base = com.professor.zerion.android.vault.net.TorSockets.open(
						socksPort, Math.min(HANDSHAKE_TIMEOUT_MS,
								deadline.remainingMs()));
				deadline.watch(base);
				socks5Connect(base, ep.host, ep.port,
						TorIsolation.socksUser(tag),
						TorIsolation.socksPassword(tag));
			} else {
				if (!ep.direct && !ElectrumEndpoint.isLanHost(ep.host)) {
					throw new IOException(
							"Refusing non-Tor connection to non-local endpoint");
				}
				base = new Socket();
				deadline.watch(base);
				base.connect(new InetSocketAddress(ep.host, ep.port),
						Math.min(HANDSHAKE_TIMEOUT_MS, deadline.remainingMs()));
				base.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			}
			if (validate) {
				SSLSocketFactory f = (SSLSocketFactory)
						SSLSocketFactory.getDefault();
				SSLSocket ssl = (SSLSocket) f.createSocket(base, ep.host,
						ep.port, true);
				TlsProtocols.restrict(ssl);
				try {
					ssl.startHandshake();
				} catch (javax.net.ssl.SSLHandshakeException e) {
					throw new NotCaValid(e);
				}
				if (!HttpsURLConnection.getDefaultHostnameVerifier()
						.verify(ep.host, ssl.getSession())) {
					throw new NotCaValid(null);
				}
				java.security.cert.Certificate[] chain =
						ssl.getSession().getPeerCertificates();
				if (chain == null || chain.length == 0) {
					throw new IOException("no server certificate");
				}
				String fp = TlsTrust.sha256Hex(chain[0].getEncoded());
				ssl.close();
				return fp;
			}
			final java.security.cert.X509Certificate[] captured =
					new java.security.cert.X509Certificate[1];
			javax.net.ssl.SSLContext ctx =
					javax.net.ssl.SSLContext.getInstance("TLS");
			ctx.init(null, new javax.net.ssl.TrustManager[]{
					new javax.net.ssl.X509TrustManager() {
						@Override
						public void checkClientTrusted(
								java.security.cert.X509Certificate[] c,
								String a) {
						}

						@Override
						public void checkServerTrusted(
								java.security.cert.X509Certificate[] c, String a)
								throws java.security.cert.CertificateException {
							if (c == null || c.length == 0) {
								throw new java.security.cert
										.CertificateException("no cert");
							}
							captured[0] = c[0];
						}

						@Override
						public java.security.cert.X509Certificate[]
								getAcceptedIssuers() {
							return new java.security.cert.X509Certificate[0];
						}
					}}, null);
			SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
					.createSocket(base, ep.host, ep.port, true);
			TlsProtocols.restrict(ssl);
			ssl.startHandshake();
			String fp = TlsTrust.sha256Hex(captured[0].getEncoded());
			ssl.close();
			return fp;
		} catch (java.security.GeneralSecurityException e) {
			throw new IOException("could not read certificate", e);
		} finally {
			try {
				if (base != null) base.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static Socket wrapTls(Socket base, ElectrumEndpoint ep)
			throws IOException {
		try {
			SSLSocket ssl;
			if (ep.pinned()) {
				SSLSocketFactory f = TlsTrust.pinnedFactory(ep.pinSha256);
				ssl = (SSLSocket) f.createSocket(base, ep.host, ep.port, true);
				TlsProtocols.restrict(ssl);
				try {
					ssl.startHandshake();
				} catch (javax.net.ssl.SSLHandshakeException e) {
					throw new IOException("Server certificate does not match "
							+ "the pinned fingerprint (possible tampering)", e);
				}
			} else {
				SSLSocketFactory f = (SSLSocketFactory)
						SSLSocketFactory.getDefault();
				ssl = (SSLSocket) f.createSocket(base, ep.host, ep.port, true);
				TlsProtocols.restrict(ssl);
				try {
					ssl.startHandshake();
				} catch (javax.net.ssl.SSLHandshakeException e) {
					throw new IOException("Server certificate could not be "
							+ "verified", e);
				}
				if (!HttpsURLConnection.getDefaultHostnameVerifier()
						.verify(ep.host, ssl.getSession())) {
					throw new IOException("TLS hostname verification failed");
				}
			}
			return ssl;
		} catch (java.security.GeneralSecurityException e) {
			throw new IOException("TLS setup failed", e);
		}
	}

	private static void socks5Connect(Socket socket, String host, int port,
			String user, String pass) throws IOException {
		OutputStream out = socket.getOutputStream();
		InputStream in = socket.getInputStream();

		out.write(new byte[]{0x05, 0x01, 0x02});
		out.flush();
		byte[] sel = readFully(in, 2);
		if (sel[0] != 0x05 || sel[1] != 0x02) {
			throw new IOException("Tor SOCKS auth not accepted");
		}

		byte[] u = user.getBytes(StandardCharsets.UTF_8);
		byte[] p = pass.getBytes(StandardCharsets.UTF_8);
		if (u.length > 255 || p.length > 255) {
			throw new IOException("SOCKS credential too long");
		}
		ByteArrayOutputStream auth = new ByteArrayOutputStream();
		auth.write(0x01);
		auth.write(u.length);
		auth.write(u, 0, u.length);
		auth.write(p.length);
		auth.write(p, 0, p.length);
		out.write(auth.toByteArray());
		out.flush();
		byte[] authReply = readFully(in, 2);
		if (authReply[1] != 0x00) {
			throw new IOException("Tor SOCKS auth failed");
		}

		byte[] d = host.getBytes(StandardCharsets.US_ASCII);
		if (d.length > 255) {
			throw new IOException("host too long");
		}
		ByteArrayOutputStream req = new ByteArrayOutputStream();
		req.write(0x05);
		req.write(0x01);
		req.write(0x00);
		req.write(0x03);
		req.write(d.length);
		req.write(d, 0, d.length);
		req.write((port >> 8) & 0xFF);
		req.write(port & 0xFF);
		out.write(req.toByteArray());
		out.flush();

		byte[] rep = readFully(in, 4);
		if (rep[0] != 0x05) {
			throw new IOException("bad SOCKS reply");
		}
		if (rep[1] != 0x00) {
			throw new IOException("Tor could not reach the server (rep="
					+ (rep[1] & 0xFF) + ")");
		}
		int atyp = rep[3] & 0xFF;
		int addrLen;
		if (atyp == 0x01) {
			addrLen = 4;
		} else if (atyp == 0x04) {
			addrLen = 16;
		} else if (atyp == 0x03) {
			addrLen = readFully(in, 1)[0] & 0xFF;
		} else {
			throw new IOException("bad SOCKS address type");
		}
		readFully(in, addrLen + 2);
	}

	private static byte[] readFully(InputStream in, int n) throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(b, off, n - off);
			if (r < 0) {
				throw new IOException("SOCKS stream closed");
			}
			off += r;
		}
		return b;
	}

	private String call(String method, String params) throws IOException {
		Deadline deadline = new Deadline(deadlineMs);
		try {
			deadline.watch(socket);
			return exchange(deadline, method, params);
		} catch (IOException e) {
			if (deadline.timedOut(e)) close();
			throw deadline.failure(e);
		} finally {
			deadline.cancel();
		}
	}

	private String exchange(Deadline deadline, String method, String params)
			throws IOException {
		active = deadline;
		try {
			return exchange(method, params);
		} finally {
			active = null;
		}
	}

	private String exchange(String method, String params) throws IOException {
		int reqId = ++id;
		String line = "{\"id\":" + reqId + ",\"method\":\"" + method
				+ "\",\"params\":" + params + "}\n";
		writer.write(line.getBytes(StandardCharsets.UTF_8));
		writer.flush();
		int skipped = 0;
		while (true) {
			String resp = readLineBounded();
			if (resp == null) {
				throw new IOException("connection closed");
			}
			org.json.JSONObject reply;
			try {
				reply = new org.json.JSONObject(resp);
			} catch (org.json.JSONException notAReply) {
				if (++skipped > MAX_SKIPPED_LINES) {
					throw new IOException("no reply from server");
				}
				continue;
			}
			if (reply.optLong("id", -1L) != reqId) {
				if (++skipped > MAX_SKIPPED_LINES) {
					throw new IOException("no reply from server");
				}
				continue;
			}
			if (reply.has("error") && !reply.isNull("error")) {
				throw new ServerRejectedException("server refused the request");
			}
			return resp;
		}
	}

	private String readLineBounded() throws IOException {
		StringBuilder sb = new StringBuilder();
		while (true) {
			int c = reader.read();
			if (c == -1) {
				return sb.length() == 0 ? null : sb.toString();
			}
			if (c == '\n') {
				return sb.toString();
			}
			if (c != '\r') {
				sb.append((char) c);
			}
			if (sb.length() > MAX_RESPONSE_CHARS) {
				throw new IOException("response too large");
			}
		}
	}

	public int blockHeight() throws IOException {
		return parseHeight(call("blockchain.headers.subscribe", "[]"));
	}

	static int parseHeight(String r) throws IOException {
		Object h = StrictJson.object(result(r)).get("height");
		if (h == null) {
			throw new IOException("no height");
		}
		return (int) StrictJson.integer(h, 0, Integer.MAX_VALUE);
	}

	public long getBalanceSat(String scriptHash) throws IOException {
		return parseBalance(call("blockchain.scripthash.get_balance",
				"[\"" + scriptHash + "\"]"));
	}

	static long parseBalance(String r) throws IOException {
		Map<String, Object> b = StrictJson.object(result(r));
		return optLong(b, "confirmed", 0L, MAX_SAT)
				+ optLong(b, "unconfirmed", -MAX_SAT, MAX_SAT);
	}

	public List<HistItem> getHistory(String scriptHash) throws IOException {
		return parseHistory(call("blockchain.scripthash.get_history",
				"[\"" + scriptHash + "\"]"));
	}

	static List<HistItem> parseHistory(String r) throws IOException {
		List<HistItem> out = new ArrayList<>();
		for (Map<String, Object> o : objects(r)) {
			String h = txHash(o);
			if (h == null) {
				continue;
			}
			out.add(new HistItem(h, heightOf(o)));
			if (out.size() >= MAX_LIST_ITEMS) break;
		}
		return out;
	}

	static boolean isTxid(String s) {
		return TXID.matcher(s).matches();
	}

	private static String requireTxid(String txid) throws IOException {
		String t = txid.trim();
		if (!isTxid(t)) throw new IOException("invalid txid");
		return t;
	}

	public List<Utxo> listUnspent(String scriptHash) throws IOException {
		return parseUnspent(call("blockchain.scripthash.listunspent",
				"[\"" + scriptHash + "\"]"));
	}

	static List<Utxo> parseUnspent(String r) throws IOException {
		List<Utxo> out = new ArrayList<>();
		for (Map<String, Object> o : objects(r)) {
			String h = txHash(o);
			if (h == null) {
				continue;
			}
			Long pos = optRange(o.get("tx_pos"), 0, 0xFFFF);
			Long value = optRange(o.get("value"), 0, MAX_SAT);
			if (pos == null || value == null) continue;
			out.add(new Utxo(h, pos.intValue(), heightOf(o), value));
			if (out.size() >= MAX_LIST_ITEMS) break;
		}
		return out;
	}

	public String getTransaction(String txid) throws IOException {
		String wanted = requireTxid(txid);
		String r = call("blockchain.transaction.get", "[\"" + wanted + "\"]");
		Object res = result(r);
		if (!(res instanceof String)) {
			throw new IOException("no tx");
		}
		String result = (String) res;
		if (result.length() > MAX_TX_HEX_CHARS) {
			throw new IOException("tx too large");
		}
		String computed;
		try {
			byte[] raw = org.bitcoinj.core.Utils.HEX.decode(result.trim());
			computed = new org.bitcoinj.core.Transaction(
					BtcKeys.PARAMS, raw).getTxId().toString();
		} catch (RuntimeException e) {
			throw new IOException("invalid tx");
		}
		if (!computed.equalsIgnoreCase(txid.trim())) {
			throw new IOException("tx identity mismatch");
		}
		return result;
	}

	public String broadcast(String rawHex) throws IOException {
		return parseBroadcast(call("blockchain.transaction.broadcast",
				"[\"" + rawHex + "\"]"));
	}

	static String parseBroadcast(String r) throws IOException {
		Object res = result(r);
		if (!(res instanceof String)) {
			throw new IOException("broadcast failed");
		}
		String result = (String) res;
		if (!TXID.matcher(result).matches()) {
			throw new IOException("broadcast reply was not a transaction id");
		}
		return result;
	}

	public double estimateFeeBtcPerKb(int blocks) throws IOException {
		return parseFee(call("blockchain.estimatefee", "[" + blocks + "]"));
	}

	static double parseFee(String r) {
		try {
			Object res = result(r);
			if (!(res instanceof java.math.BigDecimal)) {
				return 0.0;
			}
			double fee = ((java.math.BigDecimal) res).doubleValue();
			if (Double.isNaN(fee) || Double.isInfinite(fee) || fee < 0) {
				return 0.0;
			}
			return fee;
		} catch (IOException malformed) {
			return 0.0;
		}
	}

	@Override
	public void close() {
		try {
			socket.close();
		} catch (Exception e) {
		}
	}

	private static final long MAX_SAT = 21_000_000L * 100_000_000L;

	private static Object result(String r) throws IOException {
		Object res = StrictJson.object(StrictJson.parse(r)).get("result");
		if (res == null) {
			throw new IOException("no result");
		}
		return res;
	}

	@Nullable
	private static String txHash(Map<String, Object> o) {
		Object h = o.get("tx_hash");
		return h instanceof String && isTxid((String) h) ? (String) h : null;
	}

	private static int heightOf(Map<String, Object> o) {
		Long h = optRange(o.get("height"), Integer.MIN_VALUE,
				Integer.MAX_VALUE);
		return h == null ? 0 : h.intValue();
	}

	@Nullable
	private static Long optRange(@Nullable Object v, long min, long max) {
		if (v == null) {
			return null;
		}
		try {
			return StrictJson.integer(v, min, max);
		} catch (IOException notInRange) {
			return null;
		}
	}

	private static long optLong(Map<String, Object> o, String key, long min,
			long max) throws IOException {
		Object v = o.get(key);
		return v == null ? 0L : StrictJson.integer(v, min, max);
	}

	private static List<Map<String, Object>> objects(String json)
			throws IOException {
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object item : StrictJson.array(result(json))) {
			if (item instanceof Map) {
				try {
					out.add(StrictJson.object(item));
				} catch (IOException notAnObject) {
				}
			}
		}
		return out;
	}
}
