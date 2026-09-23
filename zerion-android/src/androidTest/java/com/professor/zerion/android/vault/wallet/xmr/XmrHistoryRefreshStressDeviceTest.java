package com.professor.zerion.android.vault.wallet.xmr;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Hammers the read-only native entry points while the refresh thread is
 * running against a public mainnet daemon, and reopens the wallet repeatedly
 * while a refresh is in flight. Exercises the history, balance and height
 * reads that the JNI-01 remediation serialises against the refresh thread.
 * Intended to run under a sanitizer build of libzmonero; passes when no
 * native fault or Java exception occurs.
 */
@RunWith(AndroidJUnit4.class)
public class XmrHistoryRefreshStressDeviceTest {

	private static final String[] NODES = {
			"node.monerodevs.org:18089",
			"node.sethforprivacy.com:18089",
			"xmr-node.cakewallet.com:18081",
	};
	private static final int READERS = 4;
	private static final long STRESS_MS = 60_000L;
	private static final int REOPEN_ROUNDS = 6;

	private File dir;
	private MoneroEngine engine;

	@Before
	public void setUp() {
		Context ctx = ApplicationProvider.getApplicationContext();
		assertTrue(NativeMonero.isAvailable());
		dir = new File(ctx.getCacheDir(), "xmr-stress-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		engine = new NativeMoneroEngine();
	}

	@After
	public void tearDown() {
		File[] files = dir.listFiles();
		if (files != null) for (File f : files) f.delete();
		dir.delete();
	}

	private MoneroEngine.Session open(String name) {
		MoneroEngine.Session s = engine.create(new File(dir, name).getAbsolutePath(),
				"pw".toCharArray(), "English");
		assertNotNull(s);
		assertEquals(0, s.status());
		return s;
	}

	private String connect(MoneroEngine.Session s) {
		for (String node : NODES) {
			if (s.init(node, "", false)) return node;
		}
		return null;
	}

	@Test
	public void historyBalanceAndHeightReadsRaceTheRefreshThread()
			throws Exception {
		MoneroEngine.Session session = open("w");
		String node = connect(session);
		assertNotNull("no public mainnet node reachable", node);
		long tip = session.daemonHeight();
		if (tip > 5000) session.setRefreshFromHeight(tip - 3000);
		session.setAutoRefreshInterval(500);
		session.startRefresh();

		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger reads = new AtomicInteger();
		List<Thread> threads = new ArrayList<>();
		long deadline = System.currentTimeMillis() + STRESS_MS;
		for (int i = 0; i < READERS; i++) {
			final int id = i;
			Thread t = new Thread(() -> {
				try {
					while (System.currentTimeMillis() < deadline) {
						List<XmrTxInfo> h = session.history();
						assertNotNull(h);
						session.balance(0);
						session.unlockedBalance(0);
						session.blockchainHeight();
						session.address(0, 0);
						if (id == 0) session.connectionStatus();
						reads.incrementAndGet();
					}
				} catch (Throwable e) {
					failure.compareAndSet(null, e);
				}
			}, "reader-" + i);
			threads.add(t);
			t.start();
		}
		Thread toggler = new Thread(() -> {
			try {
				while (System.currentTimeMillis() < deadline) {
					Thread.sleep(3000);
					session.pauseRefresh();
					Thread.sleep(200);
					session.startRefresh();
					session.refresh();
				}
			} catch (Throwable e) {
				failure.compareAndSet(null, e);
			}
		}, "toggler");
		threads.add(toggler);
		toggler.start();
		for (Thread t : threads) t.join();
		assertNull("reader or toggler failed: " + failure.get(), failure.get());
		assertTrue("readers made progress", reads.get() > 100);
		session.stopRefresh();
		session.close();
	}

	@Test
	public void reopenWhileRefreshRunsDoesNotFault() throws Exception {
		MoneroEngine.Session first = open("r");
		String node = connect(first);
		assertNotNull("no public mainnet node reachable", node);
		long tip = first.daemonHeight();
		first.store(new File(dir, "r").getAbsolutePath());
		first.close();
		for (int round = 0; round < REOPEN_ROUNDS; round++) {
			MoneroEngine.Session s = engine.open(new File(dir, "r").getAbsolutePath(),
					"pw".toCharArray());
			assertNotNull("reopen round " + round, s);
			assertEquals(0, s.status());
			assertTrue(s.init(node, "", false));
			if (tip > 5000) s.setRefreshFromHeight(tip - 2000);
			s.setAutoRefreshInterval(200);
			s.startRefresh();
			long until = System.currentTimeMillis() + 2500;
			while (System.currentTimeMillis() < until) {
				s.history();
				s.balance(0);
				s.blockchainHeight();
			}
			if (round % 2 == 0) {
				s.close();
			} else {
				assertTrue(s.closePersisting());
			}
		}
	}
}
