package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class XmrTxInfoTest {

	private static final String HASH =
			"a1b2c3d4e5f6071829304a5b6c7d8e9f00112233445566778899aabbccddeeff";

	@Test
	public void parsesValidIncoming() {
		XmrTxInfo t = XmrTxInfo.parse(HASH + ",0,1000000000000,0,3000000,"
				+ "1735689600,10,0,0,0");
		assertNotNull(t);
		assertEquals(HASH, t.txid);
		assertEquals(XmrTxInfo.Direction.IN, t.direction);
		assertEquals(1000000000000L, t.amountAtomic);
		assertEquals(10L, t.confirmations);
		assertEquals(false, t.pending);
		assertEquals(false, t.failed);
	}

	@Test
	public void parsesValidOutgoingPending() {
		XmrTxInfo t = XmrTxInfo.parse(HASH + ",1,500,20,0,1735689600,0,0,1,0");
		assertNotNull(t);
		assertEquals(XmrTxInfo.Direction.OUT, t.direction);
		assertEquals(20L, t.feeAtomic);
		assertEquals(true, t.pending);
	}

	@Test
	public void keepsRowWithLargeUnsignedUnlockTime() {

		XmrTxInfo t = XmrTxInfo.parse(HASH + ",0,1000000000000,0,3000000,"
				+ "1735689600,10,18446744073709551615,0,0");
		assertNotNull(t);
		assertEquals(1000000000000L, t.amountAtomic);
		assertEquals(XmrTxInfo.Direction.IN, t.direction);
	}

	@Test
	public void toleratesExtraTrailingFields() {
		XmrTxInfo t = XmrTxInfo.parse(HASH + ",0,1000,0,3000000,1735689600,"
				+ "10,0,0,0,futurefield");
		assertNotNull(t);
		assertEquals(1000L, t.amountAtomic);
	}

	@Test
	public void rejectsMalformedRows() {
		String[] bad = {
				"",
				"too,few,fields",
				HASH + ",0,1,0,0,0,0,0,0",
				"short,0,1,0,0,0,0,0,0,0",
				HASH.replace('a', 'g') + ",0,1,0,0,0,0,0,0,0",
				HASH + ",2,1,0,0,0,0,0,0,0",
				HASH + ",0,x,0,0,0,0,0,0,0",
				HASH + ",0,-5,0,0,0,0,0,0,0",
				HASH + ",0,1,0,0,0,0,0,2,0",
				HASH + ",0,1,0,0,0,0,0,0,",
		};
		for (String line : bad) {
			assertNull("must reject: " + line, XmrTxInfo.parse(line));
		}
	}
}
