package com.professor.zerion.android.vault.wallet.btc.payjoin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

public class PayjoinSessionTest {

	private static final String RECIP = "bc1qrecipient";
	private static final String CHANGE = "bc1qchange";

	private static PayjoinValidator.OriginalTx original() {
		return new PayjoinValidator.OriginalTx(Arrays.asList("a:0"), RECIP,
				40000, CHANGE, 55000);
	}

	private static PayjoinValidator.Policy policy() {
		return new PayjoinValidator.Policy(50000, 1.0, 1000.0, 2000, false);
	}

	private static PayjoinValidator.ProposedTx validProposed() {
		return new PayjoinValidator.ProposedTx(Arrays.asList("a:0", "b:0"),
				new ArrayList<>(Arrays.asList(
						new PayjoinValidator.TxOut(RECIP, 60000, "p2wpkh", true),
						new PayjoinValidator.TxOut(CHANGE, 54000, "p2wpkh",
								true))), 120000, 200, 2, 0);
	}

	private static PayjoinSession.Outcome run(PayjoinTransport t,
			PayjoinSession.ProposalParser p) {
		return new PayjoinSession(t, p).requestAndValidate("bitcoin:x?pj=y",
				new byte[]{1}, 9999, "walletA-pj", original(), policy());
	}

	@Test
	public void validProposalValidated() {
		PayjoinSession.Outcome o = run(
				(uri, req, port, tag) -> new byte[]{9},
				raw -> validProposed());
		assertEquals(PayjoinSession.Status.VALIDATED, o.status);
	}

	@Test
	public void sameProposalCannotBeProcessedTwice() {
		PayjoinSession s = new PayjoinSession(
				(uri, req, port, tag) -> new byte[]{9}, raw -> validProposed());
		PayjoinSession.Outcome first = s.requestAndValidate("u", new byte[]{1},
				9999, "t", original(), policy());
		PayjoinSession.Outcome second = s.requestAndValidate("u", new byte[]{1},
				9999, "t", original(), policy());
		assertEquals(PayjoinSession.Status.VALIDATED, first.status);
		assertEquals(PayjoinSession.Status.REPLAYED, second.status);
	}

	@Test
	public void torOrRelayUnavailableFailsClosed() {
		PayjoinSession.Outcome o = run((uri, req, port, tag) -> null,
				raw -> validProposed());
		assertEquals(PayjoinSession.Status.FAILED, o.status);
		assertNull(o.proposed);
	}

	@Test
	public void transportExceptionFailsClosed() {
		PayjoinSession.Outcome o = run((uri, req, port, tag) -> {
			throw new RuntimeException("timeout");
		}, raw -> validProposed());
		assertEquals(PayjoinSession.Status.FAILED, o.status);
	}

	@Test
	public void malformedProposalRejected() {
		PayjoinSession.Outcome o = run((uri, req, port, tag) -> new byte[]{9},
				raw -> {
					throw new RuntimeException("bad psbt");
				});
		assertEquals(PayjoinSession.Status.REJECTED, o.status);
		assertEquals(PayjoinValidator.Reason.MALFORMED, o.reason);
	}

	@Test
	public void invalidProposalRejectedWithReason() {
		PayjoinSession.Outcome o = run((uri, req, port, tag) -> new byte[]{9},
				raw -> new PayjoinValidator.ProposedTx(Arrays.asList("b:0"),
						new ArrayList<>(Arrays.asList(
								new PayjoinValidator.TxOut(RECIP, 60000, "p2wpkh",
										true))), 66000, 200, 2, 0));
		assertEquals(PayjoinSession.Status.REJECTED, o.status);
		assertEquals(PayjoinValidator.Reason.OUR_INPUT_MISSING, o.reason);
	}

	@Test
	public void failedPayjoinIsNotValidated() {
		PayjoinSession.Outcome o = run((uri, req, port, tag) -> null,
				raw -> validProposed());
		assertNotEquals(PayjoinSession.Status.VALIDATED, o.status);
		assertNull(o.result);
	}
}
