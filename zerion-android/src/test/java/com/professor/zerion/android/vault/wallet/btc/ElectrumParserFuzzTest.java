package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * The Electrum reply parsers face a server that can send anything. Random
 * text, structurally valid JSON with hostile values (NaN and infinities,
 * negative and overflowing numbers, hashes that are not hex or not 64
 * characters, duplicate keys, deep nesting) and truncations must yield
 * bounded lists of well-formed entries or, for a broadcast reply, an
 * IOException, and never any other exception.
 */
public class ElectrumParserFuzzTest {

	private static final int RANDOM_INPUTS = 4000;
	private static final String TXID =
			"1111111111111111111111111111111111111111111111111111111111111111";

	private final Random random = new Random(43);

	private static final String[] VALUES = {"0", "-1", "1", "800000",
			"99999999999999999999999", "-99999999999999999999", "NaN",
			"Infinity", "-Infinity", "1e400", "1.5", "0x10", "\"" + TXID + "\"",
			"\"" + TXID.substring(1) + "\"", "\"" + TXID + "1\"",
			"\"zz" + TXID.substring(2) + "\"", "\"\"", "null", "true", "[]",
			"{}", "[[[[[[[[]]]]]]]]", "\"\\u0000\"", "\"" + TXID.toUpperCase() + "\""};
	private static final String[] KEYS = {"tx_hash", "height", "value",
			"tx_pos", "result", "id", "error", "message", "fee", "tx_hash"};

	@Test
	public void randomTextIsHandled() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] b = new byte[random.nextInt(300)];
			random.nextBytes(b);
			for (int j = 0; j < b.length; j++) b[j] = (byte) (b[j] & 0x7F);
			feed(new String(b, java.nio.charset.StandardCharsets.US_ASCII),
					"random text " + i);
		}
	}

	@Test
	public void hostileJsonValuesAreHandled() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			feed(randomReply(), "hostile reply " + i);
		}
	}

	@Test
	public void truncationsOfAValidReplyAreHandled() {
		String reply = "{\"jsonrpc\":\"2.0\",\"result\":[{\"tx_hash\":\"" + TXID
				+ "\",\"height\":800000,\"tx_pos\":1,\"value\":5000},{\"tx_hash\":\""
				+ TXID + "\",\"height\":0,\"tx_pos\":0,\"value\":1}],\"id\":1}";
		for (int len = 0; len <= reply.length(); len++) {
			feed(reply.substring(0, len), "truncated to " + len);
		}
	}

	private String randomReply() {
		StringBuilder b = new StringBuilder("{\"id\":1,\"result\":");
		int shape = random.nextInt(4);
		if (shape == 0) {
			b.append(VALUES[random.nextInt(VALUES.length)]);
		} else {
			b.append('[');
			int n = random.nextInt(6);
			for (int i = 0; i < n; i++) {
				if (i > 0) b.append(',');
				b.append('{');
				int fields = random.nextInt(6);
				for (int f = 0; f < fields; f++) {
					if (f > 0) b.append(',');
					b.append('"').append(KEYS[random.nextInt(KEYS.length)])
							.append("\":")
							.append(VALUES[random.nextInt(VALUES.length)]);
				}
				b.append('}');
			}
			b.append(']');
		}
		b.append('}');
		if (random.nextInt(5) == 0) b.append("trailing");
		return b.toString();
	}

	private static void feed(String reply, String what) {
		try {
			List<ElectrumClient.HistItem> history =
					ElectrumClient.parseHistory(reply);
			assertTrue(what, history.size() <= ElectrumClient.MAX_LIST_ITEMS);
			for (ElectrumClient.HistItem h : history) {
				assertTrue(what + ": " + h.txHash, ElectrumClient.isTxid(h.txHash));
			}
			List<ElectrumClient.Utxo> unspent = ElectrumClient.parseUnspent(reply);
			assertTrue(what, unspent.size() <= ElectrumClient.MAX_LIST_ITEMS);
			for (ElectrumClient.Utxo u : unspent) {
				assertTrue(what + ": " + u.txHash, ElectrumClient.isTxid(u.txHash));
				assertTrue(what, u.value >= 0);
				assertTrue(what, u.txPos >= 0);
			}
			double fee = ElectrumClient.parseFee(reply);
			assertTrue(what + ": fee " + fee, !Double.isNaN(fee)
					&& !Double.isInfinite(fee) && fee >= 0);
		} catch (RuntimeException e) {
			throw new AssertionError(what + ": " + e, e);
		}
		try {
			String txid = ElectrumClient.parseBroadcast(reply);
			assertTrue(what, ElectrumClient.isTxid(txid));
		} catch (IOException expected) {
		} catch (RuntimeException e) {
			throw new AssertionError(what + " (broadcast): " + e, e);
		}
	}
}
