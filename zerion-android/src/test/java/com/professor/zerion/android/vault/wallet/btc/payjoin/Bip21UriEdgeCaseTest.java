package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Bip21UriEdgeCaseTest {

	private static final String ADDR = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";

	@Test
	public void schemeAndAmountParsing() {
		assertEquals(50_000_000L, PayjoinUri.detect("BITCOIN:" + ADDR
				+ "?amount=0.5").amountSat);
		assertEquals(1L, PayjoinUri.detect("bitcoin:" + ADDR
				+ "?amount=0.00000001").amountSat);
		assertEquals(2_100_000_000_000_000L, PayjoinUri.detect("bitcoin:" + ADDR
				+ "?amount=21000000").amountSat);
		assertEquals(ADDR, PayjoinUri.detect("bitcoin:" + ADDR
				+ "?label=caf%C3%A9&message=hi%20there&amount=1").address);
	}

	@Test
	public void unusableAmountsYieldNoAmount() {
		String[] amounts = {"-1", "0.000000001", "1e-9",
				"99999999999999999999", "NaN", "Infinity", "abc", "", "0x10",
				"1,5"};
		for (String a : amounts) {
			PayjoinUri uri = PayjoinUri.detect("bitcoin:" + ADDR + "?amount=" + a);
			assertEquals(a, 0L, uri.amountSat);
			assertEquals(a, ADDR, uri.address);
		}
	}

	@Test
	public void anUnknownRequiredParameterMakesTheUriMalformed() {
		assertEquals(PayjoinUri.Kind.MALFORMED, PayjoinUri.detect("bitcoin:"
				+ ADDR + "?amount=1&req-somethingyouneed=1").kind);
		assertEquals(PayjoinUri.Kind.MALFORMED, PayjoinUri.detect("bitcoin:"
				+ ADDR + "?REQ-X=1&pj=https://example.org/pj").kind);
		assertEquals(PayjoinUri.Kind.NORMAL, PayjoinUri.detect("bitcoin:"
				+ ADDR + "?amount=1&somethingoptional=1").kind);
	}

	@Test
	public void randomInputNeverThrows() {
		Random random = new Random(59);
		String[] parts = {"bitcoin:", "BITCOIN:", ADDR, "?", "&", "=",
				"amount", "pj", "pjos", "req-", "label", "%", "%2", "%ZZ",
				"https://", "1e400", "-", ".", "0", "\0", " ", "#"};
		for (int i = 0; i < 5000; i++) {
			StringBuilder sb = new StringBuilder();
			int n = random.nextInt(12);
			for (int j = 0; j < n; j++) sb.append(parts[random.nextInt(parts.length)]);
			PayjoinUri uri = PayjoinUri.detect(sb.toString());
			assertTrue(uri.amountSat >= 0);
			assertTrue(uri.kind != null);
		}
	}
}
