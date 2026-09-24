package com.professor.zerion.android.vault.wallet.xmr;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The native history arrives as text lines the wallet parses. Lines with
 * hostile fields (wrong counts, uppercase or short hashes, signs, values
 * beyond 64 bits, decimals, words, flags outside 0 and 1, random text) are
 * dropped or parsed, never thrown on, and every parsed row carries a
 * lowercase 64-character hash, a known direction and a non-negative
 * timestamp.
 */
public class XmrTxInfoParseFuzzTest {

	private static final int RANDOM_INPUTS = 8000;
	private static final String TXID =
			"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	private final Random random = new Random(67);

	private static final String[] FIELDS = {TXID, TXID.toUpperCase(),
			TXID.substring(1), TXID + "0", "0", "1", "2", "-1", "+5",
			"18446744073709551615", "18446744073709551616", "1.0", "1e3",
			"", " ", "abc", "NaN", "9223372036854775807",
			"9223372036854775808", "\0", "0x10"};

	@Test
	public void randomLinesAreDroppedOrParsedNeverThrown() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			StringBuilder sb = new StringBuilder();
			int n = random.nextInt(15);
			for (int j = 0; j < n; j++) {
				if (j > 0) sb.append(',');
				sb.append(FIELDS[random.nextInt(FIELDS.length)]);
			}
			String line = sb.toString();
			XmrTxInfo tx;
			try {
				tx = XmrTxInfo.parse(line);
			} catch (RuntimeException e) {
				throw new AssertionError(line + ": " + e, e);
			}
			if (tx == null) continue;
			assertEquals(line, 64, tx.txid.length());
			assertTrue(line, tx.txid.matches("[0-9a-f]{64}"));
			assertTrue(line, tx.direction == XmrTxInfo.Direction.IN
					|| tx.direction == XmrTxInfo.Direction.OUT);
			assertTrue(line, tx.timestamp >= 0);
		}
	}

	@Test
	public void randomTextIsDroppedNeverThrown() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			byte[] b = new byte[random.nextInt(200)];
			random.nextBytes(b);
			String line = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
			try {
				XmrTxInfo.parse(line);
			} catch (RuntimeException e) {
				throw new AssertionError(i + ": " + e, e);
			}
		}
	}
}
