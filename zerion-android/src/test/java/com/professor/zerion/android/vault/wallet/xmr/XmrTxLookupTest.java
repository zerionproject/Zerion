package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class XmrTxLookupTest {

	private static final String A =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String B =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
	private static final String C =
			"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

	@Test
	public void codesDecodeToTypedResultsInInputOrder() {
		List<XmrTxLookup> r = XmrTxLookup.decode(Arrays.asList(A, B, C),
				new long[]{XmrTxLookup.CODE_IN_POOL, 3_750_000L,
						XmrTxLookup.CODE_MISSED});
		assertEquals(XmrTxLookup.Result.IN_POOL, r.get(0).result);
		assertEquals(XmrTxLookup.Result.MINED, r.get(1).result);
		assertEquals(3_750_000L, r.get(1).blockHeight);
		assertEquals(XmrTxLookup.Result.MISSED, r.get(2).result);
		assertEquals(A, r.get(0).txid);
		assertEquals(C, r.get(2).txid);
	}

	@Test
	public void errorCodeIsLookupErrorNeverMissed() {
		List<XmrTxLookup> r = XmrTxLookup.decode(Arrays.asList(A),
				new long[]{XmrTxLookup.CODE_ERROR});
		assertEquals(XmrTxLookup.Result.LOOKUP_ERROR, r.get(0).result);
	}

	@Test
	public void missingOrMisSizedCodesAreLookupErrorsForEveryEntry() {
		for (XmrTxLookup x : XmrTxLookup.decode(Arrays.asList(A, B), null)) {
			assertEquals(XmrTxLookup.Result.LOOKUP_ERROR, x.result);
		}
		for (XmrTxLookup x : XmrTxLookup.decode(Arrays.asList(A, B),
				new long[]{XmrTxLookup.CODE_MISSED})) {
			assertEquals("a short answer can never become negative evidence",
					XmrTxLookup.Result.LOOKUP_ERROR, x.result);
		}
	}

	@Test
	public void malformedTxidIsLookupErrorRegardlessOfCode() {
		List<XmrTxLookup> r = XmrTxLookup.decode(
				Arrays.asList("not-a-txid", A.toUpperCase(), A.substring(1)),
				new long[]{XmrTxLookup.CODE_MISSED, XmrTxLookup.CODE_MISSED,
						XmrTxLookup.CODE_MISSED});
		for (XmrTxLookup x : r) {
			assertEquals(XmrTxLookup.Result.LOOKUP_ERROR, x.result);
		}
		assertFalse(XmrTxLookup.isTxidHex(""));
		assertTrue(XmrTxLookup.isTxidHex(A));
	}

	@Test
	public void unknownNegativeCodeIsLookupError() {
		List<XmrTxLookup> r = XmrTxLookup.decode(Arrays.asList(A),
				new long[]{-7});
		assertEquals(XmrTxLookup.Result.LOOKUP_ERROR, r.get(0).result);
	}
}
