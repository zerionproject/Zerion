package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The Send button gate: precondition blocks take priority, address validity is
 * the parser's, and the amount uses the exact converter. It only decides whether
 * the user may continue; the flow re-checks everything before construction.
 */
public class XmrSendInputTest {

	private static final String ADDR =
			"42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm";

	private final FakeMoneroEngine engine = new FakeMoneroEngine();

	private XmrSendInput eval(String addr, String amount, boolean synced,
			long unlocked, boolean quarantined, boolean busy) {
		return XmrSendInput.evaluate(engine, addr, amount, synced, unlocked,
				quarantined, busy);
	}

	@Test
	public void validInputCanContinue() {
		XmrSendInput r = eval(ADDR, "0.5", true, 1_000_000_000_000L, false,
				false);
		assertTrue(r.canContinue());
		assertEquals(XmrSendInput.Block.NONE, r.block);
		assertTrue(r.addressValid);
		assertEquals(500_000_000_000L, r.amountAtomic);
	}

	@Test
	public void quarantineBlocksAboveEverything() {
		XmrSendInput r = eval(ADDR, "0.5", true, 1_000_000_000_000L, true,
				false);
		assertEquals(XmrSendInput.Block.QUARANTINED, r.block);
		assertFalse(r.canContinue());
	}

	@Test
	public void busyBlocks() {
		assertEquals(XmrSendInput.Block.BUSY,
				eval(ADDR, "0.5", true, 1_000_000_000_000L, false, true).block);
	}

	@Test
	public void notSyncedBlocks() {
		assertEquals(XmrSendInput.Block.NOT_SYNCED,
				eval(ADDR, "0.5", false, 1_000_000_000_000L, false, false).block);
	}

	@Test
	public void noBalanceBlocks() {
		assertEquals(XmrSendInput.Block.NO_BALANCE,
				eval(ADDR, "0.5", true, 0, false, false).block);
	}

	@Test
	public void badAddressBlocks() {
		assertEquals(XmrSendInput.Block.BAD_ADDRESS,
				eval("garbage", "0.5", true, 1_000_000_000_000L, false,
						false).block);
	}

	@Test
	public void badAmountBlocks() {
		assertEquals(XmrSendInput.Block.BAD_AMOUNT,
				eval(ADDR, "not-a-number", true, 1_000_000_000_000L, false,
						false).block);
		assertEquals(XmrSendInput.Block.BAD_AMOUNT,
				eval(ADDR, "0", true, 1_000_000_000_000L, false, false).block);
	}

	@Test
	public void insufficientFundsBlocks() {
		assertEquals(XmrSendInput.Block.INSUFFICIENT_FUNDS,
				eval(ADDR, "2", true, 1_000_000_000_000L, false, false).block);
	}
}
