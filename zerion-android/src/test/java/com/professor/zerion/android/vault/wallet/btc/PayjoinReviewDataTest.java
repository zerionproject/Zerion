package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyAnalyzer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

public class PayjoinReviewDataTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static PayjoinSender.Review reviewWith(PrivacyAnalyzer.Analysis a) {
		String changeAddr = BtcKeys.changeAddress(MNEMONIC, 0, 0);
		List<PayjoinFinalTx.Entry> entries = new ArrayList<>(Arrays.asList(
				PayjoinFinalTx.Entry.owned(TX0, 0, 100000, 0xfffffffdL,
						BtcKeys.receiveKey(MNEMONIC, 0, 0))));
		List<BtcTx.Output> outputs = new ArrayList<>(Arrays.asList(
				new BtcTx.Output(DEST, 60000),
				new BtcTx.Output(changeAddr, 54000)));
		PayjoinFinalTx tx = new PayjoinFinalTx(entries, outputs, 2, 0, DEST,
				60000, changeAddr, 54000, 6000, 1.0);
		return new PayjoinSender.Review(true, PayjoinSender.Reject.OK, tx, a,
				new LinkedHashSet<>(), new LinkedHashSet<>());
	}

	@Test
	public void reviewUsesFinalTxFingerprintAndFee() {
		PayjoinSender.Review r = reviewWith(PrivacyAnalyzer.Analysis.unavailable());
		PayjoinReviewData d = PayjoinReviewData.from(r, DEST, 40000);
		assertEquals(DEST, d.recipient);
		assertEquals(40000, d.amountSat);
		assertEquals(6000, d.feeSat);
		assertEquals(46000, d.totalSat);
		assertTrue(d.payjoin);
		assertEquals(r.finalTx.fingerprint(), d.fingerprint);
	}

	@Test
	public void reviewCarriesFinalPrivacyAnalysis() {
		PrivacyAnalyzer.Analysis a = new PrivacyAnalyzer.Analysis(
				PrivacyAnalyzer.Level.MEDIUM, new ArrayList<>());
		PayjoinReviewData d = PayjoinReviewData.from(reviewWith(a), DEST, 40000);
		assertSame(a, d.analysis);
		assertEquals(PrivacyAnalyzer.Level.MEDIUM, d.analysis.level);
	}
}
