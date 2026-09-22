package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
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
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPendingLog(log);
		return w;
	}

	private static String outpoint0() {
		return TX0 + ":0";
	}

	/**
	 * BTC-12: when no connection to any broadcast server can be opened, no
	 * byte of the transaction left the device, so the send is a plain
	 * failure with its inputs still spendable, not an uncertain broadcast.
	 */
	@Test
	public void connectionFailureBeforeSendingIsFailedNotPossiblySent()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		MemLog log = new MemLog();
		final boolean[] refuse = {false};
		FakeElectrum.RecordingFactory scanFactory =
				new FakeElectrum.RecordingFactory(e);
		ElectrumRpc.Factory factory = (ep, port, tag) -> {
			if (refuse[0] && tag.endsWith("-b")) {
				throw new IOException("Tor is down");
			}
			return scanFactory.open(ep, port, tag);
		};
		BtcWallet w = new BtcWallet(MNEMONIC.toCharArray(), 0, 9999, "host", 50001,
				"walletA", factory, (url, tag) -> null);
		w.setPendingLog(log);
		BtcWallet.SendPlan plan = w.planSend(DEST, 40000, 2.0, false, null,
				false);
		refuse[0] = true;
		try {
			w.signPlan(plan);
			fail();
		} catch (BroadcastUncertainException uncertain) {
			fail("an unopened connection is not an uncertain broadcast");
		} catch (IOException expected) {
		}
		assertEquals(PendingTx.FAILED, log.only().state);
		assertFalse(log.stateHistory.contains(PendingTx.BROADCASTING));
		assertFalse(log.stateHistory.contains(PendingTx.POSSIBLY_SENT));
		assertTrue(e.broadcasts.isEmpty());
		refuse[0] = false;
		BtcWallet.ScanResult r = w.scan();
		assertEquals("inputs stay spendable", 100000, r.balanceSat);
	}

	/**
	 * BTC-11: a sent transaction whose inputs are still unspent stays
	 * reserved while the network can still confirm it, however old it is;
	 * it is released only once both servers say it is absent.
	 */
	@Test
	public void agedSentInputsStayReservedWhileTheTransactionIsLive()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		MemLog log = new MemLog();
		String txid = "4".repeat(64);
		e.txs.put(txid, "01000000000000000000");
		long threeHoursAgo = System.currentTimeMillis() - 3L * 60 * 60 * 1000;
		log.put(new PendingTx("p1", txid, "00", java.util.Arrays.asList(
				outpoint0()), PendingTx.SENT, threeHoursAgo, -100000));
		BtcWallet w = wallet(e, log);
		assertEquals("live sent inputs stay reserved", 0,
				w.scan().balanceSat);
	}

	@Test
	public void agedSentInputsAreReleasedOnlyWhenProvablyAbsent()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		MemLog log = new MemLog();
		String txid = "5".repeat(64);
		long threeHoursAgo = System.currentTimeMillis() - 3L * 60 * 60 * 1000;
		log.put(new PendingTx("p1", txid, "00", java.util.Arrays.asList(
				outpoint0()), PendingTx.SENT, threeHoursAgo, -100000));
		BtcWallet w = wallet(e, log);
		assertEquals("first miss keeps the reservation", 0,
				w.scan().balanceSat);
		assertEquals(0, w.scan().balanceSat);
		assertEquals("third definitive miss after the grace releases",
				100000, w.scan().balanceSat);
		assertEquals(PendingTx.FAILED, log.only().state);
	}

	@Test
	public void successfulSendIsRecordedThenMarkedSent() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
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
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
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
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
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
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.txs.put("theTxid", "00");
		MemLog log = new MemLog();
		log.put(new PendingTx("p1", "theTxid", "", Arrays.asList(outpoint0()),
				PendingTx.POSSIBLY_SENT, System.currentTimeMillis(), -50000L));
		wallet(e, log).scan();
		assertEquals(PendingTx.SENT, log.map.get("p1").state);
	}

	/**
	 * A2-BTC-01: a server that relays the transaction and still answers
	 * with an error must not free the inputs. The claimed rejection is an
	 * uncertain broadcast: the record stays possibly sent, the inputs stay
	 * reserved while the server shows them as unspent, a retry cannot pick
	 * them, and once the server hides the inputs the record settles and the
	 * payment stays visible as pending instead of vanishing as failed.
	 */
	@Test
	public void aClaimedRejectionIsUncertainAndKeepsInputsReserved()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		String sh = TestKeys.scriptHash(MNEMONIC, 0, 0);
		e.addUtxo(sh, TX0, 0, 100000);
		e.rejectBroadcast = true;
		MemLog log = new MemLog();
		assertThrows(BroadcastUncertainException.class,
				() -> wallet(e, log).send(DEST, 50000, 1.0, false));
		assertEquals(1, e.broadcasts.size());
		assertEquals(PendingTx.POSSIBLY_SENT, log.only().state);
		assertFalse("never a false failure once bytes reached a server",
				log.stateHistory.contains(PendingTx.FAILED));

		e.rejectBroadcast = false;
		BtcWallet.ScanResult r = wallet(e, log).scan();
		assertEquals("inputs stay reserved", 0L, r.balanceSat);
		assertThrows(IOException.class,
				() -> wallet(e, log).send(DEST, 50000, 1.0, false));
		assertEquals("a retry sent nothing", 1, e.broadcasts.size());

		e.unspent.remove(sh);
		wallet(e, log).scan();
		assertEquals(PendingTx.SETTLED, log.only().state);
		BtcWallet w = wallet(e, log);
		assertEquals("the payment stays visible", 1,
				w.pendingSummaries().size());
		assertEquals(BtcWallet.STATE_PENDING,
				w.pendingSummaries().get(0).state);
	}

	/** A2-BTC-13: only settled or failed records may leave the journal. */
	@Test
	public void onlySettledOrFailedRecordsArePrunable() {
		long cutoff = 1_000L;
		PendingTx sent = new PendingTx("p", "t", "00", Arrays.asList(outpoint0()),
				PendingTx.SENT, 0L, -1L);
		assertFalse("live inputs keep the record", sent.prunableAt(cutoff));
		assertFalse(sent.withState(PendingTx.POSSIBLY_SENT).prunableAt(cutoff));
		assertTrue(sent.withState(PendingTx.SETTLED).prunableAt(cutoff));
		assertTrue(sent.withState(PendingTx.FAILED).prunableAt(cutoff));
		assertFalse("not before the cutoff", new PendingTx("p", "t", "00",
				Arrays.asList(outpoint0()), PendingTx.SETTLED, cutoff, -1L)
				.prunableAt(cutoff));
	}

	@Test
	public void reconcileMarksSettledWhenInputsNoLongerUnspent()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		MemLog log = new MemLog();
		log.put(new PendingTx("p1", "unknownTxid", "",
				Arrays.asList(outpoint0()), PendingTx.POSSIBLY_SENT,
				System.currentTimeMillis(), -50000L));
		wallet(e, log).scan();
		assertEquals(PendingTx.SETTLED, log.map.get("p1").state);
	}

	@Test
	public void reconcileReleasesAfterGraceAndRepeatedDefinitiveMisses()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
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
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.returnWrongTxid = true;
		MemLog log = new MemLog();
		assertThrows(BroadcastUncertainException.class,
				() -> wallet(e, log).send(DEST, 50000, 1.0, false));
		assertEquals(PendingTx.POSSIBLY_SENT, log.only().state);
	}

	@Test
	public void reconcileNeverFailsOnTransportErrors() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(TestKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
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
