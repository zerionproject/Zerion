package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Contract of the inspection primitives as the send flow will consume them:
 * every txid is enumerated in order and agrees with the count, dust is read
 * but never added to the debit, and a disposed handle fails closed.
 */
public class XmrEngineContractTest {

	private static final String T1 =
			"1111111111111111111111111111111111111111111111111111111111111111";
	private static final String T2 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static FakeMoneroEngine.FakePrepared prepared(String... ids) {
		FakeMoneroEngine.FakePrepared p = new FakeMoneroEngine.FakePrepared();
		p.ids.addAll(Arrays.asList(ids));
		p.count = ids.length;
		p.amount = 1_000_000_000_000L;
		p.fee = 30_000_000L;
		p.dust = 5_000L;
		return p;
	}

	@Test
	public void singleTransactionEnumeratesOneIdMatchingCount() {
		MoneroEngine.Prepared p = prepared(T1);
		assertEquals(1, p.txIds().size());
		assertEquals(T1, p.txIds().get(0));
		assertEquals(p.txCount(), p.txIds().size());
		assertEquals("legacy first-id accessor agrees", T1, p.txId());
	}

	@Test
	public void splitTransactionEnumeratesAllIdsInOrder() {
		MoneroEngine.Prepared p = prepared(T1, T2);
		assertEquals(Arrays.asList(T1, T2), p.txIds());
		assertEquals(2, p.txCount());
	}

	@Test
	public void countMismatchIsDetectable() {
		FakeMoneroEngine.FakePrepared p = prepared(T1, T2);
		p.count = 1;
		assertFalse("a caller must reject ids.size() != txCount()",
				p.txIds().size() == p.txCount());
	}

	@Test
	public void totalDebitIsAmountPlusFeeAndDustIsInsideFee() {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		long totalDebit = p.amountAtomic() + p.feeAtomic();
		assertEquals(1_000_030_000_000L, totalDebit);
		assertTrue("dust never exceeds the fee it is part of",
				p.feeAtomic() >= p.dustAtomic());
		assertFalse("dust must not be added on top",
				totalDebit == p.amountAtomic() + p.feeAtomic() + p.dustAtomic());
	}

	@Test
	public void disposedHandleFailsClosedOnEveryInspection() {
		FakeMoneroEngine.FakePrepared p = prepared(T1);
		p.close();
		assertTrue(p.txIds().isEmpty());
		assertEquals(NativeMonero.LONG_ERR, p.txCount());
		assertEquals(NativeMonero.LONG_ERR, p.dustAtomic());
		assertEquals(-1, p.feeAtomic());
		assertFalse("a disposed transaction can never be relayed", p.commit());
		assertEquals(0, p.commits);
	}

	@Test
	public void fakeAddressKindMirrorsTheNativeContractShape() {
		FakeMoneroEngine e = new FakeMoneroEngine();
		String pad = "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000";
		assertEquals(MoneroEngine.AddressKind.STANDARD, e.addressKind("4" + pad));
		assertEquals(MoneroEngine.AddressKind.SUBADDRESS, e.addressKind("8" + pad));
		assertEquals(MoneroEngine.AddressKind.INTEGRATED,
				e.addressKind("4" + pad + "00000000000"));
		assertEquals(MoneroEngine.AddressKind.INVALID, e.addressKind("garbage"));
	}

	@Test
	public void endpointIdIsStableAndCarriesTransport() {
		XmrNode tor = XmrNode.parse(
				"2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089",
				XmrNode.Source.VETTED, false);
		XmrNode direct = XmrNode.parse("203.0.113.5:18081",
				XmrNode.Source.DIRECT, false);
		assertEquals("tor:2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089",
				tor.endpointId());
		assertEquals("direct:203.0.113.5:18081", direct.endpointId());
		assertEquals(tor.endpointId(), XmrNode.parse(
				"2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089",
				XmrNode.Source.CUSTOM, false).endpointId());
	}
}
