package com.professor.zerion.android.vault.wallet.btc.payjoin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PayjoinValidatorTest {

	private static final String RECIP = "bc1qrecipient";
	private static final String CHANGE = "bc1qchange";

	private static PayjoinValidator.Policy policy() {
		return new PayjoinValidator.Policy(50000, 1.0, 1000.0, 2000, false);
	}

	private static PayjoinValidator.OriginalTx original() {
		return new PayjoinValidator.OriginalTx(Arrays.asList("a:0"), RECIP,
				40000, CHANGE, 55000);
	}

	private static PayjoinValidator.TxOut out(String addr, long v) {
		return new PayjoinValidator.TxOut(addr, v, "p2wpkh", true);
	}

	private static PayjoinValidator.ProposedTx proposed(
			List<String> inputs, List<PayjoinValidator.TxOut> outs,
			long totalIn, long vsize, int version, long locktime) {
		return new PayjoinValidator.ProposedTx(inputs, outs, totalIn, vsize,
				version, locktime);
	}

	private static PayjoinValidator.ProposedTx validProposed() {
		return proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 60000),
						out(CHANGE, 54000))), 120000, 200, 2, 0);
	}

	private static PayjoinValidator.Result run(
			PayjoinValidator.ProposedTx p) {
		return PayjoinValidator.validate(original(), p, policy());
	}

	@Test
	public void validProposalAccepted() {
		PayjoinValidator.Result r = run(validProposed());
		assertTrue(r.ok);
		assertEquals(PayjoinValidator.Reason.OK, r.reason);
		assertEquals(6000, r.feeSat);
		assertEquals(54000, r.ourChangeSat);
	}

	@Test
	public void recipientOutputChangedRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out("bc1qattacker", 60000),
						out(CHANGE, 54000))), 120000, 200, 2, 0));
		assertFalse(r.ok);
		assertEquals(PayjoinValidator.Reason.RECIPIENT_MISSING, r.reason);
	}

	@Test
	public void recipientAmountReducedRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 30000),
						out(CHANGE, 54000))), 120000, 200, 2, 0));
		assertEquals(PayjoinValidator.Reason.RECIPIENT_REDUCED, r.reason);
	}

	@Test
	public void maliciousExtraOutputRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 60000),
						out(CHANGE, 54000), out("bc1qattacker", 5000))), 125000,
				200, 2, 0));
		assertEquals(PayjoinValidator.Reason.UNEXPECTED_OUTPUT, r.reason);
	}

	@Test
	public void excessiveFeeRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 60000),
						out(CHANGE, 54000))), 200000, 200, 2, 0));
		assertEquals(PayjoinValidator.Reason.FEE_TOO_HIGH, r.reason);
	}

	@Test
	public void feerateOutOfBoundsRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 60000),
						out(CHANGE, 54000))), 120000, 1, 2, 0));
		assertEquals(PayjoinValidator.Reason.FEERATE_OUT_OF_BOUNDS, r.reason);
	}

	@Test
	public void malformedProposalRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(), 120000, 200, 2, 0));
		assertEquals(PayjoinValidator.Reason.MALFORMED, r.reason);
	}

	@Test
	public void wrongNetworkRejected() {
		PayjoinValidator.ProposedTx p = proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(
						new PayjoinValidator.TxOut(RECIP, 60000, "p2wpkh", false),
						out(CHANGE, 54000))), 120000, 200, 2, 0);
		assertEquals(PayjoinValidator.Reason.WRONG_NETWORK, run(p).reason);
	}

	@Test
	public void unsupportedScriptRejected() {
		PayjoinValidator.ProposedTx p = proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(
						new PayjoinValidator.TxOut(RECIP, 60000, "p2sh", true),
						out(CHANGE, 54000))), 120000, 200, 2, 0);
		assertEquals(PayjoinValidator.Reason.UNSUPPORTED_SCRIPT, run(p).reason);
	}

	@Test
	public void walletInputSubstitutionRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 60000),
						out(CHANGE, 54000))), 120000, 200, 2, 0));
		assertEquals(PayjoinValidator.Reason.OUR_INPUT_MISSING, r.reason);
	}

	@Test
	public void walletChangeCorrectlyIdentified() {
		PayjoinValidator.Result r = run(validProposed());
		assertEquals(54000, r.ourChangeSat);
	}

	@Test
	public void changeMissingRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 60000))), 66000, 200,
				2, 0));
		assertEquals(PayjoinValidator.Reason.CHANGE_MISSING, r.reason);
	}

	@Test
	public void changeReducedTooMuchRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 60000),
						out(CHANGE, 50000))), 120000, 200, 2, 0));
		assertEquals(PayjoinValidator.Reason.CHANGE_REDUCED_TOO_MUCH, r.reason);
	}

	@Test
	public void badVersionRejected() {
		PayjoinValidator.Result r = run(proposed(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(out(RECIP, 60000),
						out(CHANGE, 54000))), 120000, 200, 3, 0));
		assertEquals(PayjoinValidator.Reason.BAD_VERSION_OR_LOCKTIME, r.reason);
	}
}
