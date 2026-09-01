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
				false);
	}

	@Test
	public void authorizeWithoutPreparedPlanFails() {
		SendGate g = new SendGate();
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true));
	}

	@Test
	public void wrongAuthenticationBlocksSigningButKeepsPlan()
			throws SendGate.AuthorizationException {
		SendGate g = new SendGate();
		g.prepare(plan("A"));
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", false));
		BtcWallet.SendPlan p = g.authorize("A", true);
		assertEquals("A", p.fingerprint);
	}

	@Test
	public void correctAuthenticationReturnsPlanExactlyOnce()
			throws SendGate.AuthorizationException {
		SendGate g = new SendGate();
		BtcWallet.SendPlan a = plan("A");
		g.prepare(a);
		assertSame(a, g.authorize("A", true));
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true));
	}

	@Test
	public void changedTransactionInvalidatesAuthorization() {
		SendGate g = new SendGate();
		g.prepare(plan("A"));
		g.prepare(plan("B"));
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true));
		assertNull(g.pending());
	}

	@Test
	public void clearInvalidatesAuthorization() {
		SendGate g = new SendGate();
		g.prepare(plan("A"));
		g.clear();
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true));
	}
}
