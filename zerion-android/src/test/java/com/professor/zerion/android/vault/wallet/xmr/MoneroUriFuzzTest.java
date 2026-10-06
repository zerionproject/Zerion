package com.professor.zerion.android.vault.wallet.xmr;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MoneroUriFuzzTest {

	private static final int RANDOM_INPUTS = 6000;

	private final Random random = new Random(61);

	@Test
	public void randomUrisNeverThrowAnythingElse() {
		String[] parts = {"monero:", "MONERO:", "4Adk", "8B", "?", "&", "=",
				"tx_amount", "tx_description", "recipient_name", "%", "%2F",
				"%ZZ", "1", "0.5", "-1", ".", "..", "1e5", "é", " ", "\n",
				"\0", "999999999999999999999"};
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			StringBuilder sb = new StringBuilder();
			int n = random.nextInt(12);
			for (int j = 0; j < n; j++) sb.append(parts[random.nextInt(parts.length)]);
			String input = sb.toString();
			try {
				MoneroUri uri = MoneroUri.parse(input);
				if (uri != null) {
					assertTrue(input, !uri.address().isEmpty());
					assertTrue(input, uri.amountAtomic() >= -1);
					assertEquals(input, uri.amountAtomic() >= 0, uri.hasAmount());
				}
			} catch (XmrError.XmrException expected) {
			} catch (RuntimeException e) {
				throw new AssertionError(input + ": " + e, e);
			}
		}
	}

	@Test
	public void amountConversionAgreesWithExactDecimalArithmetic() {
		for (int i = 0; i < RANDOM_INPUTS; i++) {
			String input = randomAmount();
			Long expected = expectedAtomic(input);
			try {
				long got = MoneroUri.parseXmrToAtomic(input);
				if (expected == null) fail(input + " accepted as " + got);
				assertEquals(input, expected.longValue(), got);
			} catch (XmrError.XmrException e) {
				if (expected != null) {
					fail(input + " refused, expected " + expected);
				}
			} catch (RuntimeException e) {
				throw new AssertionError(input + ": " + e, e);
			}
		}
	}

	private String randomAmount() {
		StringBuilder sb = new StringBuilder();
		int whole = random.nextInt(22);
		for (int i = 0; i < whole; i++) sb.append((char) ('0' + random.nextInt(10)));
		if (random.nextBoolean()) {
			sb.append('.');
			int frac = random.nextInt(15);
			for (int i = 0; i < frac; i++) {
				sb.append((char) ('0' + random.nextInt(10)));
			}
		}
		int twist = random.nextInt(12);
		if (twist == 0) sb.insert(0, '-');
		else if (twist == 1) sb.insert(0, '+');
		else if (twist == 2) sb.append('e');
		else if (twist == 3) sb.append(' ');
		else if (twist == 4) sb.insert(0, '.');
		return sb.toString();
	}

	private static Long expectedAtomic(String s) {
		if (s.isEmpty()) return null;
		int dot = s.indexOf('.');
		String whole = dot < 0 ? s : s.substring(0, dot);
		String frac = dot < 0 ? "" : s.substring(dot + 1);
		if (whole.isEmpty() && frac.isEmpty()) return null;
		if (frac.length() > 12) return null;
		if (!whole.matches("[0-9]*") || !frac.matches("[0-9]*")) return null;
		BigDecimal value = new BigDecimal((whole.isEmpty() ? "0" : whole)
				+ (frac.isEmpty() ? "" : "." + frac));
		try {
			return value.movePointRight(12).longValueExact();
		} catch (ArithmeticException overflow) {
			return null;
		}
	}
}
