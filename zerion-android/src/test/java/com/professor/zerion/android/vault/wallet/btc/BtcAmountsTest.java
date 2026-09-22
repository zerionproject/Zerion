package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Amount bounds: only a positive amount up to the total supply is sendable,
 * the parser refuses negatives, overflow and text, truncates beyond eight
 * decimals, and random text never throws.
 */
public class BtcAmountsTest {

	@Test
	public void sendableIsPositiveAndAtMostTheSupply() {
		assertFalse(BtcAmounts.sendable(0));
		assertFalse(BtcAmounts.sendable(-1));
		assertFalse(BtcAmounts.sendable(Long.MIN_VALUE));
		assertTrue(BtcAmounts.sendable(1));
		assertTrue(BtcAmounts.sendable(BtcAmounts.MAX_MONEY_SAT));
		assertFalse(BtcAmounts.sendable(BtcAmounts.MAX_MONEY_SAT + 1));
		assertFalse(BtcAmounts.sendable(Long.MAX_VALUE));
		assertEquals(5, BtcAmounts.bounded(5));
		assertEquals(0, BtcAmounts.bounded(0));
		assertEquals(-1, BtcAmounts.bounded(-5));
		assertEquals(-1, BtcAmounts.bounded(BtcAmounts.MAX_MONEY_SAT + 1));
	}

	@Test
	public void parsingIsBoundedAndTruncates() {
		assertEquals(1, BtcAmounts.parseBtc("0.00000001"));
		assertEquals(100_000_000L, BtcAmounts.parseBtc(" 1 "));
		assertEquals(12_345_678L, BtcAmounts.parseBtc("0.123456789"));
		assertEquals(BtcAmounts.MAX_MONEY_SAT, BtcAmounts.parseBtc("21000000"));
		assertEquals(-1, BtcAmounts.parseBtc("21000000.00000001"));
		assertEquals(-1, BtcAmounts.parseBtc("-0.5"));
		assertEquals(-1, BtcAmounts.parseBtc("99999999999999999999"));
		assertEquals(-1, BtcAmounts.parseBtc("1e400"));
		assertEquals(-1, BtcAmounts.parseBtc("1e999999999"));
		assertEquals(0, BtcAmounts.parseBtc("1e-999999999"));
		assertEquals(0, BtcAmounts.parseBtc("0.000000009"));
		assertEquals(-1, BtcAmounts.parseBtc("abc"));
		assertEquals(-1, BtcAmounts.parseBtc(""));
		assertEquals(-1, BtcAmounts.parseBtc("NaN"));
		assertEquals(-1, BtcAmounts.parseBtc("0x10"));
		assertEquals(0, BtcAmounts.parseBtc("0"));
	}

	@Test
	public void randomTextNeverThrows() {
		Random random = new Random(103);
		String alphabet = "0123456789.-+eE, x";
		for (int i = 0; i < 20_000; i++) {
			StringBuilder sb = new StringBuilder();
			int n = random.nextInt(30);
			for (int j = 0; j < n; j++) {
				sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
			}
			long sat = BtcAmounts.parseBtc(sb.toString());
			assertTrue(sb.toString(),
					sat == -1 || (sat >= 0 && sat <= BtcAmounts.MAX_MONEY_SAT));
		}
	}
}
