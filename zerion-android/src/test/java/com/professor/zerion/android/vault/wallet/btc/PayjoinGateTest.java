package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PayjoinGateTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static PayjoinFinalTx tx(long changeSat) {
		String changeAddr = BtcKeys.changeAddress(MNEMONIC, 0, 0);
		List<PayjoinFinalTx.Entry> entries = new ArrayList<>(Arrays.asList(
				PayjoinFinalTx.Entry.owned(TX0, 0, 100000, 0xfffffffdL,
						BtcKeys.receiveKey(MNEMONIC, 0, 0))));
		List<BtcTx.Output> outputs = new ArrayList<>(Arrays.asList(
				new BtcTx.Output(DEST, 60000),
				new BtcTx.Output(changeAddr, changeSat)));
		return new PayjoinFinalTx(entries, outputs, 2, 0, DEST, 60000,
				changeAddr, changeSat, 6000, 1.0);
	}

	@Test
	public void authorizeSucceedsOnMatchingFingerprint() throws Exception {
		PayjoinGate gate = new PayjoinGate();
		PayjoinFinalTx t = tx(54000);
		gate.prepare(t);
		assertSame(t, gate.authorize(t.fingerprint(), true));
	}

	@Test
	public void wrongFingerprintClearsAndThrows() {
		PayjoinGate gate = new PayjoinGate();
		gate.prepare(tx(54000));
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> gate.authorize("deadbeef", true));
		assertNull(gate.pending());
	}

	@Test
	public void postAuthMutationInvalidatesAuthorization() {
		PayjoinGate gate = new PayjoinGate();
		gate.prepare(tx(54000));
		PayjoinFinalTx mutated = tx(53000);
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> gate.authorize(mutated.fingerprint(), true));
		assertNull(gate.pending());
	}

	@Test
	public void notAuthenticatedKeepsPending() {
		PayjoinGate gate = new PayjoinGate();
		PayjoinFinalTx t = tx(54000);
		gate.prepare(t);
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> gate.authorize(t.fingerprint(), false));
		assertEquals(t, gate.pending());
	}

	@Test
	public void singleUseCannotReplay() throws Exception {
		PayjoinGate gate = new PayjoinGate();
		PayjoinFinalTx t = tx(54000);
		gate.prepare(t);
		gate.authorize(t.fingerprint(), true);
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> gate.authorize(t.fingerprint(), true));
	}
}
