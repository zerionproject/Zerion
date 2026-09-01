package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class XmrSyncManagerTest {

	private static final String N1 =
			"2chk3x3x2iyreog6y2vhljpraqmwiqdmmafhiiab443t7xyfeadqfuad.onion:18089";
	private static final String N2 =
			"4iv75ceaj2xjqne6d5d35xxk7lkcj6zdtpsbp7sq6sobp44b7txqrcid.onion:18089";

	private final ExecutorService exec = Executors.newSingleThreadExecutor();
	private final Sink sink = new Sink();
	private final AtomicBoolean current = new AtomicBoolean(true);
	private XmrSyncManager sync;

	@After
	public void tearDown() {
		if (sync != null) sync.stop();
		exec.shutdownNow();
	}

	/** Fast polling; stall thresholds far beyond any test's duration. */
	private XmrSyncManager manager() {
		sync = new XmrSyncManager(exec, (w, e) -> current.get(), sink, 200, 60,
				60_000, 120_000);
		return sync;
	}

	/** Short stall thresholds so stall handling can be exercised quickly. */
	private XmrSyncManager managerShortStall() {
		sync = new XmrSyncManager(exec, (w, e) -> current.get(), sink, 200, 60,
				300, 900);
		return sync;
	}

	private static List<XmrNode> nodes(String... specs) {
		List<XmrNode> l = new ArrayList<>();
		for (String s : specs) l.add(XmrNode.parse(s, XmrNode.Source.VETTED, false));
		return l;
	}

	@Test
	public void torPortZeroIsOfflineAndNeverConnects() throws Exception {
		Script s = new Script();
		manager().start("A", 1, s, nodes(N1), 0);
		sink.await(st -> st.state == XmrSyncState.OFFLINE, 3000);
		assertEquals("no connect attempted without Tor", 0, s.initCalls.size());
	}

	@Test
	public void connectsOverTorNeverDirect() throws Exception {
		Script s = new Script();
		s.connect = 1;
		manager().start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.CONNECTED
				|| st.state == XmrSyncState.SYNCHRONIZING, 3000);
		assertTrue(s.initCalls.size() >= 1);
		for (String[] call : s.initCalls) {
			assertEquals("Tor node must be given the SOCKS proxy",
					"127.0.0.1:9050", call[1]);
		}
	}

	@Test
	public void sequentialFailoverToSecondNodeInOrder() throws Exception {
		Script s = new Script();
		s.failInitFor.add(hostOf(N1));
		s.connect = 1;
		manager().start("A", 1, s, nodes(N1, N2), 9050);
		sink.await(st -> st.nodeLabel != null && st.nodeLabel.startsWith("4iv75")
				&& (st.state == XmrSyncState.CONNECTED
				|| st.state == XmrSyncState.SYNCHRONIZING), 4000);
		assertTrue("tried N1 before N2", s.initCalls.size() >= 2);
		assertEquals(hostOf(N1), s.initCalls.get(0)[0].split(":")[0]);
	}

	@Test
	public void syncedOnlyFromHeightsNotFromRefreshOrBalance() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 200;
		s.bal = 5_000_000_000_000L;
		manager().start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING
				&& st.daemonHeight == 200, 4000);
		assertFalse("behind tip is never SYNCED", sink.hasState(XmrSyncState.SYNCED));
		s.wh = 200;
		sink.await(st -> st.state == XmrSyncState.SYNCED, 4000);
	}

	@Test
	public void backgroundScanIsStartedOnceAndNotDuplicated() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 200;
		manager().start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING, 3000);
		Thread.sleep(400);
		assertEquals("the observer never issues its own refresh", 0,
				s.refreshCalls.get());
		assertEquals("one background scan is started", 1,
				s.startRefreshCalls.get());
	}

	@Test
	public void reachesSyncedAutomaticallyWithoutReopen() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 200;
		manager().start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING, 3000);
		s.wh = 150;
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING
				&& st.walletHeight == 150, 3000);
		s.wh = 200;
		sink.await(st -> st.state == XmrSyncState.SYNCED
				&& st.walletHeight == 200, 3000);
		assertEquals("no failover happened during a healthy scan", 1,
				s.initCalls.size());
	}

	@Test
	public void newBlockAfterSyncedIsDetectedIncrementally() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 200;
		s.dh = 200;
		manager().start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCED, 3000);
		s.wh = 201;
		sink.await(st -> st.state == XmrSyncState.SYNCED
				&& st.walletHeight == 201, 3000);
		assertEquals(1, s.initCalls.size());
	}

	@Test
	public void refreshRequestsCoalesceAndWakeTheWalletOnce() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 200;
		s.dh = 200;
		XmrSyncManager m = manager();
		m.start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCED, 3000);
		int wakesBefore = s.startRefreshCalls.get();
		for (int i = 0; i < 25; i++) m.requestRefresh();
		sink.await(st -> st.state == XmrSyncState.SYNCED && st.checking, 3000);
		Thread.sleep(500);
		assertEquals("25 rapid taps produce one wake", wakesBefore + 1,
				s.startRefreshCalls.get());
		assertEquals("a refresh request never reconnects", 1,
				s.initCalls.size());
		assertEquals("a refresh request never resets the restore height", 0,
				s.setRefreshFromHeightCalls.get());
		assertEquals("a refresh request never issues a blocking refresh", 0,
				s.refreshCalls.get());
	}

	@Test
	public void marksRecoveringFromSeedBeforeConnectingSoHistoryIsScanned()
			throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 1;
		s.dh = 3_752_000L;
		manager().start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.CONNECTED
				|| st.state == XmrSyncState.SYNCHRONIZING, 4000);
		assertTrue("recovering-from-seed is set so init does not fast-forward "
				+ "the refresh height to the tip",
				s.setRecoveringFromSeedCalls.get() >= 1);
		assertTrue("it is set before the connect that would clobber the height",
				s.recoveringSetBeforeFirstInit);
	}

	@Test
	public void recoveringFromSeedSetBeforeEachConnectOnFailover()
			throws Exception {
		Script s = new Script();
		s.failInitFor.add(hostOf(N1));
		s.connect = 1;
		manager().start("A", 1, s, nodes(N1, N2), 9050);
		sink.await(st -> st.nodeLabel != null && st.nodeLabel.startsWith("4iv75")
				&& (st.state == XmrSyncState.CONNECTED
				|| st.state == XmrSyncState.SYNCHRONIZING), 4000);
		assertTrue("recovering-from-seed is re-marked before every connect "
				+ "attempt, including a failover connect",
				s.setRecoveringFromSeedCalls.get() >= s.initCalls.size());
	}

	@Test
	public void slowBatchIsNotTreatedAsNodeFailure() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 200;
		manager().start("A", 1, s, nodes(N1, N2), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING, 3000);
		Thread.sleep(1200);
		assertEquals("a pause in progress must not trigger failover", 1,
				s.initCalls.size());
	}

	@Test
	public void walletErrorWithDisconnectFailsOverImmediately() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 200;
		manager().start("A", 1, s, nodes(N1, N2), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING, 3000);
		s.status = 1;
		s.connect = 0;
		s.connectAfterInit = 1;
		sink.await(st -> st.nodeLabel != null && st.nodeLabel.startsWith("4iv75"),
				4000);
		assertTrue("failed over to the next node", s.initCalls.size() >= 2);
	}

	@Test
	public void longStallWhileDisconnectedFailsOver() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 200;
		managerShortStall().start("A", 1, s, nodes(N1, N2), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING, 3000);
		s.connect = 0;
		s.connectAfterInit = 1;
		sink.await(st -> st.nodeLabel != null && st.nodeLabel.startsWith("4iv75"),
				5000);
	}

	@Test
	public void allNodesUnavailableBecomesOfflineNotEndlessSpinner()
			throws Exception {
		Script s = new Script();
		s.connect = 0;
		manager().start("A", 1, s, nodes(N1, N2), 9050);
		sink.await(st -> st.state == XmrSyncState.OFFLINE, 5000);
		assertTrue("both nodes attempted once before offline",
				s.initCalls.size() >= 2);
		assertFalse("offline releases the session", sync.isActive());
	}

	@Test
	public void lockStopsPublishingAndInterruptsRefresh() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 200;
		s.dh = 200;
		manager().start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCED, 4000);
		int before = sink.all().size();
		current.set(false);
		sync.stop();
		assertTrue("refresh interrupted on stop", s.stopRefreshCalled.get());
		Thread.sleep(500);
		int after = sink.all().size();
		assertTrue("no meaningful publishing after lock",
				after - before <= 1);
	}

	@Test
	public void staleWalletNeverPublishes() throws Exception {
		Script s = new Script();
		s.connect = 1;
		current.set(false);
		manager().start("A", 1, s, nodes(N1), 9050);
		Thread.sleep(600);
		assertTrue("no status for a non-current wallet", sink.all().isEmpty());
	}

	@Test
	public void malformedDaemonHeightsDoNotFalselySync() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 9_999_999L;
		s.dh = 0;
		manager().start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.CONNECTED, 4000);
		Thread.sleep(400);
		assertFalse("daemonHeight 0 must never be SYNCED",
				sink.hasState(XmrSyncState.SYNCED));
	}

	@Test
	public void sessionWorkSubmittedDuringSyncRunsPromptlyOnSessionThread()
			throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 200;
		XmrSyncManager m = manager();
		m.start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING, 3000);
		java.util.concurrent.FutureTask<String> task =
				new java.util.concurrent.FutureTask<>(
						() -> Thread.currentThread().getName());
		m.submit(task);
		String thread = task.get(3, java.util.concurrent.TimeUnit.SECONDS);
		assertTrue("ran on the session executor thread while the loop owns it",
				thread != null && !thread.equals(Thread.currentThread().getName()));
		assertTrue("the loop keeps observing afterwards",
				sink.hasState(XmrSyncState.SYNCHRONIZING));
	}

	private static String hostOf(String spec) {
		return spec.split(":")[0];
	}

	private static final class Sink implements XmrSyncManager.Sink {
		private final List<XmrSyncStatus> list = new CopyOnWriteArrayList<>();

		@Override
		public void publish(XmrSyncStatus status) {
			list.add(status);
		}

		List<XmrSyncStatus> all() {
			return list;
		}

		boolean hasState(XmrSyncState s) {
			for (XmrSyncStatus st : list) if (st.state == s) return true;
			return false;
		}

		void await(Predicate<XmrSyncStatus> p, long timeoutMs) throws Exception {
			long deadline = System.currentTimeMillis() + timeoutMs;
			while (System.currentTimeMillis() < deadline) {
				for (XmrSyncStatus st : list) if (p.test(st)) return;
				Thread.sleep(25);
			}
			throw new AssertionError("condition not reached; states="
					+ statesSeen());
		}

		private List<XmrSyncState> statesSeen() {
			List<XmrSyncState> out = new ArrayList<>();
			for (XmrSyncStatus st : list) out.add(st.state);
			return out;
		}
	}

	private static final class Script implements MoneroEngine.Session {
		final List<String[]> initCalls = new CopyOnWriteArrayList<>();
		final List<String> failInitFor = Collections.synchronizedList(
				new ArrayList<>());
		final AtomicBoolean stopRefreshCalled = new AtomicBoolean(false);
		final AtomicInteger refreshCalls = new AtomicInteger();
		final AtomicInteger startRefreshCalls = new AtomicInteger();
		final AtomicInteger setRefreshFromHeightCalls = new AtomicInteger();
		final AtomicInteger setRecoveringFromSeedCalls = new AtomicInteger();
		volatile boolean recoveringSetBeforeFirstInit = false;
		volatile int connect = 0;
		volatile int connectAfterInit = -1;
		volatile int status = 0;
		volatile long wh, dh, bal, unl;
		volatile java.util.List<XmrTxInfo> hist = new ArrayList<>();

		@Override
		public boolean init(String daemonAddress, String proxyAddress,
				boolean trustedDaemon) {
			initCalls.add(new String[]{daemonAddress, proxyAddress});
			String host = daemonAddress.split(":")[0];
			boolean ok = !failInitFor.contains(host);
			if (ok && connectAfterInit >= 0 && initCalls.size() > 1) {
				connect = connectAfterInit;
				status = 0;
			}
			return ok;
		}

		@Override
		public int connectionStatus() {
			String last = initCalls.isEmpty() ? null
					: initCalls.get(initCalls.size() - 1)[0].split(":")[0];
			if (last != null && failInitFor.contains(last)) return 0;
			return connect;
		}

		@Override
		public boolean refresh() {
			refreshCalls.incrementAndGet();
			return true;
		}

		@Override
		public void setAutoRefreshInterval(int millis) {
		}

		@Override
		public void startRefresh() {
			startRefreshCalls.incrementAndGet();
		}

		@Override
		public void pauseRefresh() {
		}

		@Override
		public void stopRefresh() {
			stopRefreshCalled.set(true);
		}

		@Override
		public long blockchainHeight() {
			return wh;
		}

		@Override
		public long daemonHeight() {
			return dh;
		}

		@Override
		public boolean isSynchronized() {
			return dh > 0 && wh >= dh;
		}

		@Override
		public long balance(long account) {
			return bal;
		}

		@Override
		public long unlockedBalance(long account) {
			return unl;
		}

		@Override
		public java.util.List<XmrTxInfo> history() {
			return hist;
		}

		@Override
		public void setRefreshFromHeight(long height) {
			setRefreshFromHeightCalls.incrementAndGet();
		}

		@Override
		public void setRecoveringFromSeed(boolean recovering) {
			setRecoveringFromSeedCalls.incrementAndGet();
			if (recovering && initCalls.isEmpty()) {
				recoveringSetBeforeFirstInit = true;
			}
		}

		@Override
		public String address(long account, long subaddress) {
			return "4";
		}

		@Override
		public void addSubaddress(long account, String label) {
		}

		@Override
		public long numSubaddresses(long account) {
			return 1;
		}

		@Override
		public int status() {
			return status;
		}

		@Override
		public String errorString() {
			return "";
		}

		@Override
		public char[] seed(char[] seedOffset) {
			return new char[0];
		}

		@Override
		public boolean store(String path) {
			return true;
		}

		@Override
		public MoneroEngine.Prepared prepare(String a, long amt, int p, long ac) {
			return null;
		}

		@Override
		public boolean waitRefreshIdle(long timeoutMs) {
			return true;
		}

		@Override
		public java.util.List<XmrTxLookup> lookupTxs(
				java.util.List<String> txids, long timeoutMs) {
			return XmrTxLookup.decode(txids, null);
		}

		@Override
		public void closePersisting() {
		}

		@Override
		public void close() {
		}
	}

	private static final String TXID_A =
			"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String TXID_B =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	@Test
	public void historyPublishedWhileSynchronizingNotYetSynced()
			throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 200;
		s.hist = Collections.singletonList(tx(TXID_A, 0, true));
		java.util.List<java.util.List<XmrTxInfo>> published =
				new CopyOnWriteArrayList<>();
		XmrSyncManager m = manager();
		m.setHistorySink(published::add);
		m.start("A", 1, s, nodes(N1), 9050);
		sink.await(st -> st.state == XmrSyncState.SYNCHRONIZING, 4000);
		assertFalse("behind tip is never SYNCED",
				sink.hasState(XmrSyncState.SYNCED));
		awaitHistory(published);
	}

	@Test
	public void historyStaysVisibleWhenDaemonHeightUnknown() throws Exception {
		Script s = new Script();
		s.connect = 1;
		s.wh = 100;
		s.dh = 0;
		s.hist = Collections.singletonList(tx(TXID_A, 2, false));
		java.util.List<java.util.List<XmrTxInfo>> published =
				new CopyOnWriteArrayList<>();
		XmrSyncManager m = manager();
		m.setHistorySink(published::add);
		m.start("A", 1, s, nodes(N1), 9050);
		assertFalse("daemonHeight 0 is never SYNCED",
				sink.hasState(XmrSyncState.SYNCED));
		awaitHistory(published);
	}

	private static void awaitHistory(
			java.util.List<java.util.List<XmrTxInfo>> published)
			throws Exception {
		long deadline = System.currentTimeMillis() + 4000;
		while (System.currentTimeMillis() < deadline) {
			for (java.util.List<XmrTxInfo> h : published) {
				if (!h.isEmpty()) return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError(
				"history with transactions was never published while not synced");
	}

	private static XmrTxInfo tx(String id, long conf, boolean pending) {
		return XmrTxInfo.parse(id + ",0,1000,0,100,0," + conf + ",0,"
				+ (pending ? "1" : "0") + ",0");
	}

	@Test
	public void historyFingerprintDetectsIncomingAndConfirmationChanges() {
		List<XmrTxInfo> pendingOne =
				Collections.singletonList(tx(TXID_A, 0, true));
		String base = XmrSyncManager.historyFingerprint(pendingOne);

		assertEquals("identical history yields the same fingerprint", base,
				XmrSyncManager.historyFingerprint(
						Collections.singletonList(tx(TXID_A, 0, true))));
		assertNotEquals("pending to confirmed must republish", base,
				XmrSyncManager.historyFingerprint(
						Collections.singletonList(tx(TXID_A, 1, false))));
		assertNotEquals("a confirmation count change must republish", base,
				XmrSyncManager.historyFingerprint(
						Collections.singletonList(tx(TXID_A, 3, false))));
		assertNotEquals("a new incoming transaction must republish", base,
				XmrSyncManager.historyFingerprint(java.util.Arrays.asList(
						tx(TXID_A, 0, true), tx(TXID_B, 0, true))));
	}
}
