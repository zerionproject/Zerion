package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class SendGateTest {

	private static BtcWallet.SendPlan plan(String fp) {
		return new BtcWallet.SendPlan("bc1qdest", 100, 10, -110, false,
				Arrays.asList("t:0"), fp, new ArrayList<>(), new ArrayList<>(),
				new ArrayList<>(), false, null, new java.util.HashSet<>(),
				false, 110, -1);
	}

	/** BTC-07: a plan is released only to the wallet it was prepared for. */
	@Test
	public void planFromAnotherWalletIsRefusedAndCleared() {
		SendGate g = new SendGate();
		g.prepare(plan("A"), "wallet-1");
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, "wallet-2"));
		assertNull("a wallet switch drops the plan", g.pending());
		g.prepare(plan("A"), "wallet-1");
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, null));
	}

	@Test
	public void authorizeWithoutPreparedPlanFails() {
		SendGate g = new SendGate();
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, "w"));
	}

	@Test
	public void wrongAuthenticationBlocksSigningButKeepsPlan()
			throws SendGate.AuthorizationException {
		SendGate g = new SendGate();
		g.prepare(plan("A"), "w");
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", false, "w"));
		BtcWallet.SendPlan p = g.authorize("A", true, "w");
		assertEquals("A", p.fingerprint);
	}

	@Test
	public void correctAuthenticationReturnsPlanExactlyOnce()
			throws SendGate.AuthorizationException {
		SendGate g = new SendGate();
		BtcWallet.SendPlan a = plan("A");
		g.prepare(a, "w");
		assertSame(a, g.authorize("A", true, "w"));
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, "w"));
	}

	@Test
	public void changedTransactionInvalidatesAuthorization() {
		SendGate g = new SendGate();
		g.prepare(plan("A"), "w");
		g.prepare(plan("B"), "w");
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, "w"));
		assertNull(g.pending());
	}

	@Test
	public void clearInvalidatesAuthorization() {
		SendGate g = new SendGate();
		g.prepare(plan("A"), "w");
		g.clear();
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, "w"));
	}
}
