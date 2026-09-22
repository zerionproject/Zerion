package com.professor.zerion.android.vault.ui;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

/**
 * A2-REG-BTC-01: the sweep plan, which carries spend-capable keys, must be
 * dropped wherever the send plan is dropped: a failed credential, a wallet
 * close and the section reset that closes the wallet. The ViewModel has no
 * unit harness, so the wiring is checked at the source level.
 */
public class VaultViewModelPlanClearingTest {

	private static final String SRC =
			"src/main/java/com/professor/zerion/android/vault/ui/VaultViewModel.java";

	@Test
	public void bothGatesAreClearedOnCredentialFailureAndWalletClose()
			throws Exception {
		String s = new String(Files.readAllBytes(Paths.get(SRC)),
				StandardCharsets.UTF_8);
		String drop = body(s, "void dropReviewedPlans()");
		assertTrue(drop.contains("sendGate.clear()"));
		assertTrue(drop.contains("spSweepGate.clear()"));
		assertTrue(body(s, "public void closeBtcWallet()")
				.contains("dropReviewedPlans()"));
		String verify = body(s,
				"private synchronized boolean verifyWalletCredential(");
		int failure = verify.indexOf("recordFailure()");
		assertTrue(failure > 0);
		assertTrue(verify.indexOf("dropReviewedPlans()", failure) > 0);
		String open = body(s, "public void openBtcWallet(");
		int switching = open.indexOf("openBtc = w;");
		assertTrue(switching > 0);
		assertTrue("a wallet switch drops both plans",
				open.lastIndexOf("dropReviewedPlans()", switching) > 0);
	}

	private static String body(String s, String signature) {
		int i = s.indexOf(signature);
		assertTrue(signature, i >= 0);
		int open = s.indexOf('{', i);
		int depth = 0;
		for (int k = open; k < s.length(); k++) {
			char c = s.charAt(k);
			if (c == '{') depth++;
			if (c == '}' && --depth == 0) return s.substring(open, k + 1);
		}
		throw new AssertionError("unbalanced");
	}
}
