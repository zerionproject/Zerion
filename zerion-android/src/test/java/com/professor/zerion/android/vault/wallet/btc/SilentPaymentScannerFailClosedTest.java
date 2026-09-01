package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class SilentPaymentScannerFailClosedTest {

	private static final String HEX64 =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String ORACLE = "https://oracle.example.org";

	private static SilentPaymentScanner.Fetcher fixed(String tweaks,
			String utxos) {
		return (url, tag) -> {
			if (url.contains("/tweaks/")) {
				return tweaks;
			}
			if (url.contains("/utxos/")) {
				return utxos;
			}
			return null;
		};
	}

	@Test
	public void nullTweaksThrows() {
		assertThrows(IOException.class, () -> SilentPaymentScanner.scanBlock(
				ORACLE, 800000, new byte[32], new byte[33], "w",
				fixed(null, "[]")));
	}

	@Test
	public void nonJsonTweaksThrows() {
		assertThrows(IOException.class, () -> SilentPaymentScanner.scanBlock(
				ORACLE, 800000, new byte[32], new byte[33], "w",
				fixed("<html>502 Bad Gateway</html>", "[]")));
	}

	@Test
	public void validEmptyTweaksReturnsEmptyNoThrow() throws IOException {
		assertTrue(SilentPaymentScanner.scanBlock(ORACLE, 800000, new byte[32],
				new byte[33], "w", fixed("[]", "[]")).isEmpty());
	}

	@Test
	public void tweaksPresentButUtxosNullThrows() {
		String tweaks = "[\"02" + HEX64 + "\"]";
		assertThrows(IOException.class, () -> SilentPaymentScanner.scanBlock(
				ORACLE, 800000, new byte[32], new byte[33], "w",
				fixed(tweaks, null)));
	}

	@Test
	public void tweaksPresentButUtxosNonJsonThrows() {
		String tweaks = "[\"02" + HEX64 + "\"]";
		assertThrows(IOException.class, () -> SilentPaymentScanner.scanBlock(
				ORACLE, 800000, new byte[32], new byte[33], "w",
				fixed(tweaks, "gateway timeout")));
	}

	@Test
	public void malformedUtxoObjectIsSkippedNotCrash() throws IOException {
		String tweaks = "[\"02" + HEX64 + "\"]";
		String utxos = "[{\"txid\":\"" + HEX64 + "\",\"vout\":0,"
				+ "\"value\":1000,\"scriptpubkey\":\"5120" + HEX64 + "\"}]";
		assertTrue(SilentPaymentScanner.scanBlock(ORACLE, 800000, new byte[32],
				new byte[33], "w", fixed(tweaks, utxos)).isEmpty());
	}

	@Test
	public void tipHeightParsesWhenPresent() {
		SilentPaymentScanner.Fetcher f =
				(url, tag) -> "{\"block_height\":812345}";
		Integer tip = SilentPaymentScanner.tipHeight(ORACLE, "w", f);
		assertEquals(Integer.valueOf(812345), tip);
	}

	@Test
	public void tipHeightNullOnMissingField() {
		SilentPaymentScanner.Fetcher f = (url, tag) -> "{\"unexpected\":1}";
		assertNull(SilentPaymentScanner.tipHeight(ORACLE, "w", f));
	}
}
