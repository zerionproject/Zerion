package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinSession;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyAnalyzer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

public class PayjoinFlowControllerTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static PayjoinFinalTx finalTx(long changeSat) {
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

	private static PayjoinSender.Review review(PayjoinFinalTx tx) {
		return new PayjoinSender.Review(true, PayjoinSender.Reject.OK, tx,
				PrivacyAnalyzer.Analysis.unavailable(),
				new LinkedHashSet<>(Arrays.asList(TX0 + ":0")),
				new LinkedHashSet<>());
	}

	private static PayjoinFlowController atReview(PayjoinFinalTx tx) {
		PayjoinFlowController c = new PayjoinFlowController();
		c.beginPreparing();
		c.beginConnectingTor();
		c.beginNegotiating();
		c.beginValidating();
		c.ready(review(tx));
		return c;
	}

	@Test
	public void statesAdvanceInOrder() {
		PayjoinFlowController c = new PayjoinFlowController();
		assertEquals(PayjoinFlowController.State.IDLE, c.state());
		c.beginPreparing();
		assertEquals(PayjoinFlowController.State.PREPARING, c.state());
		c.beginConnectingTor();
		assertEquals(PayjoinFlowController.State.CONNECTING_TOR, c.state());
		c.beginNegotiating();
		assertEquals(PayjoinFlowController.State.NEGOTIATING, c.state());
		c.beginValidating();
		assertEquals(PayjoinFlowController.State.VALIDATING, c.state());
		c.ready(review(finalTx(54000)));
		assertEquals(PayjoinFlowController.State.READY_FOR_REVIEW, c.state());
	}

	@Test
	public void invalidTransitionThrows() {
		PayjoinFlowController c = new PayjoinFlowController();
		assertThrows(IllegalStateException.class, c::beginNegotiating);
	}

	@Test
	public void reviewExposesFinalTransactionFingerprint() {
		PayjoinFinalTx tx = finalTx(54000);
		PayjoinFlowController c = atReview(tx);
		assertEquals(tx.fingerprint(), c.review().finalTx.fingerprint());
	}

	@Test
	public void authenticationOnlyInReviewState() {
		PayjoinFlowController c = new PayjoinFlowController();
		c.beginPreparing();
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> c.authorize("x", true));
	}

	@Test
	public void authenticationBoundToFinalFingerprint() throws Exception {
		PayjoinFinalTx tx = finalTx(54000);
		PayjoinFlowController c = atReview(tx);
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> c.authorize("wrong-fingerprint", true));
		PayjoinFlowController c2 = atReview(tx);
		assertSame(tx, c2.authorize(tx.fingerprint(), true));
	}

	@Test
	public void mutationInvalidatesAuthorization() {
		PayjoinFinalTx tx1 = finalTx(54000);
		PayjoinFlowController c = atReview(tx1);
		c.beginValidating();
		c.ready(review(finalTx(53000)));
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> c.authorize(tx1.fingerprint(), true));
	}

	@Test
	public void cancellationDestroysSession() {
		PayjoinFlowController c = new PayjoinFlowController();
		c.beginPreparing();
		c.beginConnectingTor();
		c.beginNegotiating();
		c.cancel();
		assertEquals(PayjoinFlowController.State.CANCELLED, c.state());
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> c.authorize("x", true));
	}

	@Test
	public void interruptionDuringNegotiationFailsSafe() {
		PayjoinFlowController c = new PayjoinFlowController();
		c.beginPreparing();
		c.beginConnectingTor();
		c.beginNegotiating();
		c.onInterrupted();
		assertEquals(PayjoinFlowController.State.CANCELLED, c.state());
	}

	@Test
	public void failureNeverAutoSendsAndClearsAuth() {
		PayjoinFlowController c = new PayjoinFlowController();
		c.beginPreparing();
		c.beginConnectingTor();
		c.beginNegotiating();
		PayjoinOutcomeRouter.Action a =
				c.fail(PayjoinSession.Status.FAILED);
		assertEquals(PayjoinOutcomeRouter.Action.OFFER_NORMAL_FALLBACK, a);
		assertEquals(PayjoinFlowController.State.FAILED, c.state());
		assertThrows(PayjoinGate.AuthorizationException.class,
				() -> c.authorize("x", true));
	}

	@Test
	public void nativeFailureProducesSafeFailureState() {
		PayjoinFlowController c = new PayjoinFlowController();
		c.beginPreparing();
		c.beginConnectingTor();
		c.beginNegotiating();
		c.beginValidating();
		PayjoinOutcomeRouter.Action a =
				c.fail(PayjoinSession.Status.FAILED);
		assertEquals(PayjoinOutcomeRouter.Action.OFFER_NORMAL_FALLBACK, a);
		assertNull(c.review());
	}

	@Test
	public void retryStartsFreshSession() {
		PayjoinFlowController c = new PayjoinFlowController();
		c.beginPreparing();
		c.beginConnectingTor();
		c.beginNegotiating();
		c.fail(PayjoinSession.Status.FAILED);
		c.reset();
		assertEquals(PayjoinFlowController.State.IDLE, c.state());
		assertNull(c.review());
		c.beginPreparing();
		assertEquals(PayjoinFlowController.State.PREPARING, c.state());
	}
}
