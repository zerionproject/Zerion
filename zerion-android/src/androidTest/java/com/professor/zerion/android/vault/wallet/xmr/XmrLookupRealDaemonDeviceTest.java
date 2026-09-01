package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;

/**
 * Validates the nLookupTxs RPC mapping against a real mainnet monerod, so the
 * relay reconciliation in a later commit rests on proven daemon semantics and
 * not on a loopback emulation. It reads only; it never builds, signs or relays
 * a transaction and needs no funds.
 *
 * The MINED and MISSED vectors were confirmed directly against mainnet before
 * being committed: transaction
 * {@code 60b1b5731d7d7e96ef06a5f5d0f20392376945785466f6cb4e8fbdb2cdcd7c58} is
 * mined in block 3750100 (a depth of hundreds of thousands of blocks, well
 * beyond any reorg), and a syntactically valid but nonexistent txid is returned
 * in the daemon's missed list. This test requires network access to a public
 * clearnet node; that is why it is a device test and not part of the offline
 * suite.
 */
@RunWith(AndroidJUnit4.class)
public class XmrLookupRealDaemonDeviceTest {

	private static final String[] NODES = {
			"node.monerodevs.org:18089",
			"node.sethforprivacy.com:18089",
			"xmr-node.cakewallet.com:18081",
	};

	private static final String MINED_TXID =
			"60b1b5731d7d7e96ef06a5f5d0f20392376945785466f6cb4e8fbdb2cdcd7c58";
	private static final long MINED_HEIGHT = 3750100L;

	private static final String NONEXISTENT_TXID =
			"dead00000000000000000000000000000000000000000000000000000beef123";

	private File dir;
	private MoneroEngine engine;
	private MoneroEngine.Session session;

	@Before
	public void setUp() {
		Context ctx = ApplicationProvider.getApplicationContext();
		assertTrue(NativeMonero.isAvailable());
		dir = new File(ctx.getCacheDir(), "xmr-lookup-real-" + System.nanoTime());
		assertTrue(dir.mkdirs());
		engine = new NativeMoneroEngine();
		session = engine.create(new File(dir, "w").getAbsolutePath(),
				"pw".toCharArray(), "English");
		assertNotNull(session);
		assertEquals(0, session.status());
	}

	@After
	public void tearDown() {
		if (session != null) session.close();
		File[] files = dir.listFiles();
		if (files != null) for (File f : files) f.delete();
		dir.delete();
	}

	private String connectClearnetNode() {
		for (String node : NODES) {
			if (session.init(node, "", false)) return node;
		}
		return null;
	}

	@Test
	public void minedAndMissedAreMappedFromRealMonerod() {
		String node = connectClearnetNode();
		if (node == null) {
			fail("no public mainnet node reachable to validate lookup semantics");
		}
		List<XmrTxLookup> r = session.lookupTxs(
				Arrays.asList(MINED_TXID, NONEXISTENT_TXID), 20000);
		assertEquals(2, r.size());

		XmrTxLookup mined = r.get(0);
		XmrTxLookup missed = r.get(1);

		assertEquals("a deeply confirmed txid is mined, never missed",
				XmrTxLookup.Result.MINED, mined.result);
		assertEquals("mined at its real, non-negative height",
				MINED_HEIGHT, mined.blockHeight);
		assertEquals("ordering stays aligned with the request", MINED_TXID,
				mined.txid);

		assertEquals("a valid but nonexistent txid the daemon does not know",
				XmrTxLookup.Result.MISSED, missed.result);
		assertEquals(NONEXISTENT_TXID, missed.txid);
	}

	@Test
	public void malformedTxidIsAnErrorAndNeverLeavesTheDevice() throws Exception {
		int closedPort;
		try (ServerSocket s = new ServerSocket(0, 1,
				java.net.InetAddress.getLoopbackAddress())) {
			closedPort = s.getLocalPort();
		}
		assertTrue(session.init("127.0.0.1:" + closedPort, "", false));

		long start = System.currentTimeMillis();
		List<XmrTxLookup> r = session.lookupTxs(
				Arrays.asList("NOT-HEX", MINED_TXID.toUpperCase()), 20000);
		long elapsed = System.currentTimeMillis() - start;

		for (XmrTxLookup x : r) {
			assertEquals(XmrTxLookup.Result.LOOKUP_ERROR, x.result);
		}
		assertTrue("with no valid txid the daemon is never contacted, so the "
				+ "call returns immediately rather than waiting on the socket, "
				+ "elapsed=" + elapsed, elapsed < 2000);
	}

	@Test
	public void transportFailureIsErrorNeverMissed() throws Exception {
		int closedPort;
		try (ServerSocket s = new ServerSocket(0, 1,
				java.net.InetAddress.getLoopbackAddress())) {
			closedPort = s.getLocalPort();
		}
		assertTrue(session.init("127.0.0.1:" + closedPort, "", false));
		List<XmrTxLookup> r = session.lookupTxs(
				Arrays.asList(MINED_TXID, NONEXISTENT_TXID), 5000);
		for (XmrTxLookup x : r) {
			assertEquals("an unreachable daemon is an error, not negative "
					+ "evidence", XmrTxLookup.Result.LOOKUP_ERROR, x.result);
		}
	}
}
