package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

public class MalformedReplyScanTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	@Test
	public void malformedHistoryKeepsTheLastGoodScan() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 50000);
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "host",
				50001, "w", new FakeElectrum.RecordingFactory(e),
				(url, tag) -> null);
		BtcWallet.ScanResult good = w.scan();
		assertEquals(50000, good.balanceSat);
		for (String bad : new String[]{"{\"id\":1,\"result\":null}",
				"{\"id\":1,\"result\":{}}",
				"{\"id\":1,\"result\":[],\"result\":[]}"}) {
			e.rawHistoryReply = bad;
			assertThrows(bad, IOException.class, w::scan);
			assertSame("a refused reply never reads as an unused address",
					good, w.cachedScan());
		}
	}
}
