package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BroadcastDurabilityTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	static final class MemLog implements PendingLog {
		final Map<String, PendingTx> map = new LinkedHashMap<>();
		final List<String> stateHistory = new ArrayList<>();

		@Override
		public List<PendingTx> all() {
			return new ArrayList<>(map.values());
		}

		@Override
		public void put(PendingTx tx) {
			map.put(tx.id, tx);
			stateHistory.add(tx.state);
		}

		PendingTx only() {
			return map.values().iterator().next();
		}
	}

	private static BtcWallet wallet(FakeElectrum e, PendingLog log) {
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPendingLog(log);
		return w;
	}

	private static String outpoint0() {
		return TX0 + ":0";
	}

	@Test
	public void successfulSendIsRecordedThenMarkedSent() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		MemLog log = new MemLog();
		wallet(e, log).send(DEST, 50000, 1.0, false);
		assertEquals(PendingTx.SENT, log.only().state);
		assertTrue(log.stateHistory.contains(PendingTx.BROADCASTING));
		assertTrue(log.stateHistory.contains(PendingTx.SENT));
		assertTrue(log.only().outpoints.contains(outpoint0()));
	}

	@Test
	public void lostAckIsRecordedAsPossiblySentNotFailed() {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.broadcastError = new IOException("connection closed");
		MemLog log = new MemLog();
		assertThrows(IOException.class,
				() -> wallet(e, log).send(DEST, 50000, 1.0, false));
		assertEquals(PendingTx.POSSIBLY_SENT, log.only().state);
		assertTrue(log.only().outpoints.contains(outpoint0()));
	}

	@Test
	public void reservedInputIsExcludedSoRetryCannotDoubleSpend()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.broadcastError = new IOException("connection closed");
		MemLog log = new MemLog();
		assertThrows(IOException.class,
				() -> wallet(e, log).send(DEST, 50000, 1.0, false));

		e.broadcastError = null;
		BtcWallet.ScanResult r = wallet(e, log).scan();
		assertEquals(0L, r.balanceSat);
		assertTrue(r.utxos.isEmpty());
		assertThrows(IOException.class,
				() -> wallet(e, log).send(DEST, 50000, 1.0, false));
	}

	@Test
	public void reconcileMarksSentWhenTxIsOnChain() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.txs.put("theTxid", "00");
		MemLog log = new MemLog();
		log.put(new PendingTx("p1", "theTxid", "", Arrays.asList(outpoint0()),
				PendingTx.POSSIBLY_SENT, System.currentTimeMillis(), -50000L));
		wallet(e, log).scan();
		assertEquals(PendingTx.SENT, log.map.get("p1").state);
	}

	@Test
	public void reconcileMarksSentWhenInputsNoLongerUnspent()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		MemLog log = new MemLog();
		log.put(new PendingTx("p1", "unknownTxid", "",
				Arrays.asList(outpoint0()), PendingTx.POSSIBLY_SENT,
				System.currentTimeMillis(), -50000L));
		wallet(e, log).scan();
		assertEquals(PendingTx.SENT, log.map.get("p1").state);
	}

	@Test
	public void reconcileReleasesAfterGraceAndRepeatedDefinitiveMisses()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		MemLog log = new MemLog();
		log.put(new PendingTx("p1", "unknownTxid", "",
				Arrays.asList(outpoint0()), PendingTx.POSSIBLY_SENT, 0L, -50000L));
		BtcWallet w = wallet(e, log);
		w.scan();
		assertEquals(PendingTx.POSSIBLY_SENT, log.map.get("p1").state);
		w.scan();
		assertEquals(PendingTx.POSSIBLY_SENT, log.map.get("p1").state);
		BtcWallet.ScanResult r = w.scan();
		assertEquals(PendingTx.FAILED, log.map.get("p1").state);
		assertEquals(100000L, r.balanceSat);
	}

	@Test
	public void txidMismatchIsUncertainAndKeepsInputsReserved()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.returnWrongTxid = true;
		MemLog log = new MemLog();
		assertThrows(BroadcastUncertainException.class,
				() -> wallet(e, log).send(DEST, 50000, 1.0, false));
		assertEquals(PendingTx.POSSIBLY_SENT, log.only().state);
	}

	@Test
	public void reconcileNeverFailsOnTransportErrors() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.transportErrorOnGetTransaction = true;
		MemLog log = new MemLog();
		log.put(new PendingTx("p1", "unknownTxid", "",
				Arrays.asList(outpoint0()), PendingTx.POSSIBLY_SENT, 0L, -50000L));
		BtcWallet w = wallet(e, log);
		for (int i = 0; i < 5; i++) {
			w.scan();
		}
		assertEquals(PendingTx.POSSIBLY_SENT, log.map.get("p1").state);
	}
}
