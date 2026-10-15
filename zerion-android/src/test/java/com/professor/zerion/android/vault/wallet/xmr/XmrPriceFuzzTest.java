package com.professor.zerion.android.vault.wallet.xmr;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class XmrPriceFuzzTest {

	private static final long SEED = 20261010L;
	private static final int MUTATIONS = 3000;
	private static final String KRAKEN =
			"{\"error\":[],\"result\":{\"XXMRZUSD\":{\"a\":[\"524.69000000\",\"1\","
					+ "\"1.000\"],\"b\":[\"524.27000000\",\"1\",\"1.000\"],"
					+ "\"c\":[\"523.63000000\",\"0.03000000\"],\"v\":[\"7384.26\","
					+ "\"11380.41\"],\"t\":[7272,10649],\"o\":\"518.65000000\"}}}";

	@Test
	public void theLastTradePriceIsRead() {
		assertEquals(523.63, XmrPrice.parseKrakenLast(KRAKEN), 1e-9);
	}

	@Test
	public void aResponseCutAtAnyCharacterYieldsNoPrice() {
		for (int cut = 0; cut < KRAKEN.length(); cut++) {
			assertEquals("cut at " + cut, 0,
					XmrPrice.parseKrakenLast(KRAKEN.substring(0, cut)), 0.0);
		}
	}

	@Test
	public void hostileResponsesYieldNoPrice() {
		String[] hostile = {
				KRAKEN + "x",
				KRAKEN + KRAKEN,
				KRAKEN.replace("\"error\":[]", "\"error\":[\"EGeneral:x\"]"),
				KRAKEN.replace("\"error\":[],", ""),
				KRAKEN.replace("523.63000000", "1e5"),
				KRAKEN.replace("523.63000000", "0x1p9"),
				KRAKEN.replace("523.63000000", "Infinity"),
				KRAKEN.replace("523.63000000", "NaN"),
				KRAKEN.replace("523.63000000", "-523.6"),
				KRAKEN.replace("523.63000000", " 523.6"),
				KRAKEN.replace("523.63000000", "0"),
				KRAKEN.replace("523.63000000", "99999999999999999999"),
				KRAKEN.replace("\"c\":[\"523.63000000\",", "\"c\":[523.63,"),
				KRAKEN.replace("\"c\":[\"523.63000000\",\"0.03000000\"]",
						"\"c\":[]"),
				KRAKEN.replace("}}}", "},\"XXMRZEUR\":{\"c\":[\"1.0\"]}}}"),
				KRAKEN.replace("\"result\":{", "\"result\":{\"a\":1,"),
				KRAKEN.replace("\"o\":", "\"c\":"),
				"[" + KRAKEN + "]",
				"﻿" + KRAKEN,
		};
		for (String h : hostile) {
			assertEquals(h, 0, XmrPrice.parseKrakenLast(h), 0.0);
		}
	}

	@Test
	public void mutatedResponsesNeverThrowAndStayPositiveAndFinite() {
		Random r = new Random(SEED);
		char[] alphabet = "{}[]\":,.-+eE0123456789 \\ux".toCharArray();
		for (int i = 0; i < MUTATIONS; i++) {
			StringBuilder sb = new StringBuilder(KRAKEN);
			int edits = 1 + r.nextInt(4);
			for (int e = 0; e < edits; e++) {
				int at = r.nextInt(sb.length() + 1);
				switch (r.nextInt(3)) {
					case 0:
						if (at < sb.length()) {
							sb.setCharAt(at, alphabet[r.nextInt(alphabet.length)]);
						}
						break;
					case 1:
						sb.insert(at, alphabet[r.nextInt(alphabet.length)]);
						break;
					default:
						if (at < sb.length()) sb.deleteCharAt(at);
						break;
				}
			}
			double v = XmrPrice.parseKrakenLast(sb.toString());
			assertTrue(sb.toString(), v == 0
					|| (Double.isFinite(v) && v > 0 && v < 1e15));
		}
	}
}
