package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * The sweep gate follows the send gate: one reviewed plan, released once,
 * only to the wallet it was planned for and the fingerprint the user saw.
 */
public class SpSweepGateTest {

	private static BtcWallet.SpSweepPlan plan(String fp) {
		return new BtcWallet.SpSweepPlan("bc1qdest", 100, 10,
				Arrays.asList("t:0"), fp, new ArrayList<>(), new ArrayList<>(),
				-110);
	}

	@Test
	public void planFromAnotherWalletIsRefusedAndCleared() {
		SpSweepGate g = new SpSweepGate();
		g.prepare(plan("A"), "wallet-1");
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, "wallet-2"));
		assertNull(g.pending());
	}

	@Test
	public void correctAuthenticationReturnsPlanExactlyOnce()
			throws SendGate.AuthorizationException {
		SpSweepGate g = new SpSweepGate();
		BtcWallet.SpSweepPlan a = plan("A");
		g.prepare(a, "w");
		assertSame(a, g.authorize("A", true, "w"));
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, "w"));
	}

	@Test
	public void changedSweepInvalidatesAuthorization() {
		SpSweepGate g = new SpSweepGate();
		g.prepare(plan("A"), "w");
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("B", true, "w"));
		assertNull(g.pending());
	}

	@Test
	public void clearDropsThePlan() {
		SpSweepGate g = new SpSweepGate();
		g.prepare(plan("A"), "w");
		g.clear();
		assertNull(g.pending());
		assertThrows(SendGate.AuthorizationException.class,
				() -> g.authorize("A", true, "w"));
	}
}
