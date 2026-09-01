package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;

public class BtcScanGapLimitTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String TXID =
			"2222222222222222222222222222222222222222222222222222222222222222";

	private static BtcWallet wallet(FakeElectrum e) {
		return new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
	}

	@Test
	public void sumsBalanceAcrossUsedAddressesWithinGap() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TXID, 0, 10000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 5), TXID, 1, 20000);
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(30000L, r.balanceSat);
	}

	@Test
	public void utxoBeyondGapLimitIsNotDiscovered() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TXID, 0, 10000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 40), TXID, 0, 999999);
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(10000L, r.balanceSat);
	}

	@Test
	public void freshReceiveAddressIsFirstUnused() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addHistoryOnly(BtcKeys.scriptHash(MNEMONIC, 0, 0), TXID);
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(BtcKeys.address(MNEMONIC, 0, 1), r.receiveAddress);
	}

	@Test
	public void emptyWalletHasZeroBalanceAndFirstAddress() throws IOException {
		FakeElectrum e = new FakeElectrum();
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(0L, r.balanceSat);
		assertEquals(BtcKeys.address(MNEMONIC, 0, 0), r.receiveAddress);
	}

	@Test
	public void changeChainIsScannedForBalance() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.changeScriptHash(MNEMONIC, 0, 0), TXID, 0, 12345);
		BtcWallet.ScanResult r = wallet(e).scan();
		assertEquals(12345L, r.balanceSat);
	}
}
