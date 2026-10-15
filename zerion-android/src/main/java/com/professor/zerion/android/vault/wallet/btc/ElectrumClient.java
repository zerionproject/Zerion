package com.professor.zerion.android.vault.wallet.btc;


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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
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

	private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*\\}");
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
		this.readTimeoutMs = Math.min(HANDSHAKE_TIMEOUT_MS, readTimeoutMs);
		this.deadlineMs = deadlineMs;
		Deadline deadline = new Deadline(deadlineMs);
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
			long left = (endNanos - System.nanoTime()) / 1_000_000L;
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

	public static CapturedCert captureCert(ElectrumEndpoint ep, int socksPort)
			throws IOException {
		return captureCert(ep, socksPort, EXCHANGE_DEADLINE_MS);
	}

	static CapturedCert captureCert(ElectrumEndpoint ep, int socksPort,
			int deadlineMs) throws IOException {
		if (!ep.tls()) {
			throw new IOException("not a TLS endpoint");
		}
		String tag = TorIsolation.ephemeral("tofu");
		Deadline deadline = new Deadline(deadlineMs);
		try {
			try {
				return new CapturedCert(handshakeAndCapture(ep, socksPort, tag,
						true, deadline), true);
			} catch (NotCaValid selfSigned) {
				return new CapturedCert(handshakeAndCapture(ep, socksPort, tag,
						false, deadline), false);
			}
		} catch (IOException e) {
			throw deadline.failure(e);
		} finally {
			deadline.cancel();
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
				restrictProtocols(ssl);
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
			restrictProtocols(ssl);
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

	static void restrictProtocols(SSLSocket ssl) {
		java.util.List<String> keep = new java.util.ArrayList<>();
		for (String p : ssl.getSupportedProtocols()) {
			if ("TLSv1.3".equals(p) || "TLSv1.2".equals(p)) keep.add(p);
		}
		if (!keep.isEmpty()) {
			ssl.setEnabledProtocols(keep.toArray(new String[0]));
		}
	}

	private static Socket wrapTls(Socket base, ElectrumEndpoint ep)
			throws IOException {
		try {
			SSLSocket ssl;
			if (ep.pinned()) {
				SSLSocketFactory f = TlsTrust.pinnedFactory(ep.pinSha256);
				ssl = (SSLSocket) f.createSocket(base, ep.host, ep.port, true);
				restrictProtocols(ssl);
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
				restrictProtocols(ssl);
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
		String r = call("blockchain.headers.subscribe", "[]");
		Long h = numField(r, "height");
		return h == null ? 0 : h.intValue();
	}

	public long getBalanceSat(String scriptHash) throws IOException {
		String r = call("blockchain.scripthash.get_balance",
				"[\"" + scriptHash + "\"]");
		Long c = numField(r, "confirmed");
		Long u = numField(r, "unconfirmed");
		return (c == null ? 0 : c) + (u == null ? 0 : u);
	}

	public List<HistItem> getHistory(String scriptHash) throws IOException {
		return parseHistory(call("blockchain.scripthash.get_history",
				"[\"" + scriptHash + "\"]"));
	}

	static List<HistItem> parseHistory(String r) {
		List<HistItem> out = new ArrayList<>();
		for (String o : objects(r)) {
			String h = strField(o, "tx_hash");
			if (h == null || !isTxid(h)) {
				continue;
			}
			Long height = numField(o, "height");
			out.add(new HistItem(h, height == null ? 0 : height.intValue()));
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

	static List<Utxo> parseUnspent(String r) {
		List<Utxo> out = new ArrayList<>();
		for (String o : objects(r)) {
			String h = strField(o, "tx_hash");
			if (h == null || !isTxid(h)) {
				continue;
			}
			Long pos = numField(o, "tx_pos");
			Long height = numField(o, "height");
			Long value = numField(o, "value");
			if (pos == null || pos < 0 || pos > 0xFFFF) continue;
			if (value == null || value < 0 || value > 21_000_000L * 100_000_000L) {
				continue;
			}
			out.add(new Utxo(h, pos.intValue(),
					height == null ? 0 : height.intValue(), value));
			if (out.size() >= MAX_LIST_ITEMS) break;
		}
		return out;
	}

	public String getTransaction(String txid) throws IOException {
		String wanted = requireTxid(txid);
		String r = call("blockchain.transaction.get", "[\"" + wanted + "\"]");
		String result = strField(r, "result");
		if (result == null) {
			throw new IOException("no tx");
		}
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
		String result = strField(r, "result");
		if (result == null) {
			throw new IOException("broadcast failed");
		}
		if (!TXID.matcher(result).matches()) {
			throw new IOException("broadcast reply was not a transaction id");
		}
		return result;
	}

	public double estimateFeeBtcPerKb(int blocks) throws IOException {
		return parseFee(call("blockchain.estimatefee", "[" + blocks + "]"));
	}

	static double parseFee(String r) {
		int i = r.indexOf("\"result\":");
		if (i < 0) {
			return 0.0;
		}
		int s = i + "\"result\":".length();
		int e = s;
		while (e < r.length() && r.charAt(e) != ',' && r.charAt(e) != '}') {
			e++;
		}
		try {
			double fee = Double.parseDouble(r.substring(s, e).trim());
			if (Double.isNaN(fee) || Double.isInfinite(fee) || fee < 0) {
				return 0.0;
			}
			return fee;
		} catch (NumberFormatException ex) {
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

	@Nullable
	private static String strField(String json, String key) {
		String m = "\"" + key + "\":\"";
		int i = json.indexOf(m);
		if (i < 0) {
			return null;
		}
		int start = i + m.length();
		int end = json.indexOf('"', start);
		return end < 0 ? null : json.substring(start, end);
	}

	@Nullable
	private static Long numField(String json, String key) {
		String m = "\"" + key + "\":";
		int i = json.indexOf(m);
		if (i < 0) {
			return null;
		}
		int s = i + m.length();
		if (s < json.length() && json.charAt(s) == '"') {
			s++;
		}
		int e = s;
		while (e < json.length()
				&& (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) {
			e++;
		}
		try {
			return Long.parseLong(json.substring(s, e));
		} catch (NumberFormatException ex) {
			return null;
		}
	}

	private static List<String> objects(String json) {
		List<String> out = new ArrayList<>();
		int start = json.indexOf("\"result\":[");
		if (start < 0) {
			return out;
		}
		Matcher matcher = OBJECT.matcher(json.substring(start));
		while (matcher.find()) {
			out.add(matcher.group());
		}
		return out;
	}
}
